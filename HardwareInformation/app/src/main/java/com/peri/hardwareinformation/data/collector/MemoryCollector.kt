package com.peri.hardwareinformation.data.collector

import android.app.ActivityManager
import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import com.peri.hardwareinformation.data.formatBytes
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * RAM. This is the category that proves the live path: free memory moves constantly, so it is
 * re-read on a timer while the category is on screen and not at all otherwise.
 */
class MemoryCollector(context: Context) : Collector {
    override val id = "memory"
    override val title = "Memory"
    override val icon = Icons.Filled.Dns

    private val activityManager =
        context.applicationContext.getSystemService(ActivityManager::class.java)

    override suspend fun sections(): List<Section> = build()

    override fun live(): Flow<List<Section>> = flow {
        while (true) {
            emit(build())
            delay(REFRESH_MILLIS)
        }
    }

    private fun build(): List<Section> {
        val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val meminfo = readMemInfo()
        return listOf(
            section("RAM") {
                // Raw forms are plain bytes throughout the category.
                metric("In use", used = info.totalMem - info.availMem, total = info.totalMem)
                text("Total", formatBytes(info.totalMem), raw = info.totalMem.toString())
                text("Available", formatBytes(info.availMem), raw = info.availMem.toString())
                text("Low-memory threshold", formatBytes(info.threshold), raw = info.threshold.toString())
                flag("Low-RAM device", activityManager.isLowRamDevice)
                // Android 15 began shipping 16 KB-page devices, and the size decides whether a
                // native library built for 4 KB pages can be loaded at all — a software property
                // of the kernel, not a property of the memory chips.
                val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrNull()
                text(
                    "Page size",
                    pageSize?.takeIf { it > 0 }?.let(::formatBytes),
                    raw = pageSize?.toString(),
                    missing = UnavailableReason.READ_FAILED,
                )
            },
            section("Swap / zRAM") {
                val swapTotal = meminfo["SwapTotal"] ?: 0L
                if (swapTotal > 0L) {
                    val swapFree = meminfo["SwapFree"] ?: 0L
                    metric("In use", used = swapTotal - swapFree, total = swapTotal)
                    text("Total", formatBytes(swapTotal), raw = swapTotal.toString())
                } else {
                    // No swap configured, or /proc/meminfo was unreadable — both render the same way.
                    val reported = meminfo["SwapTotal"]
                    text(
                        "Total",
                        reported?.let(::formatBytes),
                        raw = reported?.toString(),
                        missing = UnavailableReason.READ_FAILED,
                    )
                }
                // zRAM trades CPU for RAM, and which codec it trades with is the whole character
                // of that trade. The kernel marks the active one in brackets inside a list of all
                // the ones it was built with.
                val algorithms = readSysfs(ZRAM_ALGORITHM)?.split(' ')?.filter(String::isNotBlank)
                text(
                    "Compression",
                    algorithms?.firstOrNull { it.startsWith('[') }?.trim('[', ']'),
                    raw = algorithms?.joinToString(" "),
                )
            },
            section("Process limits") {
                text("Heap limit (per app)", "${activityManager.memoryClass} MB", raw = activityManager.memoryClass.toString())
                text("Large heap limit", "${activityManager.largeMemoryClass} MB", raw = activityManager.largeMemoryClass.toString())
                val maxHeap = Runtime.getRuntime().maxMemory()
                text("JVM max heap", formatBytes(maxHeap), raw = maxHeap.toString())
            },
        )
    }

    /** Parses `/proc/meminfo` into bytes keyed by field name. Returns empty when unreadable. */
    private fun readMemInfo(): Map<String, Long> = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.mapNotNull { line ->
                val key = line.substringBefore(':', missingDelimiterValue = "").trim()
                val kilobytes = line.substringAfter(':', missingDelimiterValue = "")
                    .trim()
                    .removeSuffix(" kB")
                    .toLongOrNull()
                if (key.isEmpty() || kilobytes == null) null else key to kilobytes * 1024L
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    private companion object {
        const val ZRAM_ALGORITHM = "/sys/block/zram0/comp_algorithm"

        const val REFRESH_MILLIS = 2_000L
    }
}
