package com.peri.hardwareinformation.data.collector

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.webkit.WebView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import com.peri.hardwareinformation.data.formatDuration
import com.peri.hardwareinformation.data.formatGrouped
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section
import java.util.Locale
import java.util.TimeZone

/**
 * The platform Google controls: the Android build running now, the kernel under it, the runtime
 * apps execute in, and the pieces of the platform that update on their own schedule.
 *
 * The vendor's image around it is [FirmwareCollector] and the trust layer is [SecurityCollector];
 * the three divide along the line Treble draws, so no row appears twice. Build fingerprints live in
 * `Firmware`, where they can be compared partition by partition.
 */
class SystemCollector(context: Context) : Collector {
    override val id = "system"
    override val title = "System"
    override val icon = Icons.Filled.SettingsIcon

    private val appContext = context.applicationContext

    override suspend fun sections(): List<Section> {
        val properties = readSystemProperties()

        return listOf(
            section("Android", fullWidth = true) {
                text("Version", Build.VERSION.RELEASE)
                text("API level", Build.VERSION.SDK_INT.toString())
                text("Codename", Build.VERSION.CODENAME)
                text("Build ID", Build.ID)
                text("Build type", Build.TYPE)
                text("Incremental", Build.VERSION.INCREMENTAL)
                tokens("Tags", Build.TAGS?.split(','))
            },
            section("Kernel") {
                attempt("Version") { System.getProperty("os.version") }
                attempt("Name") { System.getProperty("os.name") }
                text("Architecture", System.getProperty("os.arch"))
                text("Bootloader", Build.BOOTLOADER)
                // Raw forms are the millisecond counters the clock actually returns.
                val elapsed = SystemClock.elapsedRealtime()
                text("Uptime", formatDuration(elapsed), raw = elapsed.toString())
                val awake = SystemClock.uptimeMillis()
                text("Awake since boot", formatDuration(awake), raw = awake.toString())
                val boots = globalSetting(Settings.Global.BOOT_COUNT)
                text("Boot count", boots?.let { formatGrouped(it.toLong()) }, raw = boots?.toString())
            },
            section("Runtime") {
                attempt("VM name") { System.getProperty("java.vm.name") }
                attempt("VM version") { System.getProperty("java.vm.version") }
                attempt("Java version") { System.getProperty("java.version") }
                val zygote = properties["ro.zygote"]
                text("Zygote", zygote?.let(::zygoteName), raw = zygote)
                // Heap ceilings are deliberately absent here: `Memory` already reports them from
                // ActivityManager, which is the same numbers by a better route.
                flag("JIT", properties["dalvik.vm.usejit"]?.let { it == "true" })
                flag("Userfaultfd GC", properties["ro.dalvik.vm.enable_uffd_gc"]?.let { it == "true" })
                // Which micro-architecture ART tunes its generated code for. The key carries the
                // instruction set in the middle, so it is matched rather than spelled out.
                text("ISA variant", properties.entries.firstOrNull(::isIsaVariant)?.value)
                flag(
                    "Native bridge",
                    properties["ro.dalvik.vm.native.bridge"]?.let { it != "0" && it != "0.0" },
                )
                text("Target SDK", appContext.applicationInfo.targetSdkVersion.toString())
                text("Minimum SDK", appContext.applicationInfo.minSdkVersion.toString())
                flag("64-bit process", Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())
            },
            section("Locale") {
                val configuration = appContext.resources.configuration
                val locale = configuration.locales[0]
                text("Current locale", locale?.toLanguageTag())
                text("Display language", locale?.displayName, raw = locale?.toString())
                text("Locales configured", configuration.locales.size().toString())
                val zone = TimeZone.getDefault()
                text("Time zone", zone.id)
                text("Time zone name", zone.getDisplayName(Locale.getDefault()), raw = zone.id)
                text(
                    "Layout direction",
                    if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "RTL" else "LTR",
                    raw = configuration.layoutDirection.toString(),
                )
            },
            section("WebView") {
                val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
                if (webView == null) {
                    unavailable("Provider", UnavailableReason.READ_FAILED)
                } else {
                    text("Provider", webView.packageName)
                    text("Version", webView.versionName)
                }
            },
            section("Mainline modules", fullWidth = true) {
                val modules = installedModules()
                if (modules.isEmpty()) {
                    unavailable("Modules", UnavailableReason.NOT_SUPPORTED)
                } else {
                    text("Modules", formatGrouped(modules.size.toLong()), raw = modules.size.toString())
                    modules.forEach { (name, packageName) -> text(name, packageName) }
                }
            },
        )
    }

    /**
     * Google delivers parts of the OS as separately updatable modules, so this list says which
     * pieces of the platform can move without a full OS update. It needs no permission: a module
     * list is not the package list that `QUERY_ALL_PACKAGES` guards.
     *
     * The value is the package name, not the version. `ModuleInfo` carries no version and looking
     * one up per module was tried and abandoned: package-visibility rules hide most APEX packages
     * from a normal app, so on a Galaxy S25 only two of twenty-nine resolved, and the two that did
     * returned Mainline train identifiers ("aml_370549340") rather than anything a reader could
     * use. A column that was a version twice and a package name twenty-seven times is worse than a
     * column that is honestly one thing.
     *
     * Sorted by the name a user sees, not by package: package order groups by vendor prefix, which
     * is no ordering at all once every module starts with `com.google.android`.
     */
    private fun installedModules(): List<Pair<String, String>> = runCatching {
        appContext.packageManager.getInstalledModules(0)
            .mapNotNull { module ->
                val name = module.name?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                name to module.packageName.orEmpty()
            }
            .sortedBy { (name, _) -> name.lowercase(Locale.ROOT) }
    }.getOrDefault(emptyList())

    /** Reading `Settings.Global` needs no permission; only writing one does. */
    private fun globalSetting(name: String): Int? =
        runCatching { Settings.Global.getInt(appContext.contentResolver, name) }.getOrNull()

    private companion object {
        /** `dalvik.vm.isa.arm64.variant`, whatever the instruction set happens to be called. */
        fun isIsaVariant(entry: Map.Entry<String, String>): Boolean =
            entry.key.startsWith("dalvik.vm.isa.") && entry.key.endsWith(".variant")

        /**
         * The name states the bitness the device boots app processes with, and for the mixed modes
         * the order states which one is primary — the same reading the init scripts give it.
         */
        fun zygoteName(mode: String): String = when (mode) {
            "zygote32" -> "32-bit"
            "zygote64" -> "64-bit"
            "zygote32_64" -> "32-bit primary, 64-bit secondary"
            "zygote64_32" -> "64-bit primary, 32-bit secondary"
            else -> mode
        }
    }
}
