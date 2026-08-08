package com.peri.display.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 링크 키 상호운용 — **기대값은 Mac 구현이 낸 것**이다.
 *
 * 이 32바이트는 페어링이 끝난 뒤 양쪽 저장소에 각자 저장되고, 그 뒤로는 선을 타지 않는다.
 * 즉 **어긋나도 그 자리에서는 아무 일도 일어나지 않는다** — 다음번 재연결에서 "인증이 안
 * 된다"로만 나타난다. 세션 키처럼 페어링 도중 바로 들키는 값이 아니라서, 두 구현이 같은
 * 바이트를 낸다는 걸 여기서 못 박아 둔다.
 *
 * 벡터 생성 (실제 Mac 코드를 그대로 컴파일해서 뽑았다):
 * ```
 * swiftc -O shim.swift main.swift \
 *   "MacApp/.../Crypto/PeriBleHandshake.swift" "MacApp/.../Crypto/PeriCrypto.swift"
 * ```
 * shim.swift는 libsodium을 물고 있는 PeriSpake.swift 대신 같은 필드의 `SpakeKeys`만 준다.
 */
class PeriBleHandshakeTest {

    private val kemSecret = ByteArray(32) { it.toByte() }
    private val clientSk = ByteArray(32) { (0x40 + it).toByte() }
    private val serverSk = ByteArray(32) { (0x80 + it).toByte() }
    private val macNonce = ByteArray(32) { (0xC0 + it).toByte() }
    private val deviceNonce = ByteArray(32) { 0x11 }

    private fun keys() = SpakeKeys(clientSk, serverSk)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    @Test
    fun `링크 키가 Mac 구현과 같은 바이트를 낸다`() {
        assertEquals(
            "30CFEB6891FDBCC95BF3D79D7777F2114D122C82658515209767F6D71B552239",
            hex(PeriBleHandshake.linkKey(kemSecret, keys(), macNonce, deviceNonce)),
        )
    }

    /** 빈 입력도 같아야 한다 — HKDF의 salt/ikm 빈 처리는 구현마다 갈리기 쉬운 지점이다. */
    @Test
    fun `빈 입력에서도 Mac과 같다`() {
        val empty = SpakeKeys(ByteArray(0), ByteArray(0))
        assertEquals(
            "3E08BE569917BCBE28E83190238ACC3233F4671163C053F6CE1B78CA8F854DA9",
            hex(PeriBleHandshake.linkKey(ByteArray(0), empty, ByteArray(0), ByteArray(0))),
        )
    }

    @Test
    fun `링크 키는 32바이트다`() {
        assertEquals(32, PeriBleHandshake.linkKey(kemSecret, keys(), macNonce, deviceNonce).size)
    }

    /**
     * 같은 라운드에서 나온 세션 키와 링크 키가 서로 다른 값이어야 한다.
     *
     * 이게 깨지면 info 라벨이 겹쳤다는 뜻이고, 그 순간 **이번 채널의 키를 본 사람이 다음
     * 재연결의 근거까지 손에 넣는다**. 같은 ikm을 두 용도로 쓰는 설계에서 유일하게 위험한
     * 지점이라 여기서 지킨다.
     */
    @Test
    fun `세션 키와 다른 값이다`() {
        val link = PeriBleHandshake.linkKey(kemSecret, keys(), macNonce, deviceNonce)
        val ikm = kemSecret + clientSk + serverSk
        val salt = macNonce + deviceNonce
        // 세션 키는 밖으로 안 나온다(그래야 맞다) — 같은 재료·같은 salt에 세션 라벨만 붙여
        // 여기서 다시 뽑아 비교한다.
        for (info in listOf("PeriBLE-v2/m2a", "PeriBLE-v2/a2m")) {
            assertFalse(info, hex(link) == hex(hkdf(ikm, salt, info)))
        }
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.US_ASCII)))
        return ByteArray(32).also { g.generateBytes(it, 0, 32) }
    }

    /** 재료가 한 바이트만 달라도 다른 키가 나온다(= 라운드마다 새 키). */
    @Test
    fun `논스가 다르면 다른 키가 나온다`() {
        val a = PeriBleHandshake.linkKey(kemSecret, keys(), macNonce, deviceNonce)
        val b = PeriBleHandshake.linkKey(
            kemSecret, keys(), macNonce, deviceNonce.copyOf().also { it[0] = 0x12 },
        )
        assertFalse(hex(a) == hex(b))
    }

    /** 결정적이다 — 양쪽이 각자 계산해 같은 값에 도달하는 게 이 키의 존재 이유다. */
    @Test
    fun `같은 재료면 항상 같은 키다`() {
        assertArrayEquals(
            PeriBleHandshake.linkKey(kemSecret, keys(), macNonce, deviceNonce),
            PeriBleHandshake.linkKey(kemSecret, keys(), macNonce, deviceNonce),
        )
    }
}
