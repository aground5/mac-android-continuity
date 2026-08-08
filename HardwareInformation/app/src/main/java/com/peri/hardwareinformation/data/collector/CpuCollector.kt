package com.peri.hardwareinformation.data.collector

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import com.peri.hardwareinformation.data.formatKiloHertz
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Cores and clocks, with the current frequency of every core live while the category is on screen.
 *
 * Clocks sit in their own always-expanded section rather than inside the per-core cards. Those cards
 * are collapsed by default, and a live reading nobody can see is just wasted polling — putting the
 * frequencies where they are visible is what earns the refresh.
 *
 * Only the frequencies and the governor are re-read each tick. Core counts, cluster topology and
 * /proc/cpuinfo cannot change while the process lives, so they are read once and cached.
 */
class CpuCollector : Collector {
    override val id = "cpu"
    override val title = "CPU"
    override val icon = Icons.Filled.Memory

    private val fixed by lazy { readFixed() }

    override suspend fun sections(): List<Section> = build()

    override fun live(): Flow<List<Section>> = flow {
        while (true) {
            emit(build())
            delay(REFRESH_MILLIS)
        }
    }

    private fun build(): List<Section> = buildList {
        // Overview and Clocks are close in height, so they pair into the two columns without
        // leaving a gap — neither asks for the full width.
        add(overviewSection())
        add(clockSection())
        add(featureSection())
        (0 until fixed.cores).forEach { core -> add(coreSection(core)) }
    }

    private fun overviewSection(): Section = section("Overview") {
        text("Cores", fixed.cores.toString())
        text("Clusters", fixed.clusters.size.takeIf { it > 0 }?.toString())
        tokens(
            "Cluster layout",
            fixed.clusters.map { (freq, count) -> "$count × ${formatKiloHertz(freq)}" },
            raw = fixed.clusters.map { (freq, count) -> "$count × $freq" },
        )
        text("Architecture", System.getProperty("os.arch"))
        tokens("Supported ABIs", Build.SUPPORTED_ABIS.toList())
        flag("64-bit", Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())
        text("SoC", sinceApi(31) { socName() })
        attempt("Hardware") { fixed.cpuinfo["Hardware"] }
        val implementer = fixed.cpuinfo["CPU implementer"]
        text("Implementer", implementer?.let { decodeImplementer(it) ?: it }, raw = implementer)
        tokens(
            "Core types",
            fixed.coreTypes.map { (part, count) -> "$count × ${decodePart(implementer, part) ?: part}" },
            raw = fixed.coreTypes.map { (part, count) -> "$count × $part" },
        )
        attempt("Revision") { fixed.cpuinfo["CPU revision"] }
    }

    /** `Build.SOC_*` report the literal string "unknown" when the vendor left them unset. */
    private fun socName(): String? = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
        .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        .distinct()
        .joinToString(" ")
        .takeIf(String::isNotBlank)

    /**
     * Its own full-width section: several dozen pills would otherwise dwarf the rest of Overview,
     * and a wider row fits more of them per line.
     */
    private fun featureSection(): Section = section("Instruction set", fullWidth = true) {
        tokens("Features", fixed.cpuinfo["Features"]?.split(' ')?.filter(String::isNotBlank))
    }

    private fun clockSection(): Section = section("Clocks") {
        (0 until fixed.cores).forEach { core ->
            val current = frequency(core, "scaling_cur_freq")
            val peak = fixed.maxFreq.getOrNull(core)
            when {
                // A bar only means something when the ceiling is known.
                current != null && peak != null && peak > 0L -> metric(
                    label = "Core $core",
                    used = current,
                    total = peak,
                    summary = "${formatKiloHertz(current)}  ·  of ${formatKiloHertz(peak)}",
                )

                else -> text(
                    "Core $core",
                    current?.let(::formatKiloHertz),
                    raw = current?.toString(),
                    missing = UnavailableReason.READ_FAILED,
                )
            }
        }
    }

    private fun coreSection(core: Int): Section =
        section("Core $core", initiallyExpanded = false) {
            // Raw form is the sysfs value: kHz, exactly as cpufreq reports it.
            val min = fixed.minFreq.getOrNull(core)
            val max = fixed.maxFreq.getOrNull(core)
            val current = frequency(core, "scaling_cur_freq")
            text("Minimum frequency", min?.let(::formatKiloHertz), raw = min?.toString(), missing = UnavailableReason.READ_FAILED)
            text("Maximum frequency", max?.let(::formatKiloHertz), raw = max?.toString(), missing = UnavailableReason.READ_FAILED)
            text("Current frequency", current?.let(::formatKiloHertz), raw = current?.toString(), missing = UnavailableReason.READ_FAILED)
            attempt("Governor") { readSysfs(cpufreqPath(core, "scaling_governor")) }
            // Which kernel driver owns the clock. `scmi` means the frequency request goes to
            // firmware on a separate controller rather than to a register this CPU writes.
            attempt("Scaling driver") { readSysfs(cpufreqPath(core, "scaling_driver")) }
            tokens(
                "Available governors",
                readSysfs(cpufreqPath(core, "scaling_available_governors"))
                    ?.split(' ')
                    ?.filter(String::isNotBlank),
            )
            flag("Online", readSysfs("$CPU_ROOT/cpu$core/online")?.let { it == "1" })
        }

    private fun frequency(core: Int, node: String): Long? =
        readSysfs(cpufreqPath(core, node))?.toLongOrNull()

    private fun cpufreqPath(core: Int, node: String) = "$CPU_ROOT/cpu$core/cpufreq/$node"

    /** Everything that cannot change while the process is alive. */
    private class FixedInfo(
        val cores: Int,
        val cpuinfo: Map<String, String>,
        val minFreq: List<Long?>,
        val maxFreq: List<Long?>,
        /** Peak clock to core count. Distinct peaks are what separate big cores from little ones. */
        val clusters: List<Pair<Long, Int>>,
        /** Raw part id (e.g. "0xd81") to core count, in first-seen order. */
        val coreTypes: List<Pair<String, Int>>,
    )

    private fun readFixed(): FixedInfo {
        val cores = Runtime.getRuntime().availableProcessors()
        val maxFreq = (0 until cores).map { frequency(it, "cpuinfo_max_freq") }
        val cpuinfo = readKeyedFile("/proc/cpuinfo")
        return FixedInfo(
            cores = cores,
            cpuinfo = cpuinfo,
            minFreq = (0 until cores).map { frequency(it, "cpuinfo_min_freq") },
            maxFreq = maxFreq,
            clusters = maxFreq.filterNotNull()
                .groupingBy { it }
                .eachCount()
                .toList()
                .sortedByDescending { (freq, _) -> freq },
            coreTypes = readCoreTypes(),
        )
    }

    /**
     * One "CPU part" line per core, read in order — a flattened key-value map would keep only the
     * last one and silently hide that big and little cores are different silicon.
     */
    private fun readCoreTypes(): List<Pair<String, Int>> = runCatching {
        val parts = java.io.File("/proc/cpuinfo").useLines { lines ->
            lines.filter { it.startsWith("CPU part") }
                .mapNotNull { it.substringAfter(':').trim().takeIf(String::isNotEmpty) }
                .toList()
        }
        val counts = LinkedHashMap<String, Int>()
        parts.forEach { counts.merge(it, 1, Int::plus) }
        counts.toList()
    }.getOrDefault(emptyList())

    /** MIDR implementer ids, as published in the kernel's cputype.h. Null when unrecognised. */
    private fun decodeImplementer(hex: String): String? =
        when (hex.removePrefix("0x").toIntOrNull(16)) {
            0x41 -> "ARM"
            0x48 -> "HiSilicon"
            0x4e -> "NVIDIA"
            0x51 -> "Qualcomm"
            0x53 -> "Samsung"
            0x61 -> "Apple"
            else -> null
        }

    /** ARM's published part ids plus Qualcomm's custom core. Null keeps the raw hex on screen. */
    private fun decodePart(implementer: String?, hex: String): String? {
        val part = hex.removePrefix("0x").toIntOrNull(16) ?: return null
        return when (implementer?.removePrefix("0x")?.toIntOrNull(16)) {
            0x41 -> when (part) {
                0xd03 -> "Cortex-A53"; 0xd05 -> "Cortex-A55"; 0xd07 -> "Cortex-A57"
                0xd08 -> "Cortex-A72"; 0xd09 -> "Cortex-A73"; 0xd0a -> "Cortex-A75"
                0xd0b -> "Cortex-A76"; 0xd0d -> "Cortex-A77"; 0xd41 -> "Cortex-A78"
                0xd44 -> "Cortex-X1"; 0xd46 -> "Cortex-A510"; 0xd47 -> "Cortex-A710"
                0xd48 -> "Cortex-X2"; 0xd4d -> "Cortex-A715"; 0xd4e -> "Cortex-X3"
                0xd80 -> "Cortex-A520"; 0xd81 -> "Cortex-A720"; 0xd82 -> "Cortex-X4"
                0xd85 -> "Cortex-X925"; 0xd87 -> "Cortex-A725"
                else -> null
            }
            0x51 -> if (part == 0x001) "Oryon" else null
            else -> null
        }
    }

    private companion object {
        const val CPU_ROOT = "/sys/devices/system/cpu"

        /** Clocks move fast enough that anything slower reads as a stuck display. */
        const val REFRESH_MILLIS = 1_000L
    }
}
