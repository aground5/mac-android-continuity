package com.peri.hardwareinformation.data.collector

import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import com.peri.hardwareinformation.data.formatDeqpLevel
import com.peri.hardwareinformation.data.formatGlEsVersion
import com.peri.hardwareinformation.data.formatGrouped
import com.peri.hardwareinformation.data.formatVulkanVersion
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.section
import java.util.Locale

/**
 * The raw `getSystemAvailableFeatures()` list — the closest thing Android has to a capability
 * manifest, and several hundred entries long.
 *
 * Presented grouped by namespace rather than as one flat wall: `android.hardware`, `android.software`
 * and each vendor prefix become their own collapsed section, so the list is navigable without a
 * search box. Labels drop the shared prefix, which is repeated in the section title anyway.
 */
class FeatureCollector(context: Context) : Collector {
    override val id = "features"
    override val title = "System features"
    override val icon = Icons.AutoMirrored.Filled.FactCheck

    private val appContext = context.applicationContext

    override suspend fun sections(): List<Section> {
        val features = appContext.packageManager.systemAvailableFeatures
            .filter { it.name != null }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        val groups = features.groupBy(::namespaceOf).toSortedMap()

        return buildList {
            add(
                section("Overview", fullWidth = true) {
                    text(
                        "Declared features",
                        formatGrouped(features.size.toLong()),
                        raw = features.size.toString(),
                    )
                    text("Namespaces", groups.size.toString())
                    text("Versioned", features.count { it.version > 0 }.toString())
                    // OpenGL ES arrives as a bare version code with no name at all. Formatted
                    // exactly as GpuCollector does it, so the same value reads the same in both
                    // categories under either mode.
                    val glEs = openGlEsVersion()
                    text(
                        "OpenGL ES version",
                        glEs?.let(::formatGlEsVersion),
                        raw = glEs?.let { String.format(Locale.US, "0x%X", it) },
                    )
                },
            )
            groups.forEach { (namespace, entries) ->
                // No count in the title: a collapsed section already shows its item count.
                add(
                    section(namespace, initiallyExpanded = false) {
                        entries.forEach { feature ->
                            val label = feature.name.removePrefix("$namespace.")
                            if (feature.version > 0) {
                                text(
                                    label,
                                    versionText(feature.name, feature.version),
                                    raw = feature.version.toString(),
                                )
                            } else {
                                flag(label, true)
                            }
                        }
                    },
                )
            }
        }
    }

    /**
     * `FeatureInfo.version` is an integer whose meaning each feature defines for itself: a
     * conformance date on the dEQP entries, a packed version on Vulkan, a hardware level on
     * `vulkan.level`, a KeyMint revision on the keystore ones, and something entirely private on a
     * vendor's own features. Only the encodings the platform documents are spelled out — the rest
     * are shown as the bare number the feature reported.
     *
     * Earlier this said "Version $version" for everything, which put a word the app had invented
     * in front of numbers that are not versions at all.
     */
    private fun versionText(name: String, version: Int): String = when {
        name.endsWith(DEQP_LEVEL_SUFFIX) -> formatDeqpLevel(version)
        name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION -> formatVulkanVersion(version)
        else -> version.toString()
    }

    /** `android.hardware.camera.flash` groups under `android.hardware`. */
    private fun namespaceOf(feature: FeatureInfo): String =
        feature.name.split('.').take(2).joinToString(".").ifEmpty { "other" }

    /**
     * The GL ES entry is the one [FeatureInfo] with a null name; its version is packed as
     * major in the high 16 bits and minor in the low 16.
     */
    private fun openGlEsVersion(): Int? = appContext.packageManager.systemAvailableFeatures
        .firstOrNull { it.name == null && it.reqGlEsVersion != FeatureInfo.GL_ES_VERSION_UNDEFINED }
        ?.reqGlEsVersion

    private companion object {
        /** Both `vulkan.deqp.level` and `opengles.deqp.level` carry a packed conformance date. */
        const val DEQP_LEVEL_SUFFIX = ".deqp.level"
    }
}
