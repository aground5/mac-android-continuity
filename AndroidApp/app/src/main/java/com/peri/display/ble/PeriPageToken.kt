package com.peri.display.ble

import com.peri.display.crypto.TrustedPeer
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * **호출(paging) 토큰** — Mac이 이 기기 하나를 깨우려고 광고에 싣는 값.
 * Mac `BLE/PeriPageToken.swift`와 바이트 단위로 같아야 한다.
 *
 * ## 방향이 뒤집혔다
 *
 * 예전엔 우리가 상시 광고하고 Mac이 스캔했다. 지금은 반대다 — **콘센트에 꽂힌 쪽이 부르고**,
 * 우리는 `PendingIntent` 스캔만 걸어 둔다. 그 스캔은 블루투스 컨트롤러가 하드웨어에서
 * 필터링하므로 앱이 돌지 않고, 매치될 때만 OS가 깨운다. 상시 광고가 사라지는 만큼이 그대로
 * 배터리다.
 *
 * ## 왜 서비스 UUID 안에 있나
 *
 * macOS는 광고에 임의 바이트를 실을 수 없다 — SDK 헤더가 `LocalName`과 `ServiceUUIDs`만
 * 지원한다고 못 박아 놨다. 제조사 데이터도 서비스 데이터도 안 된다. 남은 자리가 128비트
 * UUID 그 자체뿐이라 거기에 넣는다:
 *
 * ```
 * UUID = [4B "PERI"][4B nonce][8B OTP]
 *         └ ScanFilter 마스크가 이것만 매치 → 오프로드 유지
 * ```
 *
 * 마스크가 있어서 뒤 12바이트가 매번 바뀌어도 필터를 다시 등록할 필요가 없다. 이게 없었으면
 * 값이 바뀔 때마다 우리가 깨어나 필터를 갈아야 했고, 그러면 오프로드를 쓰는 의미가 없다.
 *
 * ## nonce와 epoch은 역할이 다르다 — 둘 다 필요하다
 *
 * **nonce(전송됨) = 불연결성.** 광고마다 새로 뽑아 같이 오므로 같은 상대끼리라도 매번 달라
 * 보인다. 읽어서 그대로 쓰니 시계 정밀도가 필요 없다.
 *
 * **epoch(전송 안 됨) = 신선도.** 이게 없으면 `otp`가 (링크 키, nonce)만으로
 * **결정적**이라 한 번 캡처한 광고를 언제든 다시 쏠 수 있다. 그게 왜 나쁘냐면 공격자가 키를
 * 하나도 몰라도 되기 때문이다 — 진짜 호출을 한 번 녹음해 뒀다가 나중에 재생하면 우리가
 * 깨어나 광고를 시작하는 게 그대로 보인다. **"그 폰이 지금 여기 있나"를 묻는 존재 확인
 * 오라클**이 되고, nonce로 만들어 둔 불연결성을 통째로 우회한다.
 *
 * epoch을 **소금이 아니라 HMAC 메시지에** 넣는 게 요점이다. 그러면 `dk`가 epoch과 무관해
 * 유도는 여전히 한 번이고, 앞뒤 창을 열어도 HMAC만 3 × (Mac 수)번이다.
 *
 * ## 무엇을 증명하나 — **부르는 쪽이 페어링된 상대라는 것**
 *
 * ikm이 링크 키(페어링이 남긴 공유 비밀)라 이 값은 그 키를 가진 쪽만 만들 수 있다. 예전엔
 * ikm이 내 공개키였고, 공개키는 정의상 비밀이 아니라 **한 번이라도 그 값을 본 사람이면
 * 누구나 이 기기를 깨울 수 있었다.**
 *
 * 그래도 **세션 인증자는 아니다.** 한 방향 방송이고 창 안에서는 재생된다. 이걸로 정해지는
 * 건 "부를 자격이 있는 쪽이 부른다"까지고, 상대가 누구인지는 그 뒤 STS에서만 확정된다.
 *
 * ## 재료
 *
 * ```
 * dk  = HKDF(ikm = link_key, salt = nonce, info = "PeriPage-v2")
 * otp = HMAC-SHA256(dk, epoch(8B BE))[0..8]
 * ```
 *
 * **소금(nonce)이 공격자 선택이어도 된다.** 예전엔 ikm이 공개값이라 이 점이 걸렸지만, 지금은
 * ikm이 비밀이다 — HKDF-Extract가 원래 상정하는 그림이고(RFC 5869), 소금은 공개여도 무방하다.
 *
 * **Mac 공개키는 어디에도 안 들어간다.** 링크 키가 이미 상대별이라, 어느 링크 키로 값이 맞았는가가
 * 곧 "누가 부르는가"다 — [resolve]가 돌려주는 상대가 그 답이다. 공개키를 메시지에 더 넣어 봐야
 * 이미 정해진 답을 한 번 더 적는 것이고, 그만큼 양쪽이 맞춰야 할 바이트만 는다.
 *
 * ## 대가: 링크 키가 없으면 못 깨운다
 *
 * 첫 페어링에는 링크 키가 없다 — 그래서 그 한 번은 여전히 우리가 광고하는 경로다. 케이블로만
 * 신뢰를 세운 Mac도 마찬가지다(PAKE를 거치지 않아 공유 비밀이 없다). [resolve]는 링크 키가
 * 없는 상대를 **조용히 건너뛴다.**
 */
object PeriPageToken {

    /** UUID 앞 4바이트 — 고정. [android.bluetooth.le.ScanFilter] 마스크가 이것만 본다. */
    val PREFIX = "PERI".toByteArray(Charsets.US_ASCII)

    const val NONCE_BYTES = 4

    /**
     * 호출 UUID에 실리는 OTP 길이. UUID 16바이트에서 접두 4 + nonce 4를 뺀 나머지가 통째로
     * 우리 것이라 여유가 있다.
     */
    const val OTP_BYTES = 8

    /**
     * **응답 광고**에 실리는 OTP 길이 — 여긴 여유가 없다.
     *
     * 길이를 여기서 고르지 않고 [PeriAdvertToken.RESPONSE_BYTES]에서 받아 온다. 이건 암호학적
     * 선택이 아니라 **광고 한 패킷에 몇 바이트가 남는가**의 문제이고, 그 산술이 사는 곳은
     * 와이어 포맷 쪽이기 때문이다.
     *
     * 40비트로 충분한 이유: 이 값이 정하는 건 **"Mac이 GATT를 붙어 볼 것인가"** 하나다.
     * 위조에 성공해도 얻는 건 소켓 하나 열었다 STS에서 튕기는 것이고, 창은 10초 ±1이며,
     * Mac은 자기 호출 하나(후보 3개)와만 대조한다.
     */
    const val RESPONSE_BYTES = PeriAdvertToken.RESPONSE_BYTES

    /** 신선도 창(ms). 재전송이 유효한 시간이 여기에 ±1 창을 더한 만큼으로 닫힌다. */
    const val EPOCH_MS = 10_000L

    private val INFO = "PeriPage-v2".toByteArray(Charsets.US_ASCII)
    private val RESP_INFO = "PeriPage-v2/resp".toByteArray(Charsets.US_ASCII)

    fun currentEpoch(nowMs: Long = System.currentTimeMillis()): Long =
        Math.floorDiv(nowMs, EPOCH_MS)

    /** (링크 키, nonce) → 유도키. 링크 키가 상대별이라 **후보마다** 한 번씩이다. */
    fun deriveKey(linkKey: ByteArray, nonce: ByteArray): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(linkKey, nonce, INFO))
        return ByteArray(32).also { g.generateBytes(it, 0, 32) }
    }

    /**
     * 응답용 유도키. 호출과 **같은 재료·다른 라벨**이라 서로를 계산하지 못한다.
     *
     * 응답을 따로 두는 이유: 호출 광고는 방송이라 지나가는 누구나 되받아칠 수 있다. 응답에
     * MAC이 없으면 Mac이 아무 응답에나 GATT를 붙어 시간을 버린다. 이 몇 바이트가 "네가 부른
     * 그 폰이 여기 있다"까지를 말한다 — 그 이상은 STS가 정한다.
     */
    fun responseKey(linkKey: ByteArray, nonce: ByteArray): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(linkKey, nonce, RESP_INFO))
        return ByteArray(32).also { g.generateBytes(it, 0, 32) }
    }

    /** 광고에 실어 되돌릴 [RESPONSE_BYTES]바이트. */
    fun response(
        linkKey: ByteArray, nonce: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): ByteArray = otp(responseKey(linkKey, nonce), currentEpoch(nowMs), RESPONSE_BYTES)

    /**
     * 유도키 + 창 → [bytes]바이트. **RFC 4226 동적 절단**을 쓴다.
     *
     * 잘라 낼 위치를 HMAC 자신이 정한다: 마지막 바이트의 하위 4비트가 시작 오프셋이다.
     * 늘 같은 자리(앞 N바이트)를 쓰면 그 자리에 있을지 모르는 편향이 매번 같은 자리에 그대로
     * 나온다 — 위치를 값에 따라 흩어 두면 그런 것이 한곳에 쌓이지 않는다.
     *
     * 오프셋은 0~15고 32바이트에서 최대 8을 떼므로 끝이 23 — 범위를 벗어날 수 없다.
     * RFC와 달리 첫 바이트의 최상위 비트는 **안 지운다.** 그건 정수로 바꿔 십진수를 뽑을 때
     * 부호 때문에 필요한 것이고, 우리는 바이트를 그대로 비교하므로 1비트를 버릴 이유가 없다.
     */
    fun otp(key: ByteArray, epoch: Long, bytes: Int = OTP_BYTES): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(ByteBuffer.allocate(8).putLong(epoch).array())
        val code = mac.doFinal()
        val offset = code[code.size - 1].toInt() and 0x0F
        return code.copyOfRange(offset, offset + bytes)
    }

    /** 접두만 걸린 스캔 필터용 — UUID와 마스크 쌍. 뒤 12바이트는 자유롭게 둔다. */
    fun filterUuid(): UUID = uuidOf(PREFIX + ByteArray(12))
    fun filterMask(): UUID = uuidOf(ByteArray(4) { 0xFF.toByte() } + ByteArray(12))

    /**
     * 광고된 UUID가 나를 부르는 것인가 — 맞으면 **누가** 부르는지 돌려준다.
     *
     * [linkKeyOf]는 그 Mac과 공유하는 링크 키를 준다. null이면 그 상대는 나를 부를 수 없으므로
     * (첫 페어링 전이거나 케이블로만 만난 사이) 건너뛴다.
     *
     * 후보마다 HKDF 1번 + HMAC 3번이다. 신뢰된 Mac이 한둘인 게 보통이라 깨어난 김에 도는
     * 계산치고 가볍고, **값은 하나도 밖으로 안 나간다.**
     *
     * 앞뒤 한 창씩 여는 이유는 시계 오차다. 창이 10초라 ±1이면 허용 오차가 ±10초인데, NTP가
     * 도는 기기 사이에서는 넉넉하고 재전송 창은 30초로 닫힌다.
     */
    fun resolve(
        advertised: UUID,
        macs: List<TrustedPeer>,
        linkKeyOf: (TrustedPeer) -> ByteArray?,
        nowMs: Long = System.currentTimeMillis(),
    ): Page? {
        val bytes = bytesOf(advertised)
        if (!bytes.copyOfRange(0, PREFIX.size).contentEquals(PREFIX)) return null
        val nonce = bytes.copyOfRange(PREFIX.size, PREFIX.size + NONCE_BYTES)
        val got = bytes.copyOfRange(PREFIX.size + NONCE_BYTES, 16)
        val e = currentEpoch(nowMs)
        val who = macs.firstOrNull { mac ->
            if (mac.pub.isEmpty()) return@firstOrNull false
            val link = linkKeyOf(mac) ?: return@firstOrNull false
            val dk = deriveKey(link, nonce)
            (e - 1..e + 1).any { otp(dk, it).contentEquals(got) }
        } ?: return null
        return Page(who, nonce)
    }

    /**
     * 해석된 호출. **nonce를 같이 낸다** — 응답 토큰이 이 값을 소금으로 쓰므로, 부른 그 호출에
     * 대한 응답인지가 여기서 묶인다.
     */
    class Page(val mac: TrustedPeer, val nonce: ByteArray)

    private fun uuidOf(b: ByteArray): UUID {
        val bb = ByteBuffer.wrap(b)
        return UUID(bb.long, bb.long)
    }

    private fun bytesOf(u: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
}
