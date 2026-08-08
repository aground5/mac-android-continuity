package com.peri.hardwareinformation.data.collector

/**
 * Reads the whole `ro.*` property store in one shot.
 *
 * Android has no public API for system properties — `android.os.SystemProperties` is hidden and
 * reflecting onto it is blocked on current API levels. Running the `getprop` binary is the
 * supported-by-omission route that survives: it is an ordinary executable a normal app may exec,
 * and with no argument it dumps every property as `[key]: [value]`.
 *
 * One process for the whole map rather than one per lookup — a collector reads a dozen of these,
 * and a dozen forks would be visible in the loading skeleton.
 */
internal fun readSystemProperties(): Map<String, String> = runCatching {
    val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
    val properties = process.inputStream.bufferedReader().useLines { lines ->
        lines.mapNotNull(::parseProperty).toMap()
    }
    process.waitFor()
    properties
}.getOrDefault(emptyMap())

/** `[ro.boot.flash.locked]: [1]` becomes `ro.boot.flash.locked` to `1`. Blanks are dropped. */
private fun parseProperty(line: String): Pair<String, String>? {
    val key = line.substringAfter('[', missingDelimiterValue = "")
        .substringBefore(']', missingDelimiterValue = "")
        .takeIf(String::isNotEmpty)
        ?: return null
    val value = line.substringAfter("]: [", missingDelimiterValue = "")
        .substringBeforeLast(']', missingDelimiterValue = "")
        .trim()
        .takeIf(String::isNotEmpty)
        ?: return null
    return key to value
}
