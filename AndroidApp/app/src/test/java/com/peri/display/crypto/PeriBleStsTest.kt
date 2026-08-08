package com.peri.display.crypto

import com.peri.display.ble.PeriPageToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BLE 재연결(STS) 상호운용 — **기대값은 Mac 구현이 낸 것**이다.
 *
 * 이 셋(힌트·전사·K)이 어긋나면 증상이 전부 같다: "재연결이 안 된다". 어느 단계에서 갈렸는지는
 * 로그로 안 보인다 — 서명 검증 실패 하나로 뭉쳐 나온다. 그래서 단계마다 벡터를 박아 둔다.
 *
 * 벡터 생성 (실제 Mac 코드를 그대로 컴파일해서 뽑았다):
 * ```
 * swiftc -O main.swift "MacApp/.../Crypto/PeriBleSts.swift" \
 *   "MacApp/.../BLE/PeriPageToken.swift" "MacApp/.../Crypto/PeriCrypto.swift"
 * ```
 */
class PeriBleStsTest {

    private val linkKey = ByteArray(32) { it.toByte() }
    private val macPub = ByteArray(32) { (0xA0 + it).toByte() }
    private val nonce = ByteArray(32) { (0xC0 + it).toByte() }
    private val epoch = 17_540_000L

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    /** 그 창이 '지금'인 것처럼 보이게 하는 시각. */
    private fun at(e: Long) = e * PeriPageToken.EPOCH_MS

    private fun mac(pub: ByteArray = macPub) = TrustedPeer(pub, "Mac", 0, 0)

    private fun keys(vararg e: Pair<ByteArray, ByteArray>): (TrustedPeer) -> ByteArray? =
        { p -> e.firstOrNull { it.first.contentEquals(p.pub) }?.second }

    @Test
    fun `힌트가 Mac 구현과 같은 바이트를 낸다`() {
        assertEquals(
            "9EC1D8CF062E7C079A41CA69ECA07532FC4DED2319D82B4AEE73DF67B95D7BF4",
            hex(PeriBleSts.hint(linkKey, nonce, epoch)),
        )
    }

    /**
     * ★ 힌트는 **자르지 않는다.**
     *
     * 8바이트로 자르면 충돌이 가능해지고, 충돌은 "힌트가 A를 짚었는데 서명은 B"라는 상태를
     * 만들어 정상 재연결을 죽인다. 호출 토큰이 8바이트인 건 광고 예산 때문이지 그게 충분해서가
     * 아니고, GATT에는 그 예산이 없다.
     */
    @Test
    fun `힌트는 HMAC 전체다`() {
        assertEquals(32, PeriBleSts.HINT_BYTES)
        assertEquals(PeriBleSts.HINT_BYTES, PeriBleSts.hint(linkKey, nonce, epoch).size)
    }

    /** 잘린 힌트는 안 받는다 — 짧은 값을 관용하면 자른 것과 같아진다. */
    @Test
    fun `잘린 힌트는 거부한다`() {
        val full = PeriBleSts.hint(linkKey, nonce, epoch)
        assertNull(
            PeriBleSts.resolveHint(
                full.copyOfRange(0, 8), nonce, listOf(mac()), keys(macPub to linkKey), at(epoch),
            ),
        )
    }

    /**
     * ★ **호출 토큰과 유도키가 달라야 한다.**
     *
     * 호출 토큰은 방송이라 지나가는 누구나 8바이트 표본을 모을 수 있다. 같은 `dk`를 HELLO
     * 힌트가 재사용하면 두 값이 한 열쇠 아래 묶이고, 그때 안전을 지탱하는 건 창 길이뿐이다.
     */
    @Test
    fun `힌트 유도키가 호출 토큰과 다르다`() {
        assertFalse(
            hex(PeriBleSts.hintKey(linkKey, nonce)) ==
                hex(PeriPageToken.deriveKey(linkKey, nonce)),
        )
        // 응답 토큰과도 달라야 한다 — 셋 다 같은 (링크 키, nonce)에서 나온다.
        assertFalse(
            hex(PeriBleSts.hintKey(linkKey, nonce)) ==
                hex(PeriPageToken.responseKey(linkKey, nonce)),
        )
    }

    @Test
    fun `힌트가 아는 Mac을 짚는다`() {
        val hint = PeriBleSts.hint(linkKey, nonce, epoch)
        val who = PeriBleSts.resolveHint(hint, nonce, listOf(mac()), keys(macPub to linkKey), at(epoch))
        assertNotNull("Mac이 만든 힌트를 못 알아봤다", who)
        assertEquals("Mac", who!!.name)
    }

    /** 링크 키가 다르면 아무것도 안 짚는다 — **PAKE로 물러서지 않고 라운드가 죽는다.** */
    @Test
    fun `링크 키가 다르면 안 짚는다`() {
        val hint = PeriBleSts.hint(linkKey, nonce, epoch)
        val wrong = keys(macPub to ByteArray(32) { (0x77 + it).toByte() })
        assertNull(PeriBleSts.resolveHint(hint, nonce, listOf(mac()), wrong, at(epoch)))
    }

    /** 링크 키가 없는 Mac은 건너뛴다(케이블로만 만난 사이). */
    @Test
    fun `링크 키가 없는 Mac은 건너뛴다`() {
        val hint = PeriBleSts.hint(linkKey, nonce, epoch)
        assertNull(PeriBleSts.resolveHint(hint, nonce, listOf(mac()), { null }, at(epoch)))
    }

    @Test
    fun `창을 벗어난 힌트는 안 통한다`() {
        val hint = PeriBleSts.hint(linkKey, nonce, epoch)
        val k = keys(macPub to linkKey)
        for (skew in listOf(-1L, 0L, 1L)) {
            assertNotNull("창 $skew", PeriBleSts.resolveHint(hint, nonce, listOf(mac()), k, at(epoch + skew)))
        }
        for (skew in listOf(-2L, 2L, 500L)) {
            assertNull("창 $skew 재생이 통과했다",
                       PeriBleSts.resolveHint(hint, nonce, listOf(mac()), k, at(epoch + skew)))
        }
    }

    @Test
    fun `전사가 Mac 구현과 같다`() {
        val hello = byteArrayOf(2) + "HELLO-BYTES".toByteArray()
        val th = PeriBleSts.transcript(
            hello, "STS1-BYTES".toByteArray(), "CORE-BYTES".toByteArray(),
        )
        assertEquals("744BFC4B59966CE5C704DD42FDCDFFE7B5C8D04C21BD73F4C58C846D66D00C93", hex(th))
    }

    @Test
    fun `K가 Mac 구현과 같다`() {
        val th = PeriBleSts.transcript(
            byteArrayOf(2) + "HELLO-BYTES".toByteArray(),
            "STS1-BYTES".toByteArray(), "CORE-BYTES".toByteArray(),
        )
        val k = PeriBleSts.derive(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 }, th)
        assertEquals("EECB699F2EE1336FC04DDABED6E2DE0AB712D4AF3F89147620E5998F62D191C3", hex(k.auth))
    }

    /**
     * HELLO 한 바이트만 달라도 전사가 갈린다 — **모드 바이트가 서명에 덮인다**는 뜻이다.
     *
     * 중간자가 mode를 2→1로 바꿔 재연결을 PIN 라운드로 끌어내리려 하면 양쪽 전사가 갈라져
     * 서명 검증이 그 자리에서 죽는다.
     */
    @Test
    fun `모드 바이트가 전사에 덮인다`() {
        val a = PeriBleSts.transcript(byteArrayOf(2) + "X".toByteArray(), byteArrayOf(1), byteArrayOf(2))
        val b = PeriBleSts.transcript(byteArrayOf(1) + "X".toByteArray(), byteArrayOf(1), byteArrayOf(2))
        assertFalse(hex(a) == hex(b))
    }

    /** 필드 경계가 모호하지 않다 — 길이프리픽스가 실제로 일을 한다. */
    @Test
    fun `필드를 옮겨도 전사가 달라진다`() {
        val a = PeriBleSts.transcript("AB".toByteArray(), "C".toByteArray(), byteArrayOf())
        val b = PeriBleSts.transcript("A".toByteArray(), "BC".toByteArray(), byteArrayOf())
        assertFalse(hex(a) == hex(b))
    }
}
