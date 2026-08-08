package com.peri.display.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 디스크에 두는 작은 비밀들의 금고.
 *
 * AndroidKeyStore 안에 사는 AES-256-GCM 마스터키(KEK)로 감싼 뒤 SharedPreferences에 넣는다.
 * KEK는 하드웨어 밖으로 **나오지 않으므로**, 파일만 긁어가도(백업·adb·루팅된 다른 앱) 열리지
 * 않는다. 나가는 건 언제나 `iv(12) ‖ ciphertext‖tag`의 base64뿐이다.
 *
 * [PeriIdentity]가 신원 seed에 쓰던 방식을 그대로 일반화했다. 다만 KEK는 따로 쓴다 —
 * 신원키와 상대별 링크 키는 수명도 파기 시점도 다르고, 한쪽을 지우려다 다른 쪽이 같이
 * 죽는 일이 없어야 한다.
 *
 * ⚠ KEK가 사라지면(앱 데이터 삭제, 기기 초기화, 일부 기기의 잠금방식 변경) 여기 든 것은
 * 전부 못 연다. 그래서 이 금고는 **복구 가능한 것만** 담아야 한다 — 링크 키는 다시 페어링
 * 하면 되므로 자격이 있다.
 */
internal class SecretVault(context: Context, prefsName: String) {

    private val prefs = context.applicationContext.getSharedPreferences(
        prefsName, Context.MODE_PRIVATE,
    )

    fun put(id: String, secret: ByteArray) {
        val blob = try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, kek())
            c.iv + c.doFinal(secret)
        } catch (e: Exception) {
            return   // 금고가 안 열리면 저장하지 않는다. 평문으로 물러서지는 않는다.
        }
        prefs.edit().putString(id, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(id: String): ByteArray? {
        val s = prefs.getString(id, null) ?: return null
        return try {
            val blob = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, kek(), GCMParameterSpec(128, blob, 0, IV_LEN))
            c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        } catch (e: Exception) {
            null   // KEK가 갈렸거나 blob이 상했다 — 없는 것과 같이 취급한다.
        }
    }

    fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val KS_ALIAS = "peri_secret_kek"
        const val KS_PROVIDER = "AndroidKeyStore"

        /** AndroidKeyStore에 상주하는 AES-256-GCM 마스터키. 없으면 생성. */
        fun kek(): SecretKey {
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
    }
}
