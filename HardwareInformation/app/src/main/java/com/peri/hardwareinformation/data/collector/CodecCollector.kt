package com.peri.hardwareinformation.data.collector

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDrm
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import com.peri.hardwareinformation.data.formatGrouped
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section
import java.util.Locale
import java.util.UUID

/**
 * Every codec the platform advertises, hardware and software alike. A modern phone reports a couple
 * of hundred, which makes this the category that actually takes long enough to show the loading
 * skeleton — enumerating capabilities per codec is the slow part, not the list itself.
 */
class CodecCollector : Collector {
    override val id = "codecs"
    override val title = "Codecs"
    override val icon = Icons.Filled.Movie

    override suspend fun sections(): List<Section> {
        val codecs = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        }.getOrDefault(emptyList())
            .sortedWith(compareBy({ !it.isEncoder }, { it.name.lowercase(Locale.ROOT) }))

        return buildList {
            add(
                section("Overview", fullWidth = true) {
                    text("Codecs", formatGrouped(codecs.size.toLong()), raw = codecs.size.toString())
                    text("Decoders", codecs.count { !it.isEncoder }.toString())
                    text("Encoders", codecs.count { it.isEncoder }.toString())
                    text("Hardware accelerated", codecs.count { it.isHardwareAccelerated }.toString())
                    text("Software only", codecs.count { it.isSoftwareOnly }.toString())
                    text("Vendor provided", codecs.count { it.isVendor }.toString())
                    tokens(
                        "Media types",
                        codecs.flatMap { it.supportedTypes.toList() }.distinct().sorted(),
                    )
                },
            )
            add(drmSection())
            codecs.forEach { codec ->
                add(codecSection(codec))
            }
        }
    }

    private fun codecSection(codec: MediaCodecInfo): Section =
        section(codec.name, initiallyExpanded = false) {
            text("Role", if (codec.isEncoder) "Encoder" else "Decoder")
            tokens("Media types", codec.supportedTypes.toList())
            flag("Hardware accelerated", codec.isHardwareAccelerated)
            flag("Software only", codec.isSoftwareOnly)
            flag("Vendor provided", codec.isVendor)
            flag("Alias", codec.isAlias)
            // Only worth a row when it differs. Emitting an empty one rendered "Not supported",
            // which reads as a missing capability rather than "the name above is already canonical".
            codec.canonicalName.takeIf { it != codec.name }?.let { text("Canonical name", it) }

            codec.supportedTypes.forEach { type ->
                val capabilities = runCatching { codec.getCapabilitiesForType(type) }.getOrNull()
                    ?: return@forEach
                text("$type · max instances", capabilities.maxSupportedInstances.toString())
                // Raw drops the "up to" framing and the unit scaling, leaving the bare ceiling the
                // capability object reports.
                capabilities.videoCapabilities?.let { video ->
                    text(
                        "$type · resolution",
                        "up to ${video.supportedWidths.upper} × ${video.supportedHeights.upper}",
                        raw = "${video.supportedWidths.upper} × ${video.supportedHeights.upper}",
                    )
                    text(
                        "$type · frame rate",
                        "up to ${video.supportedFrameRates.upper} fps",
                        raw = video.supportedFrameRates.upper.toString(),
                    )
                    text(
                        "$type · bitrate",
                        "up to ${formatBitrate(video.bitrateRange.upper)}",
                        raw = video.bitrateRange.upper.toString(),
                    )
                }
                capabilities.audioCapabilities?.let { audio ->
                    tokens(
                        "$type · sample rates",
                        audio.supportedSampleRates?.map { "${formatGrouped(it.toLong())} Hz" },
                        raw = audio.supportedSampleRates?.map(Int::toString),
                    )
                    text(
                        "$type · channels",
                        "up to ${audio.maxInputChannelCount}",
                        raw = audio.maxInputChannelCount.toString(),
                    )
                    text(
                        "$type · bitrate",
                        "up to ${formatBitrate(audio.bitrateRange.upper)}",
                        raw = audio.bitrateRange.upper.toString(),
                    )
                }
            }
        }

    private fun formatBitrate(bitsPerSecond: Int): String = when {
        bitsPerSecond >= 1_000_000 ->
            String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)

        bitsPerSecond >= 1_000 -> "${formatGrouped(bitsPerSecond / 1_000L)} kbps"
        else -> "$bitsPerSecond bps"
    }

    /**
     * What the device can play that it is not allowed to hand over in the clear. A codec list says
     * nothing about this: streaming services gate their highest resolutions on the DRM security
     * level, not on whether an H.265 decoder exists, so a device with every codec and an L3
     * Widevine still gets 480p from most of them.
     *
     * Scheme UUIDs are the identifiers from the DASH-IF content protection registry. The security
     * level string is passed through exactly as the vendor reports it ("L1", "L3") because it is a
     * Widevine-defined value with no platform constant to check it against.
     */
    private fun drmSection(): Section = section("DRM") {
        val supported = SCHEMES.filter { (uuid, _) ->
            runCatching { MediaDrm.isCryptoSchemeSupported(uuid) }.getOrDefault(false)
        }
        tokens(
            "Schemes",
            supported.map { (_, name) -> name },
            raw = supported.map { (uuid, _) -> uuid.toString() },
        )
        val widevine = runCatching { MediaDrm(WIDEVINE) }.getOrNull()
        if (widevine == null) {
            unavailable("Widevine", UnavailableReason.NOT_SUPPORTED)
            return@section
        }
        try {
            text("Widevine version", drmProperty(widevine, MediaDrm.PROPERTY_VERSION))
            text("Widevine vendor", drmProperty(widevine, MediaDrm.PROPERTY_VENDOR))
            text("Widevine security level", drmProperty(widevine, SECURITY_LEVEL))
            val connected = runCatching { widevine.connectedHdcpLevel }.getOrNull()
            text("HDCP connected", connected?.let(::hdcpName), raw = connected?.toString())
            val max = runCatching { widevine.maxHdcpLevel }.getOrNull()
            text("HDCP maximum", max?.let(::hdcpName), raw = max?.toString())
        } finally {
            runCatching { widevine.close() }
        }
    }

    private fun drmProperty(drm: MediaDrm, name: String): String? =
        runCatching { drm.getPropertyString(name) }.getOrNull()

    /** Straight from the constant names; the platform defines no display strings for these. */
    private fun hdcpName(level: Int): String? = when (level) {
        MediaDrm.HDCP_NONE -> "None"
        MediaDrm.HDCP_V1 -> "HDCP 1.x"
        MediaDrm.HDCP_V2 -> "HDCP 2.0"
        MediaDrm.HDCP_V2_1 -> "HDCP 2.1"
        MediaDrm.HDCP_V2_2 -> "HDCP 2.2"
        MediaDrm.HDCP_V2_3 -> "HDCP 2.3"
        MediaDrm.HDCP_NO_DIGITAL_OUTPUT -> "No digital output"
        else -> null
    }


    private companion object {
        val WIDEVINE: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

        val SCHEMES = listOf(
            WIDEVINE to "Widevine",
            UUID.fromString("9a04f079-9840-4286-ab92-e65be0885f95") to "PlayReady",
            UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e") to "ClearKey",
        )

        /** A Widevine vendor property, not a MediaDrm constant — hence the literal. */
        const val SECURITY_LEVEL = "securityLevel"
    }
}
