package com.peri.display.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PQ-STS (Station-to-Station) — 세션 핸드셰이크의 상호 인증.
 * Mac `Crypto/PeriSts.swift`와 바이트 단위로 같아야 한다.
 *
 * 우리 = 개시자(Alice), Mac = 응답자(Bob).
 *
 * ```
 *  Pass 1  우리 → Mac : StsHello    { core }                    X_A, PK_A
 *  Pass 2  Mac → 우리 : StsResponse { core, E_K(cert_B ‖ S_B) } X_B, ct_B
 *  Pass 3  우리 → Mac : StsConfirm  { E_K(cert_A ‖ S_A) }
 * ```
 *
 * ## 옛 방식에서 무엇이 고쳐졌나
 *
 * ① **서명이 봉인된다.** 예전엔 서버 인증서와 서명이 ServerHello에 평문으로 실렸다. 같은
 *    망의 누구나 두 기기의 신원을 읽을 수 있었다. 봉인은 동시에 키 확인이기도 하다 —
 *    열렸다는 사실 자체가 상대가 같은 K에 도달했다는 증거다.
 *
 * ② **서명이 협상 내용까지 덮는다.** 이게 더 중요하다. 예전 전사 해시는 KEX 재료만 묶었고,
 *    ClientHello의 나머지 — 코덱 목록, 해상도, HDR 지원 범위, 디코더 한계, 프로토콜 버전 —
 *    는 평문이면서 **아무것도 덮지 않았다.** 경로상의 공격자가 supported_codecs에서 H265를
 *    지우거나 HDR을 지워도 서명은 그대로 검증됐다. 지금은 `core` 바이트 전체가 서명 대상이다.
 *
 * ## 왜 nonce 필드가 없나
 *
 * 임시 키쌍이 매 핸드셰이크 새로 생기므로 그 자체가 신선도다(HKDF salt를 비우는 이유도
 * 같다). 재전송은 `session_id`가 `core` 안에서 서명에 덮여 막힌다.
 */
object PeriSts {

    /** 와이어 버전. 옛 핸드셰이크(1)와 섞이지 않게 올렸다. */
    const val PROTOCOL_VERSION = 2

    private val TAG = "PeriSTS-v1".toByteArray(Charsets.US_ASCII)
    private val SERVER_CTX = "PeriSTS-v1/server".toByteArray(Charsets.US_ASCII)
    private val CLIENT_CTX = "PeriSTS-v1/client".toByteArray(Charsets.US_ASCII)

    /** 스펙의 K. 서명 봉인 전용이고, 세션 키는 아래 두 라벨로 따로 뽑는다. */
    private const val K_INFO = "PQ-STS Session Key"
    private const val INFO_SEND = "PQ-STS Session Key/a2m"   // 우리 송신
    private const val INFO_RECV = "PQ-STS Session Key/m2a"   // 우리 수신

    /** 봉인 방향. 같은 K를 쓰므로 **nonce가 달라야 한다** — 각 방향이 정확히 한 번 봉인한다. */
    enum class Role(val wire: Long) { SERVER(0), CLIENT(1) }

    // ── 전사 ────────────────────────────────────────────────────────────────

    private fun lp(o: ByteArrayOutputStream, d: ByteArray) {
        val n = d.size
        o.write((n ushr 24) and 0xFF); o.write((n ushr 16) and 0xFF)
        o.write((n ushr 8) and 0xFF); o.write(n and 0xFF)
        o.write(d)
    }

    /**
     * **선에 실제로 나간 바이트**를 그대로 묶는다.
     *
     * 중첩 메시지로 두고 받은 쪽이 재직렬화해 해시하면, protobuf 구현이 미지 필드나 필드
     * 순서를 다르게 내는 순간 서명이 어긋난다. 불투명 바이트로 실어야 양쪽이 같은 걸 본다.
     */
    fun transcript(helloCore: ByteArray, responseCore: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        lp(o, TAG); lp(o, helloCore); lp(o, responseCore)
        return MessageDigest.getInstance("SHA-256").digest(o.toByteArray())
    }

    fun serverSigningBytes(th: ByteArray): ByteArray = SERVER_CTX + th
    fun clientSigningBytes(th: ByteArray): ByteArray = CLIENT_CTX + th

    // ── 키 ──────────────────────────────────────────────────────────────────

    class Keys(
        /** 스펙의 K — 신원 봉인 전용. */
        val auth: ByteArray,
        /** 이후 제어·영상이 타는 방향별 세션. */
        val session: PeriCryptoSession,
    )

    /** `ikm = k_ECDH ‖ k_PQC`. salt는 비운다(임시키가 이미 신선도를 준다). */
    fun derive(ecdh: ByteArray, kem: ByteArray): Keys {
        val ikm = ecdh + kem
        return Keys(
            auth = hkdf(ikm, K_INFO),
            session = PeriCryptoSession.derive(
                ikm = ikm, salt = ByteArray(0),
                sendInfo = INFO_SEND, recvInfo = INFO_RECV,
            ),
        )
    }

    private fun hkdf(ikm: ByteArray, info: String): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, ByteArray(0), info.toByteArray(Charsets.US_ASCII)))
        return ByteArray(32).also { g.generateBytes(it, 0, 32) }
    }

    // ── 신원 봉인 ───────────────────────────────────────────────────────────

    /** [12B nonce][ciphertext‖tag]. nonce는 역할에서 결정되므로 재사용이 구조적으로 불가능하다. */
    fun sealAuth(key: ByteArray, plaintext: ByteArray, role: Role): ByteArray {
        val nonce = authNonce(role)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(plaintext)
    }

    fun openAuth(key: ByteArray, blob: ByteArray, role: Role): ByteArray? {
        if (blob.size <= 12 + 16) return null
        // nonce는 **우리가 계산한 값**을 쓴다. 실려 온 값을 믿으면 상대가 nonce를 골라
        // 같은 키에 재사용을 유도할 수 있다.
        val expected = authNonce(role)
        if (!blob.copyOfRange(0, 12).contentEquals(expected)) return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, expected))
            c.doFinal(blob, 12, blob.size - 12)
        }.getOrNull()
    }

    // ── 신원 조회 ───────────────────────────────────────────────────────────

    /**
     * 서명 하나로 **누구인지** 알아낸다 — 저장된 공개키를 하나씩 넣어 본다.
     *
     * 검증자는 서명 대상(전사)을 이미 알고 있으므로 공개키를 받을 이유가 없다. 페어링한
     * 기기 수만큼(1~3) Ed25519 검증이 도는데 한 번에 수십 µs라 값이 안 나간다.
     *
     * **집합 소속이 아니라 신원을 낸다.** 예전 코드는 인증서를 받아 "이게 신뢰 목록에 있나"만
     * 봤는데, 그러면 Mac을 둘 페어링해 뒀을 때 A에게 걸었는데 B가 응답해도 그냥 통과한다.
     * 여기서 누구인지가 나오므로 호출부가 의도와 대조할 수 있다.
     */
    fun resolveSigner(
        signature: ByteArray, signed: ByteArray, peers: List<TrustedPeer>,
    ): TrustedPeer? = peers.firstOrNull {
        it.pub.isNotEmpty() && PeriIdentity.verify(it.pub, signature, signed)
    }

    /** [채널 2 = 인증][0,0,0][8B BE 역할]. 제어(0)·영상(1) nonce 공간과 겹치지 않는다. */
    private fun authNonce(role: Role): ByteArray {
        val d = ByteArray(12)
        d[0] = 2
        for (i in 0 until 8) d[11 - i] = ((role.wire ushr (8 * i)) and 0xFF).toByte()
        return d
    }
}
