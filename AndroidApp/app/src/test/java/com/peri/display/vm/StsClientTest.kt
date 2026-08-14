package com.peri.display.vm

import com.google.protobuf.ByteString
import com.peri.display.crypto.PeriSts
import com.peri.display.crypto.TrustedPeer
import com.peri.display.proto.StsAuth
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * **"내가 걸려던 그 Mac인가."**
 *
 * 이 검사가 없거나 꺼져 있으면 STS는 "내 신뢰 목록의 누구인가"까지만 답한다. Mac을 둘
 * 페어링해 뒀다면 A에게 걸었는데 B가 응답해도 그냥 통과한다 — 주소가 재사용됐든, 경로상의
 * 누군가 돌렸든. 그리고 **그 실패는 조용하다**: 연결은 멀쩡히 성립한다, 틀린 상대와.
 *
 * 실제로 이 필드는 한동안 아무도 채우지 않아 검사가 통째로 무력했다. 그런 종류의 구멍은
 * 코드를 읽어서는 안 보이므로(문법은 멀쩡하다) 여기서 못박는다.
 *
 * [identifyServer]가 클래스 밖 순수 함수인 이유도 이것이다 — `StsClient`는 안드로이드
 * 키스토어와 파일 저장소에 매여 있어 JVM 테스트에서 만들 수 없다.
 */
class StsClientTest {

    private val rnd = SecureRandom()

    /** Mac 하나 — 장기 신원 키쌍. */
    private class Mac(val name: String) {
        val priv = Ed25519PrivateKeyParameters(ByteArray(32).also { SecureRandom().nextBytes(it) }, 0)
        val pub: ByteArray get() = priv.generatePublicKey().encoded

        fun sign(data: ByteArray): ByteArray {
            val s = Ed25519Signer()
            s.init(true, priv)
            s.update(data, 0, data.size)
            return s.generateSignature()
        }

        fun peer(): TrustedPeer = TrustedPeer(pub, name, 0L, 0L)
    }

    private val alice = Mac("앨리스의 MacBook")
    private val bob = Mac("밥의 MacBook")

    private val authKey = ByteArray(32).also { rnd.nextBytes(it) }
    private val signedBytes = ByteArray(64).also { rnd.nextBytes(it) }

    /** 그 Mac이 선에 올릴 Pass 2 봉인 — 실제 코드가 쓰는 그 함수로 만든다. */
    private fun sealedAuthFrom(mac: Mac): ByteArray {
        val auth = StsAuth.newBuilder()
            .setSignature(ByteString.copyFrom(mac.sign(signedBytes)))
            .build()
        return PeriSts.sealAuth(authKey, auth.toByteArray(), PeriSts.Role.SERVER)
    }

    private fun identify(responder: Mac, trusted: List<Mac>, expected: ByteArray?) =
        identifyServer(
            authKey = authKey,
            sealed = sealedAuthFrom(responder),
            signedBytes = signedBytes,
            trusted = trusted.map { it.peer() },
            cable = false,
            expected = expected,
        )

    // MARK: 세 갈래

    @Test
    fun `기대한 Mac이 응답하면 통과한다`() {
        val who = identify(alice, trusted = listOf(alice, bob), expected = alice.pub)
        assertTrue("Known이어야 한다: $who", who is ServerIdentity.Known)
        assertEquals(alice.name, (who as ServerIdentity.Known).peer.name)
    }

    @Test
    fun `다른 Mac이 응답하면 거부한다`() {
        // ★ 이 테스트가 이 파일의 존재 이유다. 밥도 **신뢰 목록에 있는 진짜 Mac**이라
        // 서명 검증은 통과한다 — 걸려던 상대가 아니라는 것만이 거부의 근거다.
        val who = identify(bob, trusted = listOf(alice, bob), expected = alice.pub)
        assertTrue("WrongPeer여야 한다: $who", who is ServerIdentity.WrongPeer)
        assertEquals("누가 응답했는지가 로그에 남아야 한다",
                     bob.name, (who as ServerIdentity.WrongPeer).got.name)
    }

    @Test
    fun `기대가 없으면 대조하지 않는다`() {
        // 케이블·주소 직접 입력처럼 의도가 없는 경로. 막는 게 아니라 대조할 것이 없는 것이다.
        val who = identify(bob, trusted = listOf(alice, bob), expected = null)
        assertTrue("Known이어야 한다: $who", who is ServerIdentity.Known)
        assertEquals(bob.name, (who as ServerIdentity.Known).peer.name)
    }

    // MARK: 신뢰가 먼저다 — 기대가 신뢰를 대신하지 못한다

    @Test
    fun `신뢰 목록에 없으면 기대와 맞아도 거부한다`() {
        // 기대키를 우회로 쓰면 안 된다. 기대는 **좁히는** 장치이지 신뢰를 만드는 장치가 아니다.
        val who = identify(bob, trusted = listOf(alice), expected = bob.pub)
        assertEquals(ServerIdentity.Unknown, who)
    }

    @Test
    fun `신뢰 목록이 비면 아무도 통과하지 못한다`() {
        assertEquals(ServerIdentity.Unknown, identify(alice, trusted = emptyList(), expected = null))
    }

    @Test
    fun `봉인이 다른 키로 되어 있으면 열지 못한다`() {
        val wrongKey = ByteArray(32).also { rnd.nextBytes(it) }
        val who = identifyServer(
            authKey = wrongKey,                  // 상대와 같은 K에 도달하지 못했다
            sealed = sealedAuthFrom(alice),
            signedBytes = signedBytes,
            trusted = listOf(alice.peer()),
            cable = false,
            expected = alice.pub,
        )
        assertEquals(ServerIdentity.Unknown, who)
    }

    @Test
    fun `전사가 다르면 서명이 맞지 않는다`() {
        // 봉인은 열리는데(같은 K) 서명이 **다른 바이트**를 덮고 있는 경우 — 재생 공격의 모양이다.
        val who = identifyServer(
            authKey = authKey,
            sealed = sealedAuthFrom(alice),
            signedBytes = ByteArray(64).also { rnd.nextBytes(it) },
            trusted = listOf(alice.peer()),
            cable = false,
            expected = alice.pub,
        )
        assertEquals(ServerIdentity.Unknown, who)
    }

    // MARK: 케이블 부트스트랩

    @Test
    fun `케이블이 아니면 모르는 Mac을 신뢰하지 않는다`() {
        // 인증서를 실어 보내도 무선에서는 소용없다(fail-closed). 케이블만이 채널을 인증한다.
        val cert = com.peri.display.crypto.PeriCert(
            alice.pub, alice.name, 0L,
            alice.sign(com.peri.display.crypto.PeriCert.signedRegion(alice.pub, alice.name, 0L)),
        )
        val auth = StsAuth.newBuilder()
            .setSignature(ByteString.copyFrom(alice.sign(signedBytes)))
            .setIdentityCert(ByteString.copyFrom(cert.serialized()))
            .build()
        val sealed = PeriSts.sealAuth(authKey, auth.toByteArray(), PeriSts.Role.SERVER)

        assertEquals(
            ServerIdentity.Unknown,
            identifyServer(authKey, sealed, signedBytes, emptyList(), cable = false, expected = null),
        )
        val onCable = identifyServer(
            authKey, sealed, signedBytes, emptyList(), cable = true, expected = null,
        )
        assertTrue("케이블이면 부트스트랩한다: $onCable", onCable is ServerIdentity.Bootstrap)
        assertEquals(alice.name, (onCable as ServerIdentity.Bootstrap).cert.name)
    }

    @Test
    fun `부트스트랩에도 기대 대조가 걸린다`() {
        // 케이블 경로에서는 기대가 null이라 실제로는 안 걸리지만, 갈래마다 조건을 따로 쓰면
        // 언젠가 한쪽만 남는다. 규칙이 하나라는 것을 여기서 못박는다.
        val cert = com.peri.display.crypto.PeriCert(
            bob.pub, bob.name, 0L,
            bob.sign(com.peri.display.crypto.PeriCert.signedRegion(bob.pub, bob.name, 0L)),
        )
        val auth = StsAuth.newBuilder()
            .setSignature(ByteString.copyFrom(bob.sign(signedBytes)))
            .setIdentityCert(ByteString.copyFrom(cert.serialized()))
            .build()
        val sealed = PeriSts.sealAuth(authKey, auth.toByteArray(), PeriSts.Role.SERVER)

        val who = identifyServer(
            authKey, sealed, signedBytes, emptyList(), cable = true, expected = alice.pub,
        )
        assertTrue("WrongPeer여야 한다: $who", who is ServerIdentity.WrongPeer)
    }
}
