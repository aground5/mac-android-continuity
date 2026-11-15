package com.peri.display.vm

import android.util.Log
import com.peri.display.crypto.DATAGRAM_CHANNEL_ID_BYTES
import com.peri.display.crypto.PeriCryptoSession
import com.peri.display.crypto.PeriDatagramKeys
import com.peri.display.proto.ControlMessage
import com.peri.display.transport.WireFormat

private const val TAG = "SessionCrypto"

/**
 * **이 세션의 모든 봉인이 지나가는 한 곳.**
 *
 * 예전에는 `session`·`datagram`·`datagramChannelId`·`sendLock` 넷이 `MirrorController`의
 * 필드로 흩어져 있었고, 봉인하는 코드가 여섯 군데에 있었다(제어·Bye·RESUME·HELLO·확장
 * 영상·공유 영상). 그중 셋은 `synchronized(sendLock)`을 기억해야 했고 — 카운터 nonce라
 * 빠뜨리면 조용히 nonce가 겹친다 — 둘은 "AOA면 세션 키, LAN이면 세대 키"를 각자 다시
 * 판단했다. 같은 판단이 두 곳에 있으면 언젠가 한쪽만 바뀐다.
 *
 * 여기 모으면 그 두 규칙이 각각 한 번만 쓰인다:
 *
 *  · **직렬화** — 채널0 seal은 카운터 nonce다. 여러 스레드(keepalive·영상·회전·알림)에서
 *    불리므로 반드시 한 줄로 세워야 한다.
 *  · **레인별 키** — 신뢰 레인은 언제나 세션 키. 최신성 레인은 케이블이면 세션 키,
 *    LAN이면 **지금 열린 세대**의 키다.
 *
 * ## 세대(datagram epoch)가 따로 있는 이유
 *
 * 세션은 TCP 하나로 오래 살고, 세대는 **영상을 나르는 앱이 떠 있는 동안만** 산다.
 * 키가 세션 재료에서 `channel_id`를 소금으로 갈라 나오므로 `UdpOpen`을 받는 순간
 * 우리도 키를 갖는다 — 첫 데이터그램이 곧바로 봉인돼 나간다(0-RTT). 세대마다 소금이
 * 바뀌니 채널을 닫았다 다시 열어도 예전 세대의 (키, nonce)와 겹칠 수 없다.
 *
 * **AOA에는 세대가 없다.** 세대가 있는 이유(짧은 NAT 수명 때문에 채널을 열었다 닫았다
 * 하고, 그때 frame_id가 되감기면 (키, nonce)가 겹친다)가 케이블에서는 통째로 성립하지
 * 않는다 — 파이프는 링크가 살아 있는 동안 계속 열려 있다.
 */
class SessionCrypto {

    /** 채널0 seal의 카운터 nonce를 지킨다. **모든 봉인이 이 락 안에서 일어난다.** */
    private val sendLock = Any()

    @Volatile
    var session: PeriCryptoSession? = null
        private set

    /** 지금 열려 있는 데이터그램 세대의 id. null = UDP 경로가 없다. */
    @Volatile
    var channelId: ByteArray? = null
        private set

    @Volatile
    private var epoch: PeriDatagramKeys? = null

    /** 세션 키가 서 있는가 — 제어 메시지를 보낼 수 있는지의 유일한 근거. */
    val isLive: Boolean get() = session != null

    /** 최신성 레인이 열려 있는가(LAN 기준). 케이블은 세션 키로 나가므로 이 값과 무관하다. */
    val hasEpoch: Boolean get() = epoch != null

    fun start(s: PeriCryptoSession) {
        session = s
    }

    /**
     * 세션을 놓는다. **세대는 세션보다 오래 살 수 없다** — 키가 세션 재료에서 갈라
     * 나오기 때문이다.
     */
    fun clear() {
        session = null
        closeEpoch()
    }

    // MARK: - 데이터그램 세대

    /**
     * Mac이 부여한 세대를 연다. **0-RTT: 키가 이 줄에서 나온다.**
     * id 길이가 어긋나면(프로토콜 오류) 열지 않는다.
     */
    fun openEpoch(id: ByteArray): Boolean {
        val s = session ?: return false
        if (id.size != DATAGRAM_CHANNEL_ID_BYTES) {
            Log.e(TAG, "세대 id 길이가 ${id.size}B — 버린다")
            return false
        }
        channelId = id
        epoch = s.datagram(id)
        return true
    }

    /** 이미 열린 세대와 같은 id인가 — 늦게 온 통보를 거르는 근거. */
    fun isCurrentEpoch(id: ByteArray): Boolean = id.contentEquals(channelId)

    fun closeEpoch() {
        channelId = null
        epoch = null
    }

    // MARK: - 신뢰 레인 (제어)

    /** 제어 프레임 하나를 만든다. 세션 키가 없으면 null — 호출부가 조용히 버린다. */
    fun controlFrame(msg: ControlMessage): ByteArray? {
        val s = session ?: return null
        return runCatching {
            synchronized(sendLock) {
                val (nonce, body) = s.seal(msg.toByteArray())
                WireFormat.controlFrame(nonce, body)
            }
        }.getOrElse {
            Log.e(TAG, "제어 봉인 실패: ${it.message}")
            null
        }
    }

    fun openControl(nonce: ByteArray, body: ByteArray): ControlMessage? {
        val s = session ?: return null
        val plain = runCatching { s.open(nonce, body) }.getOrNull() ?: return null
        return runCatching { ControlMessage.parseFrom(plain) }.getOrNull()
    }

    /**
     * 새 경로에 보낼 RESUME 프레임. Mac은 이걸 시도복호해서 "이 연결은 살아 있는 그
     * 세션의 것"임을 확인하고 제어 채널만 교체한다.
     */
    fun resumeFrame(token: ByteArray): ByteArray? {
        val s = session ?: return null
        return runCatching {
            synchronized(sendLock) {
                val (nonce, body) = s.seal(token)
                WireFormat.resumeFrame(nonce, body)
            }
        }.getOrNull()
    }

    /**
     * 영상 채널 개시/재등록 HELLO — `[1B 0][16B 세대 id][12B nonce][sealed(세대 id)]`.
     *
     * Mac은 평문 id로 세대를 찾고 봉인으로 검증한다. 주소가 달라져도 같은 세대로 붙으므로
     * 핸드오버 뒤 **재등록**에도 그대로 쓴다 — 세대는 경로가 갈려도 살아남는다(세션 키가
     * 그대로이므로 키도 그대로다).
     */
    fun helloDatagram(): ByteArray? {
        val keys = epoch ?: return null
        val id = channelId ?: return null
        val (nonce, body) = synchronized(sendLock) { keys.seal(id) }
        return WireFormat.udpHelloDatagram(id, nonce, body)
    }

    /**
     * 경로 생사 확인용 PING — 와이어 모양은 [helloDatagram]과 같다(HELLO 참조 참고).
     * 다른 함수로 가른 이유는 봉인 자체는 같아도 **의미가 다르기 때문**이다: HELLO는
     * "이 세대를 (재)등록해라"이고 PING은 "살아 있으면 답해라"다. 호출부가 어느 쪽을
     * 보냈는지 헷갈리면 로그도, 재시도 카운터도 뒤섞인다.
     */
    fun pingDatagram(): ByteArray? {
        val keys = epoch ?: return null
        val id = channelId ?: return null
        val (nonce, body) = synchronized(sendLock) { keys.seal(id) }
        return WireFormat.udpPingDatagram(id, nonce, body)
    }

    /**
     * PONG 하나. **지금 세대의 것인지까지 확인한다** — 세대가 갈린 뒤 늦게 도착한 PONG이
     * "경로가 살아 있다"는 잘못된 결론으로 이어지면 안 되므로, 복호화된 평문(세대 id)이
     * 지금 [channelId]와 같을 때만 true다.
     */
    fun openPong(nonce: ByteArray, body: ByteArray): Boolean {
        val keys = epoch ?: return false
        val id = channelId ?: return false
        val plain = runCatching { keys.open(nonce, body) }.getOrNull() ?: return false
        return plain.contentEquals(id)
    }

    // MARK: - 최신성 레인 (영상)
    //
    // [reliablePipe]가 곧 "케이블인가"다. 그 한 값으로 키가 갈린다 — 이 판단이 여기
    // 두 함수에만 있어야 송신과 수신이 어긋날 수 없다.
    //
    // ★ 여기는 [sendLock]을 잡지 않는다. 영상 nonce는 `frame_id`에서 유도되므로 카운터가
    //   없고, 인코더 스레드가 프레임마다 제어 송신과 락을 다투게 만들 이유가 없다.

    fun sealVideo(plain: ByteArray, frameId: Long, reliablePipe: Boolean): ByteArray? {
        val seal: (ByteArray, Long) -> ByteArray = if (reliablePipe) {
            val s = session ?: return null
            { p, i -> s.sealVideo(p, i) }
        } else {
            val k = epoch ?: return null   // 세대가 없으면 보낼 UDP 경로 자체가 없다
            { p, i -> k.sealVideo(p, i) }
        }
        return runCatching { seal(plain, frameId) }.getOrElse {
            Log.e(TAG, "영상 봉인 실패: ${it.message}")
            null
        }
    }

    fun openVideo(body: ByteArray, frameId: Long, reliablePipe: Boolean): ByteArray? {
        return runCatching {
            if (reliablePipe) session?.openVideo(body, frameId)
            else epoch?.openVideo(body, frameId)
        }.getOrElse {
            Log.e(TAG, "영상 복호 실패(${if (reliablePipe) "세션" else "세대"} 키): ${it.message}")
            null
        }
    }
}
