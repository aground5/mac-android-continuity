package com.peri.display.crypto

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * 장기 신원 "인증서" — X.509 아님. Ed25519 공개키(=신원) + 표시이름 + 생성시각에
 * 자기서명(selfSig)을 붙인 자기기술 레코드. 신뢰 결정은 pub 핀(SSH 방식) — 다만 핀을
 * 만드는 건 사람이 확인하는 BLE 페어링뿐이다(TOFU 아님, TrustStore 주석 참조).
 * Mac PeriCert.swift 와 동일한 raw 바이너리 인코딩(기존 X25519/ML-KEM raw-interop 규율).
 *
 * 레이아웃: ["PCRT" 4B][ver 1B=1][created 8B BE][nameLen 2B BE][name UTF8][pub 32B][selfSig 64B]
 */
data class PeriCert(
    val pub: ByteArray,        // Ed25519 공개키 32B = 신원
    val name: String,
    val createdMillis: Long,
    val selfSig: ByteArray,    // 64B
) {
    /** 자기서명 검증 — name/created 위변조 방지 + 보유자가 키를 통제함을 보장. */
    fun verifySelfSignature(): Boolean {
        if (pub.size != PUB_LEN || selfSig.size != SIG_LEN) return false
        return try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(pub, 0))
            val region = signedRegion(pub, name, createdMillis)
            signer.update(region, 0, region.size)
            signer.verifySignature(selfSig)
        } catch (e: Exception) { false }
    }

    /** 짧은 지문 — 페어링 화면 표시·SAS 비교용. SHA256(pub) 앞 8바이트 hex 4그룹. */
    fun fingerprint(): String {
        val h = MessageDigest.getInstance("SHA-256").digest(pub).copyOf(8)
        return (0 until 8 step 2).joinToString("-") {
            "%02X%02X".format(h[it].toInt() and 0xFF, h[it + 1].toInt() and 0xFF)
        }
    }

    fun serialized(): ByteArray = signedRegion(pub, name, createdMillis) + selfSig

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeriCert) return false
        return pub.contentEquals(other.pub) && name == other.name &&
                createdMillis == other.createdMillis && selfSig.contentEquals(other.selfSig)
    }

    override fun hashCode(): Int {
        var r = pub.contentHashCode()
        r = 31 * r + name.hashCode()
        r = 31 * r + createdMillis.hashCode()
        r = 31 * r + selfSig.contentHashCode()
        return r
    }

    companion object {
        private val MAGIC = "PCRT".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        const val PUB_LEN = 32
        const val SIG_LEN = 64

        /** selfSig가 덮는 영역 (magic..pub). 서명/검증 양쪽이 같은 함수를 쓴다. */
        fun signedRegion(pub: ByteArray, name: String, createdMillis: Long): ByteArray {
            val nameB = name.toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream()
            out.write(MAGIC)
            out.write(VERSION)
            for (i in 7 downTo 0) out.write(((createdMillis ushr (8 * i)) and 0xFF).toInt())
            out.write((nameB.size ushr 8) and 0xFF); out.write(nameB.size and 0xFF)
            out.write(nameB)
            out.write(pub)
            return out.toByteArray()
        }

        fun parse(data: ByteArray): PeriCert? {
            var i = 0
            fun take(n: Int): ByteArray? {
                if (n < 0 || i + n > data.size) return null
                val r = data.copyOfRange(i, i + n); i += n; return r
            }
            val m = take(4) ?: return null
            if (!m.contentEquals(MAGIC)) return null
            val v = take(1) ?: return null
            if ((v[0].toInt() and 0xFF) != VERSION) return null
            val createdB = take(8) ?: return null
            var created = 0L
            for (b in createdB) created = (created shl 8) or (b.toLong() and 0xFF)
            val nameLenB = take(2) ?: return null
            val nameLen = ((nameLenB[0].toInt() and 0xFF) shl 8) or (nameLenB[1].toInt() and 0xFF)
            val nameB = take(nameLen) ?: return null
            val pub = take(PUB_LEN) ?: return null
            val sig = take(SIG_LEN) ?: return null
            if (i != data.size) return null
            return PeriCert(pub, String(nameB, Charsets.UTF_8), created, sig)
        }
    }
}
