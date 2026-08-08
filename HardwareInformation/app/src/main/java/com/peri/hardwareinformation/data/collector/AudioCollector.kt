package com.peri.hardwareinformation.data.collector

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import com.peri.hardwareinformation.data.formatGrouped
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.section

/**
 * Audio routes and the platform's preferred output parameters. Sinks come before sources so the
 * speakers a user is looking for are at the top rather than buried among microphones.
 */
class AudioCollector(context: Context) : Collector {
    override val id = "audio"
    override val title = "Audio"
    override val icon = Icons.AutoMirrored.Filled.VolumeUp

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    override suspend fun sections(): List<Section> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
            .sortedWith(compareByDescending<AudioDeviceInfo> { it.isSink }.thenBy { typeName(it.type) })

        return buildList {
            add(
                section("Output parameters", fullWidth = true) {
                    val sampleRate = audioManager
                        .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                        ?.toLongOrNull()
                    text(
                        "Preferred sample rate",
                        sampleRate?.let { "${formatGrouped(it)} Hz" },
                        raw = sampleRate?.toString(),
                    )
                    val buffer = audioManager
                        .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                    text(
                        "Preferred buffer size",
                        buffer?.let { "$it frames" },
                        raw = buffer,
                    )
                    text("Devices", devices.size.toString())
                    text("Outputs", devices.count { it.isSink }.toString())
                    text("Inputs", devices.count { it.isSource }.toString())
                    flag("Low-latency audio", hasFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY))
                    flag("Professional audio", hasFeature(PackageManager.FEATURE_AUDIO_PRO))
                    flag("MIDI", hasFeature(PackageManager.FEATURE_MIDI))
                    flag("Spatial audio", sinceApi(32) { audioManager.spatializer.isAvailable })
                },
            )
            add(effectsSection())
            devices.forEach { device ->
                add(deviceSection(device))
            }
        }
    }

    /**
     * The DSP and software effects the audio framework can insert into a stream — equalisers,
     * loudness enhancers, noise suppressors and whatever the vendor adds on top. None of it is
     * visible from the device list above: an effect is code the platform loads, not a jack or a
     * speaker, and it is the reason two devices with identical hardware can sound different.
     */
    private fun effectsSection(): Section = section("Effects") {
        val effects = runCatching { AudioEffect.queryEffects() }.getOrNull().orEmpty()
        text("Available", formatGrouped(effects.size.toLong()), raw = effects.size.toString())
        tokens("Names", effects.mapNotNull { it.name }.distinct().sorted())
        tokens("Implementors", effects.mapNotNull { it.implementor }.distinct().sorted())
    }

    private fun deviceSection(device: AudioDeviceInfo): Section {
        val label = device.productName?.toString()?.takeIf(String::isNotBlank)
        val title = listOfNotNull(typeName(device.type), label).distinct().joinToString(" · ")
        return section(title, initiallyExpanded = false) {
            text("Id", device.id.toString())
            // Direction has no machine constant behind it — it is read off isSink — so it carries
            // no raw form and reads identically in both modes.
            text("Direction", if (device.isSink) "Output" else "Input")
            text("Type", typeName(device.type), raw = device.type.toString())
            text("Address", device.address.takeIf(String::isNotBlank))
            tokens(
                "Sample rates",
                device.sampleRates.map { "${formatGrouped(it.toLong())} Hz" },
                raw = device.sampleRates.map(Int::toString),
            )
            tokens("Channel counts", device.channelCounts.map(Int::toString))
            tokens(
                "Channel masks",
                device.channelMasks.map { channelMaskName(it, device.isSink) },
                raw = device.channelMasks.map { "0x${Integer.toHexString(it)}" },
            )
            tokens(
                "Encodings",
                device.encodings.map(::encodingName),
                raw = device.encodings.map(Int::toString),
            )
        }
    }

    private fun hasFeature(name: String) = appContext.packageManager.hasSystemFeature(name)

    /**
     * A channel mask is a bitmap with one bit per speaker or microphone position, so "2 channels"
     * and "which two" are different facts — 0xC is front left plus front right, 0x30 is a front
     * and a back mic, and both report a count of two.
     *
     * The same bit means different things either side of the device: 0x10 is the front centre
     * speaker on a sink and the single microphone on a source, so [isSink] chooses the table.
     * Masks matching a standard layout are named as that layout; the rest are decomposed into the
     * positions they set, and any bit the SDK does not name is left as hex rather than guessed at.
     */
    private fun channelMaskName(mask: Int, isSink: Boolean): String {
        val hex = { value: Int -> "0x${Integer.toHexString(value)}" }
        (if (isSink) OUTPUT_LAYOUTS else INPUT_LAYOUTS)[mask]?.let { return it }

        var unnamed = mask
        val positions = (if (isSink) OUTPUT_POSITIONS else INPUT_POSITIONS).mapNotNull { (bit, name) ->
            if (mask and bit == bit) {
                unnamed = unnamed and bit.inv()
                name
            } else {
                null
            }
        }
        if (positions.isEmpty()) return hex(mask)
        return (positions + listOfNotNull(unnamed.takeIf { it != 0 }?.let(hex))).joinToString(" + ")
    }

    /** Covers every `AudioFormat.ENCODING_*` the SDK defines, checked against android.jar. */
    private fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_DEFAULT -> "Default"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        AudioFormat.ENCODING_AC3 -> "AC-3"
        AudioFormat.ENCODING_E_AC3 -> "E-AC-3"
        AudioFormat.ENCODING_E_AC3_JOC -> "E-AC-3 JOC"
        AudioFormat.ENCODING_AC4 -> "AC-4"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        AudioFormat.ENCODING_DTS_HD_MA -> "DTS-HD MA"
        AudioFormat.ENCODING_DTS_UHD_P1 -> "DTS:X P1"
        AudioFormat.ENCODING_DTS_UHD_P2 -> "DTS:X P2"
        AudioFormat.ENCODING_AAC_LC -> "AAC-LC"
        AudioFormat.ENCODING_AAC_HE_V1 -> "HE-AAC v1"
        AudioFormat.ENCODING_AAC_HE_V2 -> "HE-AAC v2"
        AudioFormat.ENCODING_AAC_ELD -> "AAC-ELD"
        AudioFormat.ENCODING_AAC_XHE -> "xHE-AAC"
        AudioFormat.ENCODING_MPEGH_BL_L3 -> "MPEG-H BL L3"
        AudioFormat.ENCODING_MPEGH_BL_L4 -> "MPEG-H BL L4"
        AudioFormat.ENCODING_MPEGH_LC_L3 -> "MPEG-H LC L3"
        AudioFormat.ENCODING_MPEGH_LC_L4 -> "MPEG-H LC L4"
        AudioFormat.ENCODING_OPUS -> "Opus"
        AudioFormat.ENCODING_MP3 -> "MP3"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        AudioFormat.ENCODING_DOLBY_MAT -> "Dolby MAT"
        AudioFormat.ENCODING_IEC61937 -> "IEC 61937"
        AudioFormat.ENCODING_DRA -> "DRA"
        AudioFormat.ENCODING_DSD -> "DSD"
        else -> "Encoding $encoding"
    }

    /** Covers every `AudioDeviceInfo.TYPE_*` the SDK defines, checked against android.jar. */
    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_UNKNOWN -> "Unknown"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Speaker (safe)"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE speaker"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE broadcast"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        AudioDeviceInfo.TYPE_DOCK_ANALOG -> "Dock (analogue)"
        AudioDeviceInfo.TYPE_MULTICHANNEL_GROUP -> "Multichannel group"
        AudioDeviceInfo.TYPE_BLE_HEARING_AID -> "BLE hearing aid"
        AudioDeviceInfo.TYPE_BLE_CENTRAL -> "BLE central"
        AudioDeviceInfo.TYPE_BLE_CENTRAL_BROADCAST -> "BLE central broadcast"
        AudioDeviceInfo.TYPE_FM -> "FM"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV tuner"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analogue line"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line"
        AudioDeviceInfo.TYPE_AUX_LINE -> "Aux line"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "Bus"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote submix"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        else -> "Type $type"
    }

    private companion object {
        /** Exact masks the SDK gives a layout name to. Checked against android.jar. */
        val OUTPUT_LAYOUTS = mapOf(
            AudioFormat.CHANNEL_OUT_MONO to "Mono",
            AudioFormat.CHANNEL_OUT_STEREO to "Stereo",
            AudioFormat.CHANNEL_OUT_QUAD to "Quad",
            AudioFormat.CHANNEL_OUT_SURROUND to "Surround",
            AudioFormat.CHANNEL_OUT_5POINT1 to "5.1",
            AudioFormat.CHANNEL_OUT_6POINT1 to "6.1",
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND to "7.1",
            AudioFormat.CHANNEL_OUT_5POINT1POINT2 to "5.1.2",
            AudioFormat.CHANNEL_OUT_5POINT1POINT4 to "5.1.4",
            AudioFormat.CHANNEL_OUT_7POINT1POINT2 to "7.1.2",
            AudioFormat.CHANNEL_OUT_7POINT1POINT4 to "7.1.4",
            AudioFormat.CHANNEL_OUT_9POINT1POINT4 to "9.1.4",
            AudioFormat.CHANNEL_OUT_9POINT1POINT6 to "9.1.6",
            AudioFormat.CHANNEL_OUT_13POINT0 to "13.0",
        )

        val INPUT_LAYOUTS = mapOf(
            AudioFormat.CHANNEL_IN_MONO to "Mono",
            AudioFormat.CHANNEL_IN_STEREO to "Stereo",
        )

        /** Single-position bits, most significant layouts first so the join reads front to back. */
        val OUTPUT_POSITIONS = listOf(
            AudioFormat.CHANNEL_OUT_FRONT_LEFT to "Front left",
            AudioFormat.CHANNEL_OUT_FRONT_RIGHT to "Front right",
            AudioFormat.CHANNEL_OUT_FRONT_CENTER to "Front centre",
            AudioFormat.CHANNEL_OUT_LOW_FREQUENCY to "LFE",
            AudioFormat.CHANNEL_OUT_BACK_LEFT to "Back left",
            AudioFormat.CHANNEL_OUT_BACK_RIGHT to "Back right",
            AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER to "Front left of centre",
            AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER to "Front right of centre",
            AudioFormat.CHANNEL_OUT_BACK_CENTER to "Back centre",
            AudioFormat.CHANNEL_OUT_SIDE_LEFT to "Side left",
            AudioFormat.CHANNEL_OUT_SIDE_RIGHT to "Side right",
            AudioFormat.CHANNEL_OUT_TOP_CENTER to "Top centre",
            AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT to "Top front left",
            AudioFormat.CHANNEL_OUT_TOP_FRONT_CENTER to "Top front centre",
            AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT to "Top front right",
            AudioFormat.CHANNEL_OUT_TOP_BACK_LEFT to "Top back left",
            AudioFormat.CHANNEL_OUT_TOP_BACK_CENTER to "Top back centre",
            AudioFormat.CHANNEL_OUT_TOP_BACK_RIGHT to "Top back right",
            AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT to "Top side left",
            AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT to "Top side right",
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT to "Bottom front left",
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER to "Bottom front centre",
            AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT to "Bottom front right",
            AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2 to "LFE 2",
            AudioFormat.CHANNEL_OUT_FRONT_WIDE_LEFT to "Front wide left",
            AudioFormat.CHANNEL_OUT_FRONT_WIDE_RIGHT to "Front wide right",
        )

        val INPUT_POSITIONS = listOf(
            AudioFormat.CHANNEL_IN_LEFT to "Left",
            AudioFormat.CHANNEL_IN_RIGHT to "Right",
            AudioFormat.CHANNEL_IN_FRONT to "Front",
            AudioFormat.CHANNEL_IN_BACK to "Back",
            AudioFormat.CHANNEL_IN_LEFT_PROCESSED to "Left (processed)",
            AudioFormat.CHANNEL_IN_RIGHT_PROCESSED to "Right (processed)",
            AudioFormat.CHANNEL_IN_FRONT_PROCESSED to "Front (processed)",
            AudioFormat.CHANNEL_IN_BACK_PROCESSED to "Back (processed)",
            AudioFormat.CHANNEL_IN_PRESSURE to "Pressure",
            AudioFormat.CHANNEL_IN_X_AXIS to "X axis",
            AudioFormat.CHANNEL_IN_Y_AXIS to "Y axis",
            AudioFormat.CHANNEL_IN_Z_AXIS to "Z axis",
            AudioFormat.CHANNEL_IN_VOICE_UPLINK to "Voice uplink",
            AudioFormat.CHANNEL_IN_VOICE_DNLINK to "Voice downlink",
        )
    }
}
