package com.peri.display.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 이 기기의 장기 신원 Ed25519 키. seed(32B)를 한 번 생성해 AndroidKeyStore의 AES-GCM
 * 마스터키(KEK)로 래핑한 뒤 SharedPreferences에 보관하고 재사용한다. 임시 X25519 KEX
 * 키(PeriHandshake)와 별개 — 서명 전용 신원. Mac PeriIdentity.swift 와 대칭.
 */
class PeriIdentity private constructor(private val priv: Ed25519PrivateKeyParameters) {

    /** 내 신원 공개키 (32B). */
    val publicKey: ByteArray get() = priv.generatePublicKey().encoded

    /** 주어진 이름으로 내 자기서명 인증서를 만든다. */
    fun makeCert(name: String, createdMillis: Long = System.currentTimeMillis()): PeriCert {
        val region = PeriCert.signedRegion(publicKey, name, createdMillis)
        return PeriCert(publicKey, name, createdMillis, sign(region))
    }

    /** 임의 데이터(예: 핸드셰이크/페어링 전사)에 신원키로 서명. */
    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    companion object {
        private const val PREFS = "peri_identity"
        private const val KEY_SEED = "seed_wrapped"   // base64( iv(12) || ciphertext )
        private const val KS_ALIAS = "peri_identity_kek"
        private const val KS_PROVIDER = "AndroidKeyStore"

        @Volatile private var instance: PeriIdentity? = null

        fun get(context: Context): PeriIdentity =
            instance ?: synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }

        /** 상대 공개키로 서명 검증. */
        fun verify(pub: ByteArray, signature: ByteArray, data: ByteArray): Boolean = try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(pub, 0))
            signer.update(data, 0, data.size)
            signer.verifySignature(signature)
        } catch (e: Exception) { false }

        private fun load(ctx: Context): PeriIdentity {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seed = readSeed(prefs) ?: ByteArray(32).also {
                SecureRandom().nextBytes(it); writeSeed(prefs, it)
            }
            return PeriIdentity(Ed25519PrivateKeyParameters(seed, 0))
        }

        /** AndroidKeyStore에 상주하는 AES-256-GCM 마스터키. 없으면 생성. */
        private fun kek(): SecretKey {
            val ks = KeyStore.getInstance(KS_PROVIDER).apply { load(null) }
            (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS_PROVIDER)
            gen.init(
                KeyGenParameterSpec.Builder(
                    KS_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }

        private fun writeSeed(prefs: SharedPreferences, seed: ByteArray) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, kek())
            val blob = c.iv + c.doFinal(seed)   // iv(12) || ciphertext||tag
            prefs.edit().putString(KEY_SEED, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        }

        private fun readSeed(prefs: SharedPreferences): ByteArray? {
            val s = prefs.getString(KEY_SEED, null) ?: return null
            return try {
                val blob = Base64.decode(s, Base64.NO_WRAP)
                val iv = blob.copyOfRange(0, 12)
                val ct = blob.copyOfRange(12, blob.size)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, kek(), GCMParameterSpec(128, iv))
                c.doFinal(ct)
            } catch (e: Exception) { null }
        }
    }
}
