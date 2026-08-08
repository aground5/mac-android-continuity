package com.peri.hardwareinformation.data.collector

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Monitor
import com.peri.hardwareinformation.data.formatMegapixels
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.section
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Every attached display, not just the built-in one — a tablet in DeX or a phone on a dock reports
 * several, and each has its own modes and HDR support.
 */
class DisplayCollector(context: Context) : Collector {
    override val id = "display"
    override val title = "Display"
    override val icon = Icons.Filled.Monitor

    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    override suspend fun sections(): List<Section> {
        val metrics = appContext.resources.displayMetrics
        val configuration = appContext.resources.configuration
        val displays = displayManager.displays.orEmpty()

        return buildList {
            add(
                section("Overview", fullWidth = true) {
                    text("Displays", displays.size.toString())
                    text("Density", "${metrics.densityDpi} dpi", raw = metrics.densityDpi.toString())
                    text("Density bucket", densityBucket(metrics.densityDpi))
                    text("Scale factor", String.format(Locale.US, "%.2f×", metrics.density), raw = metrics.density.toString())
                    text("Font scale", String.format(Locale.US, "%.2f×", configuration.fontScale), raw = configuration.fontScale.toString())
                    text(
                        "Window size",
                        "${configuration.screenWidthDp} × ${configuration.screenHeightDp} dp",
                    )
                    // What the adaptive layout actually branches on.
                    text(
                        "Smallest width",
                        "${configuration.smallestScreenWidthDp} dp",
                        raw = configuration.smallestScreenWidthDp.toString(),
                    )
                    flag("Round screen", configuration.isScreenRound)
                },
            )
            displays.forEach { display ->
                add(displaySection(display, metrics, expanded = displays.size == 1))
            }
        }
    }

    private fun displaySection(
        display: Display,
        metrics: DisplayMetrics,
        expanded: Boolean,
    ): Section = section(display.name ?: "Display ${display.displayId}", initiallyExpanded = expanded) {
        val mode = display.mode
        text("Id", display.displayId.toString())
        text("State", stateName(display.state), raw = display.state.toString())
        text("Resolution", "${mode.physicalWidth} × ${mode.physicalHeight}")
        text(
            "Pixel count",
            formatMegapixels(mode.physicalWidth, mode.physicalHeight),
            raw = (mode.physicalWidth.toLong() * mode.physicalHeight).toString(),
        )
        text(
            "Refresh rate",
            String.format(Locale.US, "%.2f Hz", mode.refreshRate),
            raw = mode.refreshRate.toString(),
        )
        // distinctBy on the readable form, so the two lists stay index-aligned when a display
        // reports several modes that differ only in something this row does not show.
        val modes = display.supportedModes
            .map { "${it.physicalWidth}×${it.physicalHeight} @ ${it.refreshRate.roundToInt()}Hz" to it.modeId }
            .distinctBy { (label, _) -> label }
        tokens(
            "Supported modes",
            modes.map { (label, _) -> label },
            raw = modes.map { (_, id) -> id.toString() },
        )
        // Raw form is the Surface.ROTATION_* constant (0–3), not degrees.
        text("Rotation", "${display.rotation * 90}°", raw = display.rotation.toString())
        // xdpi/ydpi describe the display the app is on, so a diagonal is only honest for that one.
        attempt("Diagonal") {
            if (display.displayId != Display.DEFAULT_DISPLAY) null
            else diagonalInches(mode.physicalWidth, mode.physicalHeight, metrics)
        }
        flag("Display cutout", display.cutout != null)
        val hdrTypes = hdrTypes(display)
        tokens(
            "HDR",
            hdrTypes.map(::hdrTypeName),
            raw = hdrTypes.map(Int::toString),
        )
        val nits = maxLuminance(display)
        text(
            "HDR max luminance",
            nits?.let { String.format(Locale.US, "%.0f nits", it) },
            raw = nits?.toString(),
        )
        flag("Wide colour gamut", display.isWideColorGamut)
        flag("Minimal post-processing", sinceApi(30) { display.isMinimalPostProcessingSupported })
    }

    private fun diagonalInches(width: Int, height: Int, metrics: DisplayMetrics): String? {
        if (metrics.xdpi <= 0f || metrics.ydpi <= 0f) return null
        val inches = sqrt(
            (width / metrics.xdpi).toDouble() * (width / metrics.xdpi) +
                (height / metrics.ydpi).toDouble() * (height / metrics.ydpi),
        )
        return String.format(Locale.US, "%.1f\"", inches)
    }

    /** Deprecated in API 34, but still the only public source of a luminance figure. */
    private fun maxLuminance(display: Display): Float? {
        @Suppress("DEPRECATION")
        val nits = display.hdrCapabilities?.desiredMaxLuminance ?: return null
        return nits.takeIf { it > 0f }
    }

    private fun hdrTypes(display: Display): List<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            display.mode.supportedHdrTypes.toList()
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty()
        }

    /** Covers every `HDR_TYPE_*` the SDK defines, checked against android.jar. */
    private fun hdrTypeName(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HLG_PLUS -> "HLG+"
        else -> "Type $type"
    }

    /** Covers every `Display.STATE_*` the SDK defines, checked against android.jar. */
    private fun stateName(state: Int): String = when (state) {
        Display.STATE_UNKNOWN -> "Unknown"
        Display.STATE_ON -> "On"
        Display.STATE_OFF -> "Off"
        Display.STATE_DOZE -> "Doze"
        Display.STATE_DOZE_SUSPEND -> "Doze (suspended)"
        Display.STATE_VR -> "VR"
        Display.STATE_ON_SUSPEND -> "On (suspended)"
        else -> "State $state"
    }

    private fun densityBucket(dpi: Int): String = when {
        dpi <= DisplayMetrics.DENSITY_LOW -> "ldpi"
        dpi <= DisplayMetrics.DENSITY_MEDIUM -> "mdpi"
        dpi <= DisplayMetrics.DENSITY_HIGH -> "hdpi"
        dpi <= DisplayMetrics.DENSITY_XHIGH -> "xhdpi"
        dpi <= DisplayMetrics.DENSITY_XXHIGH -> "xxhdpi"
        else -> "xxxhdpi"
    }
}
