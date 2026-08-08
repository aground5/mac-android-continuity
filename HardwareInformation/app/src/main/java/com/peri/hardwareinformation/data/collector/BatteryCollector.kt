package com.peri.hardwareinformation.data.collector

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import com.peri.hardwareinformation.data.formatGrouped
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Charge, health and the electrical readings, refreshed while the category is on screen.
 *
 * Everything descriptive arrives on the sticky `ACTION_BATTERY_CHANGED` broadcast, which a null
 * receiver can read without any permission; the instantaneous current and charge counters come from
 * [BatteryManager] properties. Design capacity is the one figure Android never exposed — it lives in
 * a sysfs node that most vendors now block, so it is attempted and reported honestly when denied.
 */
class BatteryCollector(context: Context) : Collector {
    override val id = "battery"
    override val title = "Battery"
    override val icon = Icons.Filled.BatteryFull

    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)

    override suspend fun sections(): List<Section> = build()

    override fun live(): Flow<List<Section>> = flow {
        while (true) {
            emit(build())
            delay(REFRESH_MILLIS)
        }
    }

    private fun build(): List<Section> {
        val status = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        val statusCode = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pluggedCode = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val healthCode = status?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)

        return listOf(
            section("Charge", fullWidth = true) {
                if (level >= 0 && scale > 0) {
                    text("Level", "${level * 100 / scale}%", raw = "$level / $scale")
                } else {
                    unavailable("Level", UnavailableReason.READ_FAILED)
                }
                text("Status", statusCode?.let(::statusName), raw = statusCode?.toString())
                text("Power source", pluggedCode?.let(::pluggedName), raw = pluggedCode?.toString())
                text("Health", healthCode?.let(::healthName), raw = healthCode?.toString())
                flag("Present", status?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false))
                flag("Charging", batteryManager.isCharging)
            },
            section("Hardware") {
                text("Technology", status?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val cycles = status?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) ?: -1
                    text("Cycle count", cycles.takeIf { it >= 0 }?.toString())
                } else {
                    unavailable("Cycle count", UnavailableReason.NOT_SUPPORTED)
                }
                // Not part of any public API; blocked by SELinux on most recent devices.
                val design = readSysfs("/sys/class/power_supply/battery/charge_full_design")?.toLongOrNull()
                text(
                    "Design capacity",
                    design?.let { "${formatGrouped(it / 1_000)} mAh" },
                    raw = design?.toString(),
                    missing = UnavailableReason.READ_FAILED,
                )
                val full = readSysfs("/sys/class/power_supply/battery/charge_full")?.toLongOrNull()
                text(
                    "Full charge capacity",
                    full?.let { "${formatGrouped(it / 1_000)} mAh" },
                    raw = full?.toString(),
                    missing = UnavailableReason.READ_FAILED,
                )
            },
            section("Readings") {
                // Raw forms are the values exactly as the API hands them over: tenths of a degree,
                // millivolts, microampere-hours, microamperes.
                val temperature = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    ?.takeIf { it != Int.MIN_VALUE }
                text(
                    "Temperature",
                    temperature?.let { String.format(Locale.US, "%.1f °C", it / 10.0) },
                    raw = temperature?.toString(),
                )
                val voltage = (status?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1)
                    .takeIf { it > 0 }
                text(
                    "Voltage",
                    voltage?.let { String.format(Locale.US, "%.3f V", it / 1000.0) },
                    raw = voltage?.toString(),
                )
                val counter = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, zeroMeansAbsent = true)
                text("Charge counter", counter?.let { "${formatGrouped(it / 1_000)} mAh" }, raw = counter?.toString())
                val now = property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                text("Current now", now?.let { "${formatGrouped(it / 1_000)} mA" }, raw = now?.toString())
                val average = property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                text("Current average", average?.let { "${formatGrouped(it / 1_000)} mA" }, raw = average?.toString())
                val capacity = property(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                text("Reported capacity", capacity?.let { "$it%" }, raw = capacity?.toString())
            },
            // The software half of power: none of this is a property of the cell, it is what the
            // platform is currently doing about it. Refreshed with the rest of the category, so
            // thermal throttling appears here as it happens.
            section("Power management") {
                val power = appContext.getSystemService(PowerManager::class.java)
                flag("Power save mode", power?.isPowerSaveMode)
                flag("Doze mode", power?.isDeviceIdleMode)
                val thermal = sinceApi(29) { power?.currentThermalStatus }
                text("Thermal status", thermal?.let(::thermalName), raw = thermal?.toString())
                flag("Sustained performance mode", power?.isSustainedPerformanceModeSupported)
            },
        )
    }

    /**
     * The throttling severity the platform is asking apps to respond to, from
     * [PowerManager.THERMAL_STATUS_NONE] up to a shutdown that is already under way.
     */
    private fun thermalName(status: Int): String? = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "None"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutting down"
        else -> null
    }

    /**
     * Unsupported properties come back as [Int.MIN_VALUE]. Zero is only rejected where it can
     * never be a real reading — a charge counter of 0 µAh would be a physically empty cell, but
     * 0 µA of current is just a battery at rest, which this hardware genuinely reports.
     */
    private fun property(id: Int, zeroMeansAbsent: Boolean = false): Long? =
        batteryManager.getIntProperty(id)
            .takeIf { it != Int.MIN_VALUE && !(zeroMeansAbsent && it == 0) }
            ?.toLong()

    private fun statusName(status: Int): String? = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> null
        else -> null
    }

    private fun pluggedName(plugged: Int): String = when (plugged) {
        0 -> "Battery"
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        // Compile-time constant, so no API 33 guard is needed on minSdk 29.
        BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
        else -> "Source $plugged"
    }

    private fun healthName(health: Int): String? = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified failure"
        else -> null
    }

    private companion object {
        const val REFRESH_MILLIS = 2_000L
    }
}
