package com.peri.display.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BLE 페어링 핸드셰이크 v2 — **PIN 하나로 중간자를 막는다.**
 * Mac `Crypto/PeriBleHandshake.swift`와 바이트 단위로 같아야 한다.
 *
 * ## 흐름 (Mac이 개시자)
 *
 * ```
 *  1. Mac → HELLO : nonce_M ‖ ek_M            (ML-KEM-768 캡슐화 공개키)
 *  2. 우리 → OFFER: nonce_A ‖ ct ‖ public_data   ← 이때 화면에 4자리 PIN이 뜬다
 *     ── 사람이 그 PIN을 Mac에 옮겨 적는다 ──
 *  3. Mac → PAKE1 : response1
 *  4. 우리 → PAKE2: response2
 *  5. Mac → PAKE3 : response3
 *  → 양쪽이 같은 키에 도달한다. 여기서부터 채널이 봉인된다.
 *  6. 봉인된 채널 안에서 장기 신원 인증서를 주고받고 서로 핀한다.
 * ```
 *
 * ## 왜 이 순서인가
 *
 * **인증서가 평문 flight에 없다.** 예전엔 CLIENT_HELLO/SERVER_HELLO가 인증서를 그대로
 * 실어서, 근처에서 광고를 듣는 누구나 두 기기의 신원을 읽을 수 있었다. 신원 교환을 PAKE
 * 뒤로 미루면 그 유출이 통째로 사라지고, 교환된 인증서는 이미 중간자 없는 채널을 타므로
 * 따로 서명을 붙일 필요도 없다.
 *
 * ## KEM 방향
 *
 * Mac이 먼저 말하므로 Mac이 키쌍을 만들고, **우리가 캡슐화한다.** LAN 핸드셰이크와 정반대다
 * — 거기선 우리가 먼저 말하니 우리가 키쌍을 만든다.
 *
 * ## 두 비밀을 한 ikm에
 *
 * PIN만 깨도, 양자내성 키교환만 깨도 세션 키는 안 나온다. 그게 [session] 한 줄의 뜻이다.
 */
object PeriBleHandshake {

    /**
     * 사람이 옮겨 적는 자릿수.
     *
     * 4자리면 온라인 추측이 1/10⁴이다. PAKE라 오프라인 대입은 불가능하고, 틀린 추측은
     * 아무 정보도 남기지 않는다. **대신 기회가 한 번이어야 한다** — 재시도를 허용하는 순간
     * 그 1/10⁴이 반복 가능해진다. 그 규칙은 여기가 아니라 페어링 기계가 지킨다.
     */
    const val PIN_DIGITS = 4

    private val TAG = "PeriBLE-pair-v2".toByteArray(Charsets.US_ASCII)
    private val MAC_ROLE = "peri/mac".toByteArray(Charsets.US_ASCII)
    private val ANDROID_ROLE = "peri/android".toByteArray(Charsets.US_ASCII)

    private val rng = SecureRandom()

    /** 이번 라운드의 PIN. crypto-random이다 — 전사에서 파생하면 상대가 스스로 계산한다. */
    fun newPin(): String =
        (0 until PIN_DIGITS).joinToString("") { rng.nextInt(10).toString() }

    fun randomNonce32(): ByteArray = ByteArray(32).also { rng.nextBytes(it) }

    // ── 전사 ────────────────────────────────────────────────────────────────

    private fun lp(o: ByteArrayOutputStream, d: ByteArray) {
        val n = d.size
        o.write((n ushr 24) and 0xFF); o.write((n ushr 16) and 0xFF)
        o.write((n ushr 8) and 0xFF); o.write(n and 0xFF)
        o.write(d)
    }

    /**
     * 평문으로 오간 flight 1·2 **전부**를 덮는 해시.
     *
     * 이 값이 SPAKE2의 신원 문자열로 들어가므로, 중간자가 nonce든 KEM 재료든 한 바이트라도
     * 건드리면 양쪽 전사가 갈라져 PAKE 검증이 그 자리에서 죽는다. 예전 SAS가 KEX를 안 묶어
     * 생겼던 구멍이 여기서 닫힌다.
     */
    fun transcript(
        macNonce: ByteArray, kemPub: ByteArray,
        deviceNonce: ByteArray, ciphertext: ByteArray,
        publicData: ByteArray,
    ): ByteArray {
        val o = ByteArrayOutputStream()
        lp(o, TAG)
        lp(o, macNonce); lp(o, kemPub)
        lp(o, deviceNonce); lp(o, ciphertext)
        lp(o, publicData)
        return MessageDigest.getInstance("SHA-256").digest(o.toByteArray())
    }

    /** SPAKE2에 넘길 신원 문자열 — 역할 구분 + 전사 바인딩. */
    fun clientId(transcript: ByteArray): ByteArray = MAC_ROLE + transcript
    fun serverId(transcript: ByteArray): ByteArray = ANDROID_ROLE + transcript

    // ── KEM (우리 = 캡슐화하는 쪽) ──────────────────────────────────────────

    /** 결과: (Mac에게 보낼 암호문, 공유 비밀). */
    fun encapsulate(macKemPub: ByteArray): Pair<ByteArray, ByteArray> {
        val pub = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, macKemPub)
        val enc = MLKEMGenerator(rng).generateEncapsulated(pub)
        return enc.encapsulation to enc.secret
    }

    // ── 세션 ────────────────────────────────────────────────────────────────

    /**
     * 이번 라운드의 마스터 재료 — KEM 공유비밀 + PAKE 키 두 개.
     *
     * 순서가 양쪽에 같아야 한다. client=Mac, server=우리라 이 순서다.
     */
    private fun ikm(kemSecret: ByteArray, spake: SpakeKeys): ByteArray =
        kemSecret + spake.clientSk + spake.serverSk

    /** KEM 공유비밀 + PAKE 키 두 개 → 방향별 AEAD 세션. **이 라운드에서만 산다.** */
    fun session(
        kemSecret: ByteArray, spake: SpakeKeys,
        macNonce: ByteArray, deviceNonce: ByteArray,
    ): PeriCryptoSession {
        // 방향이 Mac과 반대다 — 우리 송신은 a2m, 수신은 m2a.
        return PeriCryptoSession.derive(
            ikm = ikm(kemSecret, spake), salt = macNonce + deviceNonce,
            sendInfo = "PeriBLE-v2/a2m", recvInfo = "PeriBLE-v2/m2a",
        )
    }

    /**
     * 이 상대와 **계속 쓸** 링크 키(32B). 라운드가 끝나도 살아남아 상대 신원 옆에 저장된다.
     *
     * 세션 키와 **같은 재료에서, 다른 info로** 뽑는다. HKDF라 둘은 서로를 유도하지 못한다 —
     * 이번 라운드 세션 키가 새어도 링크 키는 무사하고, 그 반대도 마찬가지다.
     *
     * 방향을 가르지 않는다. 이건 흘려보낼 채널이 아니라 **양쪽이 똑같이 들고 있는 하나의
     * 비밀**이고, 다음 만남에서 서로를 알아보는 근거가 된다. 사람이 PIN을 옮겨 적은 그
     * 한 번의 확인이 이 32바이트에 응결돼 남는 것이다.
     */
    fun linkKey(
        kemSecret: ByteArray, spake: SpakeKeys,
        macNonce: ByteArray, deviceNonce: ByteArray,
    ): ByteArray = hkdf(ikm(kemSecret, spake), macNonce + deviceNonce, "PeriBLE-v2/link")

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.US_ASCII)))
        return ByteArray(32).also { g.generateBytes(it, 0, 32) }
    }
}
