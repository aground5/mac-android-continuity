package com.peri.hardwareinformation.data.collector

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import com.peri.hardwareinformation.data.formatMegapixels
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.section
import java.util.Locale

/**
 * Optics and sensor geometry for every camera the platform exposes.
 *
 * Reading [CameraCharacteristics] needs no CAMERA permission — the permission gates opening a camera,
 * not describing one — so this category is fully populated without ever prompting.
 */
class CameraCollector(context: Context) : Collector {
    override val id = "cameras"
    override val title = "Cameras"
    override val icon = Icons.Filled.PhotoCamera

    private val cameraManager = context.applicationContext.getSystemService(CameraManager::class.java)

    override suspend fun sections(): List<Section> {
        val ids = runCatching { cameraManager.cameraIdList.toList() }.getOrDefault(emptyList())
        val characteristics = ids.mapNotNull { id ->
            runCatching { id to cameraManager.getCameraCharacteristics(id) }.getOrNull()
        }

        return buildList {
            add(
                section("Overview", fullWidth = true) {
                    text("Cameras", ids.size.toString())
                    val facings = characteristics
                        .map { it.second.get(CameraCharacteristics.LENS_FACING) }
                        .distinct()
                    tokens(
                        "Facing",
                        facings.map(::facingName),
                        raw = facings.map { it?.toString() ?: "—" },
                    )
                },
            )
            characteristics.forEach { (id, camera) ->
                add(cameraSection(id, camera, expanded = characteristics.size == 1))
            }
        }
    }

    private fun cameraSection(
        id: String,
        camera: CameraCharacteristics,
        expanded: Boolean,
    ): Section {
        val facing = facingName(camera.get(CameraCharacteristics.LENS_FACING))
        return section("Camera $id · $facing", initiallyExpanded = expanded) {
            val pixelArray = camera.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            text("Resolution", pixelArray?.let { "${it.width} × ${it.height}" })
            text(
                "Sensor size",
                pixelArray?.let { formatMegapixels(it.width, it.height) },
                raw = pixelArray?.let { (it.width.toLong() * it.height).toString() },
            )
            val physical = camera.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            text(
                "Physical size",
                physical?.let { String.format(Locale.US, "%.2f × %.2f mm", it.width, it.height) },
                raw = physical?.let { "${it.width} × ${it.height}" },
            )
            val level = camera.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            text("Hardware level", hardwareLevelName(level), raw = level?.toString())
            val orientation = camera.get(CameraCharacteristics.SENSOR_ORIENTATION)
            text("Orientation", orientation?.let { "$it°" }, raw = orientation?.toString())
            val focalLengths = camera.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            tokens(
                "Focal lengths",
                focalLengths?.map { String.format(Locale.US, "%.2f mm", it) },
                raw = focalLengths?.map(Float::toString),
            )
            val apertures = camera.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            tokens(
                "Apertures",
                apertures?.map { String.format(Locale.US, "f/%.1f", it) },
                raw = apertures?.map(Float::toString),
            )
            text(
                "ISO range",
                camera.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    ?.let { "${it.lower} – ${it.upper}" },
            )
            // Nanoseconds at both ends; the short end is microseconds in practice.
            val exposure = camera.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            text(
                "Exposure time range",
                exposure?.let {
                    String.format(
                        Locale.US,
                        "%.0f µs – %.2f s",
                        it.lower / 1_000.0,
                        it.upper / 1_000_000_000.0,
                    )
                },
                raw = exposure?.let { "${it.lower} – ${it.upper}" },
            )
            // The API reports dioptres; the readable form is the distance they describe. Zero is
            // not a missing read — the docs define it as a fixed-focus lens, so it gets said.
            val dioptres = camera.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            text(
                "Minimum focus distance",
                when {
                    dioptres == null -> null
                    dioptres > 0f -> String.format(Locale.US, "%.1f cm", 100f / dioptres)
                    else -> "Fixed focus"
                },
                raw = dioptres?.toString(),
            )
            val zoom = camera.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            text(
                "Maximum digital zoom",
                zoom?.let { String.format(Locale.US, "%.1f×", it) },
                raw = zoom?.toString(),
            )
            flag("Flash", camera.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
            text("Largest JPEG", largestJpeg(camera))
            val capabilities = camera.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.toList()
                .orEmpty()
            tokens(
                "Capabilities",
                capabilities.map(::capabilityName),
                raw = capabilities.map(Int::toString),
            )
        }
    }

    private fun largestJpeg(camera: CameraCharacteristics): String? {
        val map = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val largest = map.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height }
            ?: return null
        return "${largest.width} × ${largest.height} (${formatMegapixels(largest.width, largest.height)})"
    }

    /** Covers every `REQUEST_AVAILABLE_CAPABILITIES_*` the SDK defines, checked against android.jar. */
    private fun capabilityName(capability: Int): String = when (capability) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "Backward compatible"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "Manual sensor"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "Manual post-processing"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "Private reprocessing"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "Read sensor settings"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "Burst capture"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV reprocessing"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "Depth output"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "High-speed video"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "Motion tracking"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "Logical multi-camera"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "Monochrome"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "Secure image data"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "System camera"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "Offline processing"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "Ultra-high resolution"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "Remosaic reprocessing"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "10-bit dynamic range"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "Stream use case"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "Colour space profiles"
        else -> "Capability $capability"
    }

    private fun hardwareLevelName(level: Int?): String? = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
        else -> null
    }

    private fun facingName(facing: Int?): String = when (facing) {
        CameraMetadata.LENS_FACING_FRONT -> "Front"
        CameraMetadata.LENS_FACING_BACK -> "Back"
        CameraMetadata.LENS_FACING_EXTERNAL -> "External"
        else -> "Unknown"
    }
}
