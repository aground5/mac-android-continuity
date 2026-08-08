package com.peri.display.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
// ML-KEM은 표준화(FIPS 203)되면서 BouncyCastle에서 `pqc.crypto.mlkem` → 일반 crypto 패키지로
// 옮겨졌다(1.84에서 옛 위치는 deprecated). 클래스 이름과 시그니처는 그대로라 import만 바뀐다.
// 확인은 실제 jar에서 했다: `javap -cp bcprov-jdk18on-1.84.jar org.bouncycastle.crypto.kems.MLKEMExtractor`
//
// 인코딩 호환성: 우리가 선에 싣는 건 **공개키의 raw encoded**뿐이고 그 표현은 그대로다
// (같은 구현이 이동만 했다). 새 `MLKEMPrivateKeyParameters`에 seed/expanded 형식 선택이
// 생겼지만 그건 개인키 직렬화 얘기이고, 우리는 개인키를 저장하지도 보내지도 않는다.
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2E 암호화 (DESIGN.md §3). 하이브리드 X25519 + ML-KEM-768 → HKDF → AES-256-GCM.
 * Android = initiator (decapsulator). 방향별 키: 송신 a2m / 수신 m2a (Mac과 반대).
 *
 * 상호운용: BouncyCastle lightweight API의 raw 인코딩(getEncoded)이 CryptoKit의
 * raw 표현(FIPS 203 / RFC 7748)과 일치한다. JCE SPKI DER 경로를 쓰지 않는다.
 */
// 데이터그램(UDP) 채널. 마스터를 한 번 뽑아 두고, 세대마다 channel_id를 소금으로 갈라 쓴다.
private const val INFO_UDP_MASTER = "PeriUDP-v1/master"
private const val INFO_UDP_SEND = "PeriUDP-v1/a2m"   // Android → Mac (우리 송신)
private const val INFO_UDP_RECV = "PeriUDP-v1/m2a"   // Mac → Android (우리 수신)

/** 데이터그램 세대 id의 길이. 와이어(UDP HELLO 접두)와 키 유도 소금이 같은 값을 쓴다. */
const val DATAGRAM_CHANNEL_ID_BYTES = 16

class PeriHandshake {
    private val rng = SecureRandom()
    private val x25519Priv: X25519PrivateKeyParameters
    private val x25519Pub: X25519PublicKeyParameters
    private val mlkemPriv: MLKEMPrivateKeyParameters
    private val mlkemPub: MLKEMPublicKeyParameters

    val sessionId: ByteArray = ByteArray(16).also { rng.nextBytes(it) }

    init {
        val xg = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(rng)) }
        val xkp = xg.generateKeyPair()
        x25519Pub = xkp.public as X25519PublicKeyParameters
        x25519Priv = xkp.private as X25519PrivateKeyParameters

        val mg = MLKEMKeyPairGenerator().apply {
            init(MLKEMKeyGenerationParameters(rng, MLKEMParameters.ml_kem_768))
        }
        val mkp = mg.generateKeyPair()
        mlkemPub = mkp.public as MLKEMPublicKeyParameters
        mlkemPriv = mkp.private as MLKEMPrivateKeyParameters
    }

    val x25519PubBytes: ByteArray get() = x25519Pub.encoded
    val mlkemPubBytes: ByteArray get() = mlkemPub.encoded

    /**
     * STS용 — 세션을 만들지 않고 **원재료만** 낸다. 키 파생은 [PeriSts]가 한다.
     *
     * 왜 갈랐나: STS는 같은 재료에서 신원 봉인용 K와 방향별 세션 키를 함께 뽑는다. 파생을
     * 여기 두면 이 클래스가 두 프로토콜의 KDF 라벨을 다 알아야 하고, 그건 라벨이 어긋나도
     * 컴파일이 통과한다는 뜻이다.
     */
    fun rawSecrets(serverX25519Pub: ByteArray, mlkemCiphertext: ByteArray): Pair<ByteArray, ByteArray> {
        val agr = X25519Agreement().apply { init(x25519Priv) }
        val ssX = ByteArray(agr.agreementSize)
        agr.calculateAgreement(X25519PublicKeyParameters(serverX25519Pub, 0), ssX, 0)
        val ssKem = MLKEMExtractor(mlkemPriv).extractSecret(mlkemCiphertext)
        return ssX to ssKem
    }

}

/**
 * 데이터그램 채널 **한 세대**의 키. Mac `PeriDatagramKeys`와 대칭.
 *
 * 세션 키에서 `channel_id`를 소금으로 갈라 나온다. 두 가지를 동시에 얻는다:
 *
 *  · **0-RTT** — 채널을 연다는 통보(`UdpOpen`)만 오면 양쪽이 즉시 같은 키에 도달한다.
 *    UDP 위에서 키를 다시 합의할 필요가 없으니 첫 데이터그램부터 봉인돼 나간다.
 *  · **세대 격리** — 영상 nonce는 frame_id에서 유도되는데, 채널을 닫았다 다시 열면서
 *    프레임 번호가 되감기면 같은 (키, nonce)가 두 번 나온다. 세대마다 키가 바뀌면
 *    그 사고가 **구조적으로** 불가능해진다.
 */
class PeriDatagramKeys(sendKey: ByteArray, recvKey: ByteArray) {
    private val sendKeySpec = SecretKeySpec(sendKey, "AES")
    private val recvKeySpec = SecretKeySpec(recvKey, "AES")
    private val sendCounter = AtomicLong(0)

    /** 제어용(HELLO 등): (12B nonce, ciphertext||tag). */
    fun seal(plaintext: ByteArray): Pair<ByteArray, ByteArray> =
        gcmSeal(sendKeySpec, makeNonce(0, sendCounter.getAndIncrement()), plaintext)

    fun open(nonce: ByteArray, body: ByteArray): ByteArray =
        gcmOpen(recvKeySpec, nonce, body)

    /** 영상용: nonce를 frame_id로 유도(전송 안 함). */
    fun sealVideo(plaintext: ByteArray, frameId: Long): ByteArray =
        gcmSeal(sendKeySpec, makeNonce(1, frameId), plaintext).second

    fun openVideo(body: ByteArray, frameId: Long): ByteArray =
        gcmOpen(recvKeySpec, makeNonce(1, frameId), body)
}

/** AES-256-GCM 방향별 sealer/opener. nonce = 12B (마지막 8B big-endian 카운터). */
class PeriCryptoSession private constructor(
    sendKey: ByteArray,
    recvKey: ByteArray,
    /**
     * 데이터그램 세대 키의 뿌리. 세션 키와 **다른 라벨**로 뽑아 둔다 — 한쪽이 새도
     * 다른 쪽이 나오지 않게.
     */
    private val datagramMaster: ByteArray,
) {
    private val sendKeySpec = SecretKeySpec(sendKey, "AES")
    private val recvKeySpec = SecretKeySpec(recvKey, "AES")
    private val sendCounter = AtomicLong(0)

    companion object {
        /**
         * 같은 재료·같은 소금에서 방향별 세션 키와 데이터그램 마스터를 **한 번에** 뽑는다.
         *
         * 생성자를 막고 이 문 하나만 남긴 이유: 마스터를 호출부가 따로 챙기게 두면
         * 채널이 늘어날 때마다 한 곳이 빠뜨린다. 라벨을 인자로 받는 건 채널마다 도메인이
         * 달라야 하기 때문이다(LAN 세션 vs BLE 페어링).
         */
        fun derive(
            ikm: ByteArray, salt: ByteArray, sendInfo: String, recvInfo: String,
        ): PeriCryptoSession = PeriCryptoSession(
            sendKey = hkdf32(ikm, salt, sendInfo),
            recvKey = hkdf32(ikm, salt, recvInfo),
            datagramMaster = hkdf32(ikm, salt, INFO_UDP_MASTER),
        )
    }

    /** 이 세대의 데이터그램 키. [channelId]는 Mac이 세대마다 새로 뽑는 16바이트다. */
    fun datagram(channelId: ByteArray): PeriDatagramKeys = PeriDatagramKeys(
        sendKey = hkdf32(datagramMaster, channelId, INFO_UDP_SEND),
        recvKey = hkdf32(datagramMaster, channelId, INFO_UDP_RECV),
    )

    /** 반환: (12B nonce, ciphertext||tag). */
    fun seal(plaintext: ByteArray): Pair<ByteArray, ByteArray> =
        gcmSeal(sendKeySpec, makeNonce(0, sendCounter.getAndIncrement()), plaintext)

    /**
     * 영상용 봉인: nonce를 frame_id로 유도한다(전송 안 됨) — `openVideo`의 거울상.
     *
     * 키는 이미 방향별이라(`sendKey` = a2m) 확장 경로가 쓰는 수신키와 겹치지 않고,
     * nonce 공간도 채널 바이트로 갈려 있어 제어 카운터와도 안 겹친다.
     *
     * ⚠ **frame_id를 재사용하면 안 된다.** 같은 키에 같은 nonce로 두 번 봉인하는 순간
     * GCM은 무너진다. 호출부는 단조 증가하는 값만 넘긴다.
     *
     * UDP로 나가는 영상은 이 키가 아니라 [PeriDatagramKeys]를 쓴다 — 세대마다 키가 갈리므로
     * 채널을 다시 열어 frame_id가 되감겨도 안전하다. 여기 남은 건 AOA(신뢰 파이프)뿐이다.
     */
    fun sealVideo(plaintext: ByteArray, frameId: Long): ByteArray =
        gcmSeal(sendKeySpec, makeNonce(1, frameId), plaintext).second

    /** 제어용: 전송된 nonce 사용. body = ciphertext||tag(16). */
    fun open(nonce: ByteArray, body: ByteArray): ByteArray = gcmOpen(recvKeySpec, nonce, body)

    /** 영상용: nonce를 frame_id로 유도 (전송 안 됨). */
    fun openVideo(body: ByteArray, frameId: Long): ByteArray =
        gcmOpen(recvKeySpec, makeNonce(1, frameId), body)
}

// ── 두 키 묶음이 공유하는 원시 연산 ────────────────────────────────────────────
//
// 세션 키와 데이터그램 세대 키는 **재료만 다르고 하는 일은 같다.** 각자 Cipher 호출을
// 복사해 두면 언젠가 한쪽만 고쳐진다(nonce 레이아웃이 그런 종류의 값이다).

// nonce = [채널(1B)][000][카운터/frame_id (8B big-endian)]. 제어=0, 영상=1.
private fun makeNonce(channel: Int, value: Long): ByteArray {
    val d = ByteArray(12)
    d[0] = channel.toByte()
    for (i in 0 until 8) d[11 - i] = ((value ushr (8 * i)) and 0xFF).toByte()
    return d
}

private fun gcmSeal(
    key: SecretKeySpec, nonce: ByteArray, plaintext: ByteArray,
): Pair<ByteArray, ByteArray> {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
    return nonce to c.doFinal(plaintext)
}

private fun gcmOpen(key: SecretKeySpec, nonce: ByteArray, body: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
    return c.doFinal(body)
}

private fun hkdf32(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
    val g = HKDFBytesGenerator(SHA256Digest())
    g.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.US_ASCII)))
    return ByteArray(32).also { g.generateBytes(it, 0, 32) }
}
