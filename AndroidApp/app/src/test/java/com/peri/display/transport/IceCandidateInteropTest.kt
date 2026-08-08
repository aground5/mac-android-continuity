package com.peri.display.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mac `IceCandidate.swift` ↔ Android `IceCandidate.kt` 바이트 호환 검증.
 *
 * 기대값은 **추측이 아니라 실제 Swift 구현을 돌려 얻은 출력**이다(2026-07-31):
 *   swiftc Stun.swift IceCandidate.swift main.swift && ./icetest
 * 후보 교환은 BLE 한 번뿐이라 형식이 어긋나면 조용히 연결만 안 된다 — 그래서 고정해 둔다.
 */
class IceCandidateInteropTest {

    /** Swift가 실제로 뱉은 151바이트. 토큰은 결정적 값 00,11,22,…,ff. */
    private val swiftWire =
        "v1 00112233445566778899aabbccddeeff\n" +
        "c tcp host 192.168.0.5 7437\n" +
        "c udp host 192.168.0.5 7437\n" +
        "c tcp srflx 203.0.113.9 41022\n" +
        "c udp srflx 203.0.113.9 51234"

    private val expectedToken = ByteArray(16) { (it * 17 and 0xFF).toByte() }

    @Test
    fun `Swift가 만든 바이트를 Kotlin이 파싱한다`() {
        val bytes = swiftWire.toByteArray(Charsets.UTF_8)
        assertEquals("Swift 출력과 길이가 같아야 함", 151, bytes.size)

        val b = CandidateBundle.parse(bytes)
        assertNotNull("파싱 실패", b)
        b!!

        assertArrayEquals("토큰 불일치", expectedToken, b.token)
        assertEquals(4, b.candidates.size)

        assertEquals(
            IceCandidate(CandidateTransport.TCP, CandidateKind.HOST, Ipv4Endpoint("192.168.0.5", 7437)),
            b.candidates[0],
        )
        assertEquals(
            IceCandidate(CandidateTransport.UDP, CandidateKind.HOST, Ipv4Endpoint("192.168.0.5", 7437)),
            b.candidates[1],
        )
        assertEquals(
            IceCandidate(CandidateTransport.TCP, CandidateKind.SRFLX, Ipv4Endpoint("203.0.113.9", 41022)),
            b.candidates[2],
        )
        assertEquals(
            IceCandidate(CandidateTransport.UDP, CandidateKind.SRFLX, Ipv4Endpoint("203.0.113.9", 51234)),
            b.candidates[3],
        )
    }

    @Test
    fun `Kotlin 직렬화가 Swift와 바이트 단위로 같다`() {
        val mine = CandidateBundle(
            expectedToken,
            listOf(
                IceCandidate(CandidateTransport.TCP, CandidateKind.HOST, Ipv4Endpoint("192.168.0.5", 7437)),
                IceCandidate(CandidateTransport.UDP, CandidateKind.HOST, Ipv4Endpoint("192.168.0.5", 7437)),
                IceCandidate(CandidateTransport.TCP, CandidateKind.SRFLX, Ipv4Endpoint("203.0.113.9", 41022)),
                IceCandidate(CandidateTransport.UDP, CandidateKind.SRFLX, Ipv4Endpoint("203.0.113.9", 51234)),
            ),
        )
        assertArrayEquals(
            "Kotlin 직렬화가 Swift 출력과 달라짐",
            swiftWire.toByteArray(Charsets.UTF_8),
            mine.serialized(),
        )
    }

    @Test
    fun `host 후보를 srflx보다 먼저 시도한다`() {
        val b = CandidateBundle.parse(swiftWire.toByteArray(Charsets.UTF_8))!!
        assertEquals(listOf(CandidateKind.HOST, CandidateKind.SRFLX),
                     b.ordered(CandidateTransport.TCP).map { it.kind })
        assertEquals(listOf(CandidateKind.HOST, CandidateKind.SRFLX),
                     b.ordered(CandidateTransport.UDP).map { it.kind })
    }

    @Test
    fun `모르는 줄과 필드는 무시하고 나머지를 살린다`() {
        // 앞으로 Mac이 필드를 추가해도 구버전 Android가 깨지지 않아야 한다.
        val future =
            "v1 00112233445566778899aabbccddeeff\n" +
            "c tcp host 192.168.0.5 7437 generation 0 ufrag xyz\n" +   // 필드 추가
            "x relay 1.2.3.4 3478\n" +                                 // 모르는 줄
            "c udp relay 1.2.3.4 3478\n" +                             // 모르는 kind
            "c udp srflx 203.0.113.9 51234"
        val b = CandidateBundle.parse(future.toByteArray(Charsets.UTF_8))
        assertNotNull(b)
        assertEquals(2, b!!.candidates.size)
        assertEquals(CandidateKind.HOST, b.candidates[0].kind)
        assertEquals(CandidateKind.SRFLX, b.candidates[1].kind)
    }

    @Test
    fun `토큰이 없거나 길이가 다르면 거부한다`() {
        assertNull(CandidateBundle.parse("c tcp host 1.2.3.4 7437".toByteArray()))
        assertNull(CandidateBundle.parse("v1 0011\nc tcp host 1.2.3.4 7437".toByteArray()))
    }

    @Test
    fun `왕복이 원본과 같다`() {
        val original = CandidateBundle(
            CandidateBundle.randomToken(),
            listOf(IceCandidate(CandidateTransport.UDP, CandidateKind.SRFLX, Ipv4Endpoint("8.8.4.4", 65535))),
        )
        val back = CandidateBundle.parse(original.serialized())!!
        assertArrayEquals(original.token, back.token)
        assertEquals(original.candidates, back.candidates)
    }

    // ── fast UDP 플래그 ────────────────────────────────────────────────────
    //
    // 세션 수립은 TCP만으로 끝나므로 보통은 udp 후보가 아예 없다. 그런데 "안 실었다"와
    // "STUN이 실패해 못 얻었다"가 선에서 똑같이 보여서, 상대가 자기 쪽 수집을 생략해도
    // 되는지 판단할 근거가 필요하다 — 그게 이 플래그다.

    @Test
    fun `플래그가 없으면 기본은 꺼짐이다`() {
        // 옛 Mac(플래그를 모르는 버전)이 보낸 묶음이 이 모양이다.
        val b = CandidateBundle.parse(swiftWire.toByteArray(Charsets.UTF_8))!!
        assertEquals(false, b.fastUdp)
    }

    @Test
    fun `fastudp 플래그를 읽는다`() {
        val wire =
            "v1 00112233445566778899aabbccddeeff\n" +
            "f fastudp\n" +
            "c tcp host 192.168.0.5 7437"
        assertEquals(true, CandidateBundle.parse(wire.toByteArray(Charsets.UTF_8))!!.fastUdp)
    }

    @Test
    fun `모르는 플래그 낱말은 지나간다`() {
        // 플래그가 앞으로 늘어도 구버전이 깨지면 안 된다 — 한 줄에 여러 낱말도 허용한다.
        val wire =
            "v1 00112233445566778899aabbccddeeff\n" +
            "f relay fastudp turbo\n" +
            "c tcp host 192.168.0.5 7437"
        val b = CandidateBundle.parse(wire.toByteArray(Charsets.UTF_8))!!
        assertEquals(true, b.fastUdp)
        assertEquals(1, b.candidates.size)
    }

    @Test
    fun `플래그도 왕복한다`() {
        val original = CandidateBundle(
            CandidateBundle.randomToken(),
            listOf(IceCandidate(CandidateTransport.UDP, CandidateKind.HOST, Ipv4Endpoint("10.0.0.2", 5000))),
            fastUdp = true,
        )
        assertEquals(true, CandidateBundle.parse(original.serialized())!!.fastUdp)
    }
}
