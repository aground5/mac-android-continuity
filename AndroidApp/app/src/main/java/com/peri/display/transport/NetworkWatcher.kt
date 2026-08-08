package com.peri.display.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.Inet4Address

private const val TAG = "NetworkWatcher"

/**
 * 기본망(default network) 변화 감시자 — 핸드오버 시 ICE를 다시 돌리는 트리거.
 *
 * **왜 "연결 유지"가 아니라 재협상인가.** Android의 IP가 바뀌면
 *  - TCP 제어 채널은 4-tuple이 깨져 죽는다. 살릴 방법이 없다(migration은 QUIC 얘기).
 *  - UDP 소켓은 wildcard bind라 로컬 포트는 유지되지만, 나가는 소스 주소가 바뀌어
 *    **NAT 매핑이 새것**이 된다. 상대가 뚫어 둔 구멍은 무효고, Mac 입장에선 모르는
 *    주소에서 오는 패킷이라 새 피어로 잡힌다. 그래서 영상은 에러 없이 조용히 끊긴다.
 *
 * 살아남는 건 연결이 아니라 **세션**이다: 암호 세션(Opener가 무상태라 전송에 독립)과
 * 가상 디스플레이(DisplayManager가 지문 기준 60s 유예)는 전송이 바뀌어도 그대로다.
 * 그래서 할 일은 새 망에서 후보를 다시 모아 다시 뚫고 갈아타는 것뿐이다.
 *
 * **make-before-break.** `onAvailable`은 기본망이 새 망으로 *바뀌는 순간* 불리는데, 그때
 * 옛 망은 보통 아직 살아 있다(셀룰러는 Wi-Fi가 기본이 된 뒤에도 한동안 유지된다).
 * 그 겹치는 창 동안 옛 경로로 영상을 계속 흘리면서 새 망에서 펀칭을 끝내면 무손실로
 * 갈아탈 수 있다. 겹침이 없는 전환(수동 토글 등)이면 그냥 끊겼다 복구되는 경로가 된다.
 * 이 클래스는 그 판단을 하지 않는다 — "바뀌었다"만 알리고, 옛 경로가 아직 쓸 만한지는
 * 스트리밍 상태를 아는 MirrorController가 정한다.
 */
class NetworkWatcher(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** 콜백 전용 스레드 — 메인 루퍼를 쓰면 UI 정체가 감지를 지연시킨다. */
    private val thread = HandlerThread("peri-netwatch").apply { start() }
    private val handler = Handler(thread.looper)

    /**
     * 기본망이 바뀌었다(또는 같은 망에서 IP가 바뀌었다). `network`는 새 기본망 —
     * 후보 수집용 소켓을 여기에 bind해야 실제 경로와 후보가 일치한다.
     *
     * `reason`은 로그용. 콜백 스레드에서 불린다.
     */
    var onChanged: ((network: Network, reason: String) -> Unit)? = null

    /** 곧 사라질 망 + 남은 수명(ms). OS가 알려주는 겹침 창의 잔여 시간이다. */
    var onLosing: ((network: Network, maxMsToLive: Int) -> Unit)? = null

    /** 현재 기본망 — 소켓 bind에 쓴다. 아직 못 봤으면 시스템에 직접 묻는다. */
    val current: Network?
        get() = seenNetwork ?: runCatching { cm.activeNetwork }.getOrNull()

    /** 기본망의 전송 종류. */
    enum class Transport(val label: String) {
        NONE("없음"), WIFI("Wi-Fi"), CELLULAR("셀룰러"), ETHERNET("이더넷"), OTHER("기타")
    }

    /**
     * 지금 기본망의 전송 종류.
     *
     * 경로 우선순위와 Mac 표시의 근거다 — 셀룰러는 캐리어 CGNAT을 나갔다 와야 해서 지연도
     * 크고 symmetric NAT이라 펀칭조차 안 되는 경우가 흔하다. **가능하면 마지막에 쓴다.**
     * 그런데 앱은 여태 자기가 LTE인지 Wi-Fi인지 알 방법이 아예 없었다(기본망 객체만 봤다).
     */
    val transport: Transport
        get() = runCatching {
            val c = cm.getNetworkCapabilities(current) ?: return@runCatching Transport.NONE
            when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
                else -> Transport.OTHER
            }
        }.getOrDefault(Transport.NONE)

    val onCellular: Boolean get() = transport == Transport.CELLULAR

    /** 로그·진단용 한 단어. */
    val transportName: String get() = transport.label

    /**
     * 기본망이 셀룰러일 때 그 망의 IPv4 주소들 — **후보에서 뒤로 미룰 대상**이다.
     *
     * `hostAddresses()`는 인터페이스를 전부 훑어 Wi-Fi 주소와 셀룰러 주소를 같이 담는다.
     * 셀룰러 host 주소는 상대가 닿을 일이 거의 없는 CGNAT 사설 주소라(10.x), 앞에 실리면
     * 상대가 그걸 먼저 두드리며 시간을 버린다. 비면 미룰 게 없다는 뜻이다.
     */
    fun deprioritizedAddresses(): Set<String> {
        if (!onCellular) return emptySet()
        val n = current ?: return emptySet()
        return runCatching {
            cm.getLinkProperties(n)?.linkAddresses
                ?.mapNotNull { (it.address as? Inet4Address)?.hostAddress }
                ?.toSet()
        }.getOrNull() ?: emptySet()
    }

    /** 지금 붙어 있는 기본망. 소실되면 null. */
    @Volatile private var seenNetwork: Network? = null
    /**
     * 마지막으로 본 기본망 — **소실돼도 지우지 않는다.**
     *
     * `seenNetwork`만 보면 break-before-make(옛 망이 먼저 죽고 새 망이 나중에 뜨는 전환)를
     * 놓친다: `onLost`가 null로 지워 놓으면 뒤이은 `onAvailable`이 "최초 부착"처럼 보여
     * 재협상을 안 건다. 실기기 로그에서 Wi-Fi 소실 1초 뒤 LTE가 떴는데 전환으로 판정되지
     * 않았다 — 정작 겹침이 없어서 **재협상이 가장 절실한** 경우였다.
     */
    private var lastNetwork: Network? = null
    /** 기본망을 한 번이라도 본 적 있는가 — 진짜 최초 부착과 복귀를 구분한다. */
    private var everAttached = false
    /** 마지막으로 관측한 IPv4 주소들 — 같은 Network 객체라도 주소가 바뀔 수 있다. */
    private var seenAddrs: Set<String> = emptySet()
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 붙어 있다가 끊긴 뒤 다시 붙는 경우 — 같은 Network 객체로 돌아와도 전환이다.
            val wasDetached = everAttached && seenNetwork == null
            val previous = lastNetwork
            seenNetwork = network
            lastNetwork = network
            if (!everAttached) {
                everAttached = true
                Log.i(TAG, "기본망 최초 관측: $network")
                return   // 진짜 최초 부착은 핸드오버가 아니다
            }
            if (previous != network || wasDetached) {
                seenAddrs = emptySet()   // 새 망의 주소는 onLinkPropertiesChanged로 새로 받는다
                Log.i(TAG, "★ 기본망 전환 $previous → $network (겹침 ${if (wasDetached) "없음" else "있음"})")
                onChanged?.invoke(network, if (wasDetached) "기본망 재부착" else "기본망 전환")
            }
        }

        /**
         * 같은 Network 안에서 IP만 바뀌는 경우 — Wi-Fi AP 간 로밍, DHCP 갱신, IPv6 프리픽스 변경.
         * `onAvailable`은 안 불리므로 이걸 안 보면 조용히 죽는다.
         */
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            val addrs = lp.linkAddresses
                .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
                .toSet()
            if (network != seenNetwork) { seenNetwork = network; seenAddrs = addrs; return }
            val old = seenAddrs
            seenAddrs = addrs
            // 최초 관측(old가 빔)은 변화가 아니다. 주소가 사라지기만 한 것도 아직은 아니다
            // (곧 onLost가 오거나 새 주소가 붙는다) — 새 주소가 생긴 경우만 재협상한다.
            if (old.isNotEmpty() && addrs.isNotEmpty() && old != addrs) {
                Log.i(TAG, "★ 같은 망에서 주소 변경 $old → $addrs")
                onChanged?.invoke(network, "주소 변경")
            }
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            Log.i(TAG, "기본망 소멸 예고 $network (${maxMsToLive}ms 남음)")
            this@NetworkWatcher.onLosing?.invoke(network, maxMsToLive)
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "기본망 소실 $network")
            // `lastNetwork`는 일부러 남긴다 — 다음 onAvailable을 '최초 부착'으로 오인하지 않으려면
            // "예전에 뭔가 있었다"는 사실이 살아 있어야 한다.
            if (network == seenNetwork) { seenNetwork = null; seenAddrs = emptySet() }
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        // 등록 실패(권한 누락 등)가 앱을 죽여선 안 된다 — 감지가 없으면 예전처럼 재접속으로 복구된다.
        runCatching { cm.registerDefaultNetworkCallback(callback, handler) }
            .onSuccess { started = true; Log.i(TAG, "기본망 감시 시작") }
            .onFailure { Log.w(TAG, "기본망 감시 등록 실패: ${it.message}") }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        started = false
        seenNetwork = null
        lastNetwork = null
        everAttached = false
        seenAddrs = emptySet()
    }
}
