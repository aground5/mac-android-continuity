package com.peri.display.crypto

import com.peri.display.ble.PeriPageToken
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * **BLE 재연결** — 이미 페어링한 Mac과 다시 만날 때. PIN을 묻지 않는다.
 * Mac `Crypto/PeriBleSts.swift`와 바이트 단위로 같아야 한다.
 *
 * ## 언제 이 경로인가 — 우리는 아무것도 기억하지 않는다
 *
 * Mac이 HELLO에 **호출 토큰과 똑같은 TOTP**를 싣는다. 우리는 신뢰 목록을 훑어 각 Mac의 링크
 * 키로 같은 값을 만들어 보고, 맞는 게 있으면 그 상대와 STS로 간다. 없으면 PAKE다.
 *
 * 요점은 **"아까 호출당했다" 같은 상태를 들고 있지 않다**는 것이다. 그런 깃발을 두면 깃발이
 * 선 짧은 창 안에 아무나 끼어들어 그걸 소비할 수 있다. 여기서는 매 연결이 스스로 자격을
 * 증명한다 — 기억할 게 없으니 훔칠 것도 없다.
 *
 * 힌트는 **추측**이지 인증이 아니다. 창(±1) 안에서 재생되므로 남의 HELLO를 녹음해 다시 쏘면
 * 우리가 STS를 시작하기는 한다. 거기서 끝이다 — 신원은 서명으로만 정해진다.
 *
 * ## 흐름 (Mac이 개시자, 하지만 STS 역할은 LAN과 같다)
 *
 * ```
 *  1. Mac → HELLO : [mode=2][nonce_M][totp]
 *  2. 우리 → STS1 : X_A ‖ PK_A                      (우리 = client)
 *  3. Mac → STS2  : X_B ‖ ct ‖ E_K(S_mac)
 *  4. 우리 → STS3 : E_K(S_and)
 * ```
 *
 * Mac은 자기가 부른 그 상대의 공개키로만 검증한다(누굴 불렀는지 안다). 우리는 TOTP가 짚어 준
 * 그 Mac의 공개키로만 검증한다. **양쪽의 짐작이 서명으로 확정되는 지점**이 여기다.
 *
 * ## 전사
 *
 * ```
 * th = SHA256( LP(tag) ‖ LP(hello) ‖ LP(sts1) ‖ LP(sts2core) )
 * ```
 *
 * `hello`가 통째로 들어가므로 **TOTP도 서명에 덮인다.** 중간자가 mode 바이트를 갈아끼우거나
 * 힌트를 건드리면 양쪽 전사가 갈라져 서명 검증이 그 자리에서 죽는다.
 *
 * ## LAN STS와 무엇이 다른가
 *
 * 실어 나르는 게 protobuf가 아니라 BLE 프레임이다. LAN 쪽 `core`에는 코덱·해상도·HDR 협상이
 * 들어 있는데 여기서는 협상할 게 없다 — 이 채널은 시그널링이고, 실제 세션은 TCP에서 자기
 * STS를 다시 돈다. 컨텍스트 태그도 갈라 두어 한쪽 서명이 다른 쪽에 재생되지 않는다.
 */
object PeriBleSts {

    private val TAG = "PeriBLE-sts-v1".toByteArray(Charsets.US_ASCII)
    private val MAC_CTX = "PeriBLE-sts-v1/mac".toByteArray(Charsets.US_ASCII)
    private val ANDROID_CTX = "PeriBLE-sts-v1/android".toByteArray(Charsets.US_ASCII)
    private const val AUTH_INFO = "PeriBLE-sts-v1/auth"
    private val HINT_INFO = "PeriBLE-sts-v1/hint".toByteArray(Charsets.US_ASCII)

    /**
     * HELLO에 실리는 힌트 길이 — **HMAC 전체.** 자르지 않는다.
     *
     * 호출 토큰이 8바이트인 건 광고 예산(31B) 때문이지 그게 충분해서가 아니다. 여기는 GATT라
     * 그 예산이 없다. 자르면 충돌이 생기고, 충돌은 "힌트가 A를 짚었는데 서명은 B"라는 상태를
     * 만들어 라운드를 죽인다 — 24바이트 아끼자고 만들 문제가 아니다.
     */
    const val HINT_BYTES = 32

    /**
     * HELLO 힌트의 유도키. **호출 토큰과 라벨이 다르다.**
     *
     * 구성이 같다고 같은 `dk`를 쓰면 안 된다. 호출 토큰은 **방송**이라 지나가는 누구나 8바이트
     * 표본을 모을 수 있는데, 같은 키를 HELLO 힌트가 재사용하면 두 값이 한 열쇠 아래 묶인다.
     * 창이 짧아 지금은 실질 위험이 작지만, 그건 창 길이에 기댄 안전이지 구조적 안전이 아니다.
     */
    fun hintKey(linkKey: ByteArray, nonce: ByteArray): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(linkKey, nonce, HINT_INFO))
        return ByteArray(32).also { g.generateBytes(it, 0, 32) }
    }

    /** Mac이 HELLO에 실어 보내는 값. 우리는 대조에만 쓰지만 계산은 같은 함수여야 한다. */
    fun hint(linkKey: ByteArray, nonce: ByteArray, epoch: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hintKey(linkKey, nonce), "HmacSHA256"))
        mac.update(ByteBuffer.allocate(8).putLong(epoch).array())
        return mac.doFinal()
    }

    // ── 힌트 ────────────────────────────────────────────────────────────────

    /**
     * HELLO의 TOTP가 누구를 가리키는가 — 맞는 Mac이 있으면 그 상대를 돌려준다.
     *
     * [linkKeyOf]가 null을 주는 상대는 건너뛴다(첫 페어링 전이거나 케이블로만 만난 Mac).
     * 앞뒤 한 창씩 여는 건 호출 토큰과 같은 이유(시계 오차)다.
     */
    fun resolveHint(
        got: ByteArray,
        nonce: ByteArray,
        macs: List<TrustedPeer>,
        linkKeyOf: (TrustedPeer) -> ByteArray?,
        nowMs: Long = System.currentTimeMillis(),
    ): TrustedPeer? {
        if (got.size != HINT_BYTES) return null
        val e = PeriPageToken.currentEpoch(nowMs)
        return macs.firstOrNull { mac ->
            if (mac.pub.isEmpty()) return@firstOrNull false
            val link = linkKeyOf(mac) ?: return@firstOrNull false
            (e - 1..e + 1).any { hint(link, nonce, it).contentEquals(got) }
        }
    }

    // ── 전사 ────────────────────────────────────────────────────────────────

    private fun lp(o: ByteArrayOutputStream, d: ByteArray) {
        val n = d.size
        o.write((n ushr 24) and 0xFF); o.write((n ushr 16) and 0xFF)
        o.write((n ushr 8) and 0xFF); o.write(n and 0xFF)
        o.write(d)
    }

    /** 선에 실제로 나간 바이트를 그대로 묶는다. [responseCore]는 STS2에서 봉인 블롭 **앞**까지다. */
    fun transcript(hello: ByteArray, stsHello: ByteArray, responseCore: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        lp(o, TAG); lp(o, hello); lp(o, stsHello); lp(o, responseCore)
        return MessageDigest.getInstance("SHA-256").digest(o.toByteArray())
    }

    fun macSigningBytes(th: ByteArray): ByteArray = MAC_CTX + th
    fun androidSigningBytes(th: ByteArray): ByteArray = ANDROID_CTX + th

    // ── 키 ──────────────────────────────────────────────────────────────────

    class Keys(
        /** 서명 봉인 전용(스펙의 K). */
        val auth: ByteArray,
        /**
         * 봉인된 BLE 프레임(PARAMS·CANDIDATES·PUNCH)이 타는 세션. PAKE 경로와 같은 라벨이라
         * 그 뒤의 코드가 어느 경로로 왔는지 몰라도 된다.
         */
        val session: PeriCryptoSession,
    )

    /** 전사를 소금으로 쓴다 — 임시키가 신선도를 주는 데다, 이 라운드 밖에서는 같은 키가 안 난다. */
    fun derive(ecdh: ByteArray, kem: ByteArray, transcript: ByteArray): Keys {
        val ikm = ecdh + kem
        return Keys(
            auth = hkdf(ikm, transcript, AUTH_INFO),
            // 방향이 Mac과 반대다 — 우리 송신은 a2m, 수신은 m2a.
            session = PeriCryptoSession.derive(
                ikm = ikm, salt = transcript,
                sendInfo = "PeriBLE-v2/a2m", recvInfo = "PeriBLE-v2/m2a",
            ),
        )
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.US_ASCII)))
        return ByteArray(32).also { g.generateBytes(it, 0, 32) }
    }
}
