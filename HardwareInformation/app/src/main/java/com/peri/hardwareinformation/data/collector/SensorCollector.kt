package com.peri.hardwareinformation.data.collector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import com.peri.hardwareinformation.data.formatGrouped
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.section
import java.util.Locale

/**
 * Sensors. This is the category that proves the "lots of entities" case: a phone reports 30–60
 * sensors, so each one becomes its own collapsed section rather than a bespoke table widget.
 */
class SensorCollector(context: Context) : Collector {
    override val id = "sensors"
    override val title = "Sensors"
    override val icon = Icons.Filled.Sensors

    private val sensorManager =
        context.applicationContext.getSystemService(SensorManager::class.java)

    override suspend fun sections(): List<Section> {
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        return buildList {
            add(
                // Spans the row: the one tall card in a category of otherwise uniform one-liners.
                section("Overview", fullWidth = true) {
                    text("Detected", sensors.size.toString())
                    text("Wake-up sensors", sensors.count { it.isWakeUpSensor }.toString())
                    tokens("Vendors", sensors.map { it.vendor }.distinct().sorted())
                },
            )
            sensors.forEach { sensor ->
                add(
                    section(sensor.name, initiallyExpanded = false) {
                        // Raw forms are the values as the framework hands them over: the int type
                        // and reporting-mode constants, and bare unscaled floats for the quantities.
                        val unit = unitFor(sensor.type)
                        text("Type", sensor.stringType, raw = sensor.type.toString())
                        text("Vendor", sensor.vendor)
                        text("Version", sensor.version.toString())
                        text("Id", sensor.id.toString())
                        text("Maximum range", quantity(sensor.maximumRange, unit), raw = sensor.maximumRange.toString())
                        text("Resolution", quantity(sensor.resolution, unit), raw = sensor.resolution.toString())
                        text("Power", "${format(sensor.power)} mA", raw = sensor.power.toString())
                        text("Minimum delay", "${formatGrouped(sensor.minDelay.toLong())} µs", raw = sensor.minDelay.toString())
                        text("Maximum delay", "${formatGrouped(sensor.maxDelay.toLong())} µs", raw = sensor.maxDelay.toString())
                        text(
                            "FIFO reserved events",
                            formatGrouped(sensor.fifoReservedEventCount.toLong()),
                            raw = sensor.fifoReservedEventCount.toString(),
                        )
                        text(
                            "FIFO max events",
                            formatGrouped(sensor.fifoMaxEventCount.toLong()),
                            raw = sensor.fifoMaxEventCount.toString(),
                        )
                        flag("Wake-up sensor", sensor.isWakeUpSensor)
                        flag("Dynamic sensor", sensor.isDynamicSensor)
                        text(
                            "Reporting mode",
                            reportingMode(sensor.reportingMode),
                            raw = sensor.reportingMode.toString(),
                        )
                    },
                )
            }
        }
    }

    private fun format(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString()
        else String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

    private fun quantity(value: Float, unit: String?): String =
        if (unit == null) format(value) else "${format(value)} $unit"

    /**
     * Range and resolution are raw floats whose meaning comes entirely from the sensor type — a "5"
     * is 5 µT on a magnetometer and 5 cm on a proximity sensor. Null for the genuinely unitless
     * types (rotation vectors are quaternion components, detectors report a bare 1.0) and for the
     * vendor-specific types above [Sensor.TYPE_DEVICE_PRIVATE_BASE], whose units are undocumented.
     */
    private fun unitFor(type: Int): String? = when (type) {
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_GRAVITY,
        -> "m/s²"

        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        -> "rad/s"

        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
        -> "µT"

        Sensor.TYPE_LIGHT -> "lx"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "%"
        Sensor.TYPE_PROXIMITY -> "cm"
        Sensor.TYPE_STEP_COUNTER -> "steps"
        Sensor.TYPE_HEART_RATE -> "bpm"
        Sensor.TYPE_HINGE_ANGLE -> "°"
        else -> null
    }

    private fun reportingMode(mode: Int): String = when (mode) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "Continuous"
        Sensor.REPORTING_MODE_ON_CHANGE -> "On change"
        Sensor.REPORTING_MODE_ONE_SHOT -> "One shot"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "Special trigger"
        else -> "Mode $mode"
    }
}
