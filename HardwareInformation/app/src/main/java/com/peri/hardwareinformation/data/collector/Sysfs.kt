package com.peri.hardwareinformation.data.collector

import java.io.File

/**
 * Reads a kernel-exposed file, returning null rather than throwing.
 *
 * Most of `/sys` and `/proc` is a lottery on modern Android: SELinux policy, vendor kernels and API
 * level all decide what a normal app may read, and the same path works on one device and not the
 * next. Collectors funnel these through
 * [com.peri.hardwareinformation.data.model.SectionScope.attempt] so a denial becomes one honest
 * "Could not be read" row.
 */
internal fun readSysfs(path: String): String? = runCatching {
    File(path).takeIf { it.canRead() }?.readText()?.trim()?.takeIf(String::isNotEmpty)
}.getOrNull()

/** Parses a `key: value` file such as /proc/cpuinfo or /proc/meminfo. */
internal fun readKeyedFile(path: String): Map<String, String> = runCatching {
    File(path).useLines { lines ->
        lines.mapNotNull { line ->
            val key = line.substringBefore(':', missingDelimiterValue = "").trim()
            val value = line.substringAfter(':', missingDelimiterValue = "").trim()
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }.toMap()
    }
}.getOrDefault(emptyMap())
