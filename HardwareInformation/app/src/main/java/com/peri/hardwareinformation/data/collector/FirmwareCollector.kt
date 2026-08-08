package com.peri.hardwareinformation.data.collector

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.section
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The image the manufacturer shipped, and what it is made of.
 *
 * The software categories are split along the line Treble already draws — [SystemCollector] is the
 * platform Google controls, this is the vendor's image around it, and [SecurityCollector] is what
 * decides either may run. That line is not an abstraction: the partition list below shows a Galaxy
 * Tab S10+ running an Android 16 system on an Android 14 vendor image, two releases apart in the
 * same device, which is the whole point of the split and something no single "OS version" row can
 * express.
 *
 * Every fingerprint in the inventory lives here rather than in `System`, so the per-partition
 * comparison is complete and no build string appears in two categories.
 */
class FirmwareCollector : Collector {
    override val id = "firmware"
    override val title = "Firmware"
    override val icon = Icons.Filled.Layers

    override suspend fun sections(): List<Section> {
        val properties = readSystemProperties()

        return buildList {
            add(
                section("Image", fullWidth = true) {
                    longText("Description", properties["ro.build.description"])
                    text("Flavor", properties["ro.build.flavor"])
                    text("Build date", formatBuildTime(Build.TIME), raw = Build.TIME.toString())
                    val characteristics = properties["ro.build.characteristics"]?.split(',')
                    tokens(
                        "Form factor",
                        characteristics?.map { it.trim().replaceFirstChar(Char::uppercase) },
                        raw = characteristics,
                    )
                    // The API the device shipped on, which is not the API it is running now. There
                    // is no Build constant for it — DEVICE_INITIAL_SDK_INT is not in this SDK — so
                    // it comes from the property the platform itself reads.
                    text("Launched on API", properties["ro.product.first_api_level"])
                    longText("Base OS", Build.VERSION.BASE_OS)
                },
            )
            add(
                section("Compatibility") {
                    flag("Treble", properties["ro.treble.enabled"]?.let { it == "true" })
                    text("VNDK version", properties["ro.vndk.version"])
                    flag("Preview build", Build.VERSION.PREVIEW_SDK_INT > 0)
                    text(
                        "Media performance class",
                        sinceApi(31) {
                            Build.VERSION.MEDIA_PERFORMANCE_CLASS.takeIf { it > 0 }?.toString()
                        },
                    )
                },
            )
            // Absent entirely on a device that ships stock Android, and a section of five
            // "Not supported" rows would say less than no section at all.
            val oneUi = properties["ro.build.version.oneui"]?.toIntOrNull()
            val sep = properties["ro.build.version.sep"]
            val csc = properties["mdc.omc.config_version"]
            if (oneUi != null || sep != null || csc != null) {
                add(
                    section("Vendor software") {
                        // 80500 is 8.05.00. Samsung has never published the encoding, but the digit
                        // grouping is legible in the number itself and both test devices agree with
                        // the One UI version their own settings screen shows. The raw code is one
                        // toggle away for anyone who disagrees with the reading.
                        text("One UI", oneUi?.let(::oneUiVersion), raw = oneUi?.toString())
                        text("SEP version", sep)
                        text("CSC configuration", csc)
                    },
                )
            }
            val partitions = PARTITIONS.mapNotNull { (property, label) ->
                partitionRelease(properties, property)?.let { Triple(label, it.first, it.second) }
            }
            if (partitions.isNotEmpty()) {
                add(
                    section("Partitions", fullWidth = true) {
                        partitions.forEach { (label, readable, raw) -> text(label, readable, raw) }
                    },
                )
            }
            val fingerprints = PARTITIONS.mapNotNull { (property, label) ->
                properties["ro.$property.build.fingerprint"]?.let { label to it }
            }
            if (fingerprints.isNotEmpty()) {
                add(
                    section("Build fingerprints", initiallyExpanded = false) {
                        fingerprints.forEach { (label, fingerprint) -> longText(label, fingerprint) }
                    },
                )
            }
        }
    }

    private companion object {
        /**
         * Ordered as the platform stacks them, most-Google to most-vendor, so a version that falls
         * behind shows up as a break in a descending column rather than something to hunt for.
         * `bootimage` is the odd name out because that is what the property is called.
         */
        val PARTITIONS = listOf(
            "system" to "System",
            "system_ext" to "System ext",
            "product" to "Product",
            "vendor" to "Vendor",
            "odm" to "ODM",
            "bootimage" to "Boot image",
        )

        /**
         * Prefers the marketing release over the API level because they say the same thing and one
         * of them is a number people recognise; falls back when a partition reports only the SDK.
         */
        fun partitionRelease(properties: Map<String, String>, partition: String): Pair<String, String>? {
            properties["ro.$partition.build.version.release"]?.let { return "Android $it" to it }
            properties["ro.$partition.build.version.sdk"]?.let { return "API $it" to it }
            return null
        }

        fun oneUiVersion(code: Int): String {
            val major = code / 10_000
            val minor = (code / 100) % 100
            val patch = code % 100
            return if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
        }

        /** Device-local time, because that is the clock the build number is stamped against. */
        fun formatBuildTime(millis: Long): String? = millis.takeIf { it > 0 }?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it))
        }
    }
}
