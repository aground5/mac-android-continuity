package com.peri.hardwareinformation.data.collector

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import com.peri.hardwareinformation.data.formatBytes
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Locale

/**
 * What the device does to protect itself: the boot chain that decided this OS was allowed to run,
 * the keystore hardware an app's keys land in, disk encryption, SELinux and the lock screen.
 *
 * Split out of `Software` because the two answer different questions — `Software` is "what is
 * installed", this is "what is trusted, and by what". `Security patch` and the SELinux row moved
 * here out of `System` for the same reason; no row appears in two categories.
 *
 * Everything here is readable without a runtime permission. Boot and encryption state come from
 * `ro.*` properties via [readSystemProperties]; keystore capabilities come from the system feature
 * list; the key backing comes from generating a throwaway key and asking where it went.
 */
class SecurityCollector(context: Context) : Collector {
    override val id = "security"
    override val title = "Security"
    override val icon = Icons.Filled.Shield

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    override suspend fun sections(): List<Section> {
        val properties = readSystemProperties()
        val strongBoxDeclared = has(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        return listOf(
            section("Boot integrity", fullWidth = true) {
                val bootState = properties["ro.boot.verifiedbootstate"]
                text("Verified boot", bootState?.let(::verifiedBootName), raw = bootState)
                flag("Bootloader locked", bootloaderLocked(properties))
                flag("Verified boot supported", has(PackageManager.FEATURE_VERIFIED_BOOT))
                val verity = properties["ro.boot.veritymode"]
                text("dm-verity", verity?.replaceFirstChar { it.uppercase() }, raw = verity)
                // Two different numbers with confusingly similar names, so the labels say which
                // property each came from rather than claiming to know how they relate.
                text("AVB version (bootloader)", properties["ro.boot.avb_version"])
                text("AVB version (vbmeta)", properties["ro.boot.vbmeta.avb_version"])
                val hashAlg = properties["ro.boot.vbmeta.hash_alg"]
                text("vbmeta hash", hashAlg?.let(::hashAlgorithmName), raw = hashAlg)
                val vbmetaSize = properties["ro.boot.vbmeta.size"]?.toLongOrNull()
                text("vbmeta size", vbmetaSize?.let(::formatBytes), raw = vbmetaSize?.toString())
                longText("vbmeta digest", properties["ro.boot.vbmeta.digest"])
            },
            section("Platform") {
                text("Android security patch", Build.VERSION.SECURITY_PATCH)
                // Reads on some devices and not others, and the difference is not whether the
                // vendor set it: properties carry SELinux labels, and this one is readable from an
                // app on a Galaxy S25 but not on a Galaxy Tab S10+, where `adb shell` sees a date
                // and `getprop` run as the app sees nothing. Missing here always means "not visible
                // to us", which is the only thing an app can honestly say about it.
                text("Vendor security patch", properties["ro.vendor.build.security_patch"])
                flag("CDD security model", has(PackageManager.FEATURE_SECURITY_MODEL_COMPATIBLE))
                // Enforcement is runtime state, and the only honest source for it is selinuxfs.
                // Most devices deny an ordinary app that read — including both test devices — so
                // this is often a failed read. The build-time `ro.build.selinux.enforce` would fill
                // the row on Samsung hardware, but a device built enforcing can still boot
                // permissive, so it would be a guess wearing a fact's clothes.
                val enforce = readSysfs(SELINUX_ENFORCE)
                text(
                    "SELinux",
                    enforce?.let { if (it == "1") "Enforcing" else "Permissive" },
                    raw = enforce,
                    missing = UnavailableReason.READ_FAILED,
                )
                // The app's own label, which it may always read. Its mere existence proves SELinux
                // is running, and the domain says which sandbox this process was placed in.
                val context = selinuxContext()
                text(
                    "SELinux domain",
                    context?.split(':')?.getOrNull(2),
                    raw = context,
                    missing = UnavailableReason.READ_FAILED,
                )
            },
            section("Encryption") {
                val state = properties["ro.crypto.state"]
                text("State", state?.replaceFirstChar { it.uppercase() }, raw = state)
                val type = properties["ro.crypto.type"]
                text("Type", type?.let(::encryptionTypeName), raw = type)
                flag("Metadata encryption", metadataEncrypted(properties))
                val names = properties["ro.crypto.volume.filenames_mode"]
                text("File names", names?.let(::cipherName), raw = names)
                val contents = properties["ro.crypto.volume.contents_mode"]
                text("File contents", contents?.let(::cipherName), raw = contents)
            },
            section("Keystore") {
                val backing = keyBacking(strongBox = false)
                text(
                    "Key backing",
                    backing?.let(::securityLevelName),
                    raw = backing?.toString(),
                    missing = UnavailableReason.READ_FAILED,
                )
                flag("Isolated execution environment", has(PackageManager.FEATURE_HARDWARE_KEYSTORE))
                val hardwareLevel = featureVersion(PackageManager.FEATURE_HARDWARE_KEYSTORE)
                text(
                    "Keystore level",
                    hardwareLevel?.let { keystoreLevelName(it, strongBox = false) },
                    raw = hardwareLevel?.toString(),
                )
                flag("StrongBox", strongBoxDeclared)
                if (strongBoxDeclared) {
                    val strongBoxLevel = featureVersion(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
                    text(
                        "StrongBox level",
                        strongBoxLevel?.let { keystoreLevelName(it, strongBox = true) },
                        raw = strongBoxLevel?.toString(),
                    )
                    // Only asked when the device claims StrongBox: a secure element is slow to
                    // generate a key on, and there is nothing to verify when there is no claim.
                    val strongBoxBacking = keyBacking(strongBox = true)
                    text(
                        "StrongBox key backing",
                        strongBoxBacking?.let(::securityLevelName),
                        raw = strongBoxBacking?.toString(),
                        missing = UnavailableReason.READ_FAILED,
                    )
                }
            },
            section("Keystore capabilities") {
                flag("App attest keys", has(PackageManager.FEATURE_KEYSTORE_APP_ATTEST_KEY))
                flag("Device ID attestation", has(PackageManager.FEATURE_DEVICE_ID_ATTESTATION))
                flag("Limited-use keys", has(PackageManager.FEATURE_KEYSTORE_LIMITED_USE_KEY))
                flag("Single-use keys", has(PackageManager.FEATURE_KEYSTORE_SINGLE_USE_KEY))
                flag("Identity credential", has(PackageManager.FEATURE_IDENTITY_CREDENTIAL_HARDWARE))
                flag(
                    "Identity direct access",
                    has(PackageManager.FEATURE_IDENTITY_CREDENTIAL_HARDWARE_DIRECT_ACCESS),
                )
            },
            section("Lock screen") {
                val keyguard = appContext.getSystemService(KeyguardManager::class.java)
                flag("Screen lock set", keyguard?.isDeviceSecure)
                flag("Secure lock screen", has(PackageManager.FEATURE_SECURE_LOCK_SCREEN))
                flag("Fingerprint", has(PackageManager.FEATURE_FINGERPRINT))
                flag("Face", has(PackageManager.FEATURE_FACE))
                flag("Iris", has(PackageManager.FEATURE_IRIS))
            },
            section("Developer options") {
                // Both are ordinary readable settings — only writing them needs a permission — and
                // both widen the device's attack surface, which is why they are reported here and
                // not filed under `System` with the rest of the platform configuration.
                flag("Enabled", globalFlag(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED))
                flag("USB debugging", globalFlag(Settings.Global.ADB_ENABLED))
            },
        )
    }

    private fun has(feature: String): Boolean = packageManager.hasSystemFeature(feature)

    private fun globalFlag(name: String): Boolean? =
        runCatching { Settings.Global.getInt(appContext.contentResolver, name) }.getOrNull()
            ?.let { it != 0 }

    /** A feature's version is 0 when it carries none, which is not a level of anything. */
    private fun featureVersion(name: String): Int? = packageManager.systemAvailableFeatures
        .firstOrNull { it.name == name }
        ?.version
        ?.takeIf { it > 0 }

    /**
     * Generates a throwaway signing key and reports where the platform put it — the one question
     * the feature list cannot answer, since a declared capability is a promise and this is the
     * outcome. The key is deleted either way, and it is generated without an attestation challenge
     * so nothing here can reach out to a remote provisioning server.
     */
    private fun keyBacking(strongBox: Boolean): Int? {
        val alias = if (strongBox) STRONGBOX_PROBE_ALIAS else PROBE_ALIAS
        return runCatching {
            deleteKey(alias)
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE,
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .apply { if (strongBox) setIsStrongBoxBacked(true) }
                    .build(),
            )
            securityLevelOf(generator.generateKeyPair().private)
        }.getOrNull().also { deleteKey(alias) }
    }

    /**
     * [KeyInfo.getSecurityLevel] arrived in API 31. Below that the only question the platform
     * answers is the yes/no one, and "yes" is documented to mean at least a trusted execution
     * environment — which is exactly [KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE].
     */
    private fun securityLevelOf(key: PrivateKey): Int? {
        val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java)
        @Suppress("DEPRECATION")
        return sinceApi(31) { info.securityLevel }
            ?: if (info.isInsideSecureHardware) {
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE
            } else {
                KeyProperties.SECURITY_LEVEL_SOFTWARE
            }
    }

    private fun deleteKey(alias: String) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
        }
    }

    /** NUL-terminated, unlike every other file under /proc. */
    private fun selinuxContext(): String? =
        readSysfs("/proc/self/attr/current")?.trim { it <= ' ' }?.takeIf(String::isNotEmpty)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val PROBE_ALIAS = "hwinfo_key_backing_probe"
        const val STRONGBOX_PROBE_ALIAS = "hwinfo_strongbox_probe"
        const val SELINUX_ENFORCE = "/sys/fs/selinux/enforce"

        /**
         * Per the verified boot documentation: green is locked with the vendor's own root of trust,
         * yellow is locked with a root of trust the user installed, orange is unlocked. Red is not
         * in that list because a device in it does not reach the point of publishing properties —
         * it is included for completeness of the enum, not because it is expected here.
         */
        fun verifiedBootName(state: String): String = when (state.lowercase(Locale.ROOT)) {
            "green" -> "Verified"
            "yellow" -> "Self-signed"
            "orange" -> "Unlocked"
            "red" -> "Verification failed"
            else -> state
        }

        /**
         * `ro.boot.flash.locked` is the direct answer; `ro.boot.vbmeta.device_state` carries the
         * same fact in words on devices that do not set the first one.
         */
        fun bootloaderLocked(properties: Map<String, String>): Boolean? =
            properties["ro.boot.flash.locked"]?.let { it == "1" }
                ?: properties["ro.boot.vbmeta.device_state"]?.let { it.equals("locked", true) }

        fun metadataEncrypted(properties: Map<String, String>): Boolean? =
            properties["ro.crypto.metadata.encrypted"]?.let { it == "1" }
                ?: properties["ro.crypto.metadata.enabled"]?.let { it == "true" }

        fun encryptionTypeName(type: String): String = when (type.lowercase(Locale.ROOT)) {
            "file" -> "File-based"
            "block" -> "Full-disk"
            "none" -> "None"
            else -> type
        }

        fun hashAlgorithmName(algorithm: String): String = when (algorithm.lowercase(Locale.ROOT)) {
            "sha1" -> "SHA-1"
            "sha256" -> "SHA-256"
            "sha512" -> "SHA-512"
            else -> algorithm.uppercase(Locale.ROOT)
        }

        /** `aes-256-xts` is written the way the standard writes it, not the way the kernel does. */
        fun cipherName(mode: String): String = mode.uppercase(Locale.ROOT)

        fun securityLevelName(level: Int): String = when (level) {
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "Trusted execution environment"
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "Secure hardware"
            else -> "Unknown"
        }

        /**
         * The keystore feature version is a capability ladder, not a product version — 300 is not
         * "KeyMint 3.0". Each rung names the newest thing that rung added, and every rung implies
         * the ones below it. The two ladders diverge: 200 adds Curve 25519 to an isolated execution
         * environment, but explicitly adds nothing to StrongBox, and only the isolated environment
         * has a documented 500.
         *
         * Taken verbatim from the javadoc on `FEATURE_HARDWARE_KEYSTORE` and
         * `FEATURE_STRONGBOX_KEYSTORE`; an undocumented rung stays a bare number rather than being
         * rounded down to the nearest one that is documented.
         */
        fun keystoreLevelName(version: Int, strongBox: Boolean): String = when {
            version == 500 && !strongBox -> "ML-DSA signatures"
            version == 400 -> "Module hash in attestation"
            version == 300 -> "Second IMEI in attestation"
            version == 200 && strongBox -> "No new capabilities"
            version == 200 -> "Curve 25519"
            version == 100 -> "ECDH and app attest keys"
            version == 41 -> "Device-unlocked key enforcement"
            version == 40 -> "Attestation and wrapped key import"
            else -> version.toString()
        }
    }
}
