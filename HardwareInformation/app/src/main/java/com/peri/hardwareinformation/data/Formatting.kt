package com.peri.hardwareinformation.data

import java.util.Locale

/**
 * Binary (1024-based) byte formatting, matching what kernels and hardware tools report. Pure and
 * locale-independent so it can be unit tested.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val pattern = if (value >= 100.0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[unit])
}

/**
 * Recovers the size a storage device is sold as. Vendors disagree about what "256 GB" means in
 * bytes — AOSP rounds the disk up to 256 × 10⁹, Samsung to 256 × 2³⁰ — so neither base formats
 * both correctly, and the marketing figure has to be matched by proximity instead: the nearest
 * power-of-two decimal size within 12 %. Returns null when nothing is close enough.
 */
fun formatMarketedBytes(bytes: Long): String? {
    if (bytes <= 0L) return null
    val candidates = buildList {
        for (shift in 0..9) add((1L shl shift) * 1_000_000_000L to "${1L shl shift} GB")
        for (shift in 0..2) add((1L shl shift) * 1_000_000_000_000L to "${1L shl shift} TB")
    }
    val (size, label) = candidates.minBy { (size, _) -> kotlin.math.abs(bytes - size) }
    val ratio = bytes.toDouble() / size
    return if (ratio in 0.88..1.12) label else null
}

fun formatPercent(used: Long, total: Long): String =
    if (total <= 0L) "—" else String.format(Locale.US, "%.0f%%", used.toDouble() / total * 100.0)

/**
 * Thousands separators for raw counts and durations. "200000 µs" has to be counted digit by digit;
 * "200,000 µs" does not. Identifiers and version numbers are deliberately left ungrouped.
 */
fun formatGrouped(value: Long): String = String.format(Locale.US, "%,d", value)

/** Clock speeds come out of sysfs in kHz, which is never how anyone wants to read them. */
fun formatKiloHertz(kiloHertz: Long): String = when {
    kiloHertz <= 0L -> "—"
    kiloHertz >= 1_000_000L -> String.format(Locale.US, "%.2f GHz", kiloHertz / 1_000_000.0)
    else -> "${formatGrouped(kiloHertz / 1_000)} MHz"
}

fun formatHertz(hertz: Long): String = when {
    hertz <= 0L -> "—"
    hertz >= 1_000_000_000L -> String.format(Locale.US, "%.2f GHz", hertz / 1_000_000_000.0)
    hertz >= 1_000_000L -> String.format(Locale.US, "%.1f MHz", hertz / 1_000_000.0)
    else -> "${formatGrouped(hertz)} Hz"
}

/** Coarse duration for uptime: days and hours matter, seconds do not. */
fun formatDuration(millis: Long): String {
    if (millis < 0L) return "—"
    val seconds = millis / 1_000L
    val days = seconds / 86_400L
    val hours = seconds % 86_400L / 3_600L
    val minutes = seconds % 3_600L / 60L
    return buildList {
        if (days > 0L) add("${days}d")
        if (days > 0L || hours > 0L) add("${hours}h")
        add("${minutes}m")
    }.joinToString(" ")
}

/**
 * Android publishes several capability numbers as packed integers, and the same packed value shows
 * up in more than one category — the Vulkan version appears both in `GPU` and in the raw feature
 * list under `System features`. These decoders live here, shared, because the first cut of this app
 * decoded them in one collector and printed the bare integer in the other, so the same fact read
 * two different ways depending on where you looked.
 */

/** Vulkan packs its version as major(7) | minor(10) | patch(12) bits. */
fun formatVulkanVersion(packed: Int): String =
    "${packed ushr 22}.${(packed ushr 12) and 0x3FF}.${packed and 0xFFF}"

/** A dEQP conformance level is a date packed as (year << 16) | (month << 8) | day. */
fun formatDeqpLevel(packed: Int): String = String.format(
    Locale.US,
    "%d-%02d-%02d",
    packed ushr 16,
    (packed ushr 8) and 0xFF,
    packed and 0xFF,
)

/** GL ES versions pack major into the high 16 bits and minor into the low 16. */
fun formatGlEsVersion(packed: Int): String = "${packed ushr 16}.${packed and 0xFFFF}"

/** Sensor and camera resolutions read better as megapixels than as a pixel count. */
fun formatMegapixels(width: Int, height: Int): String =
    String.format(Locale.US, "%.1f MP", width.toLong() * height / 1_000_000.0)
