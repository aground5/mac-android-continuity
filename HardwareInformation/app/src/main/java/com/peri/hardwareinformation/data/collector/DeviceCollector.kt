package com.peri.hardwareinformation.data.collector

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section

/**
 * What this machine is: identity and SoC. Build and OS details live in [SystemCollector] so the two
 * categories never repeat each other.
 */
class DeviceCollector : Collector {
    override val id = "device"
    override val title = "Device"
    override val icon = Icons.Filled.Smartphone

    override suspend fun sections(): List<Section> = listOf(
        section("Identity") {
            // Build identity strings are lowercase codes ("samsung"); the readable form just
            // capitalises, and raw mode keeps the exact Build value.
            text("Manufacturer", Build.MANUFACTURER?.capitalised(), raw = Build.MANUFACTURER)
            text("Brand", Build.BRAND?.capitalised(), raw = Build.BRAND)
            text("Model", Build.MODEL)
            text("Device", Build.DEVICE)
            text("Product", Build.PRODUCT)
            text("Board", Build.BOARD)
            text("Hardware", Build.HARDWARE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                text("SKU", Build.SKU)
                text("ODM SKU", Build.ODM_SKU)
            } else {
                unavailable("SKU", UnavailableReason.NOT_SUPPORTED)
                unavailable("ODM SKU", UnavailableReason.NOT_SUPPORTED)
            }
            // Non-resettable identifiers have been unavailable to normal apps since Android 10,
            // and minSdk here is 29 — so this row is honest rather than hopeful.
            unavailable("Serial number", UnavailableReason.OS_RESTRICTED)
            unavailable("IMEI / MEID", UnavailableReason.OS_RESTRICTED)
        },
        section("SoC") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                text("Manufacturer", socVendor(Build.SOC_MANUFACTURER), raw = Build.SOC_MANUFACTURER)
                text("Model", Build.SOC_MODEL)
            } else {
                unavailable("Manufacturer", UnavailableReason.NOT_SUPPORTED)
                unavailable("Model", UnavailableReason.NOT_SUPPORTED)
            }
            text("Core count", Runtime.getRuntime().availableProcessors().toString())
            tokens("Supported ABIs", Build.SUPPORTED_ABIS.toList())
            tokens("64-bit ABIs", Build.SUPPORTED_64_BIT_ABIS.toList())
            tokens("32-bit ABIs", Build.SUPPORTED_32_BIT_ABIS.toList())
            attempt("Hardware (cpuinfo)") { readKeyedFile("/proc/cpuinfo")["Hardware"] }
        },
    )
}

/** "samsung" → "Samsung"; leaves anything already mixed-case (e.g. "OnePlus") untouched. */
private fun String.capitalised(): String =
    if (firstOrNull()?.isLowerCase() == true) replaceFirstChar { it.uppercase() } else this

/**
 * Vendors write `SOC_MANUFACTURER` as an internal abbreviation — Qualcomm ships "QTI" for
 * Qualcomm Technologies Inc. Anything unrecognised keeps the vendor's own spelling rather than
 * being guessed at.
 */
private fun socVendor(value: String?): String? = when (value?.trim()?.uppercase()) {
    null -> null
    "QTI", "QUALCOMM" -> "Qualcomm"
    "MEDIATEK", "MTK" -> "MediaTek"
    "SAMSUNG", "LSI" -> "Samsung"
    "GOOGLE" -> "Google"
    "UNISOC", "SPRD" -> "UNISOC"
    "HISILICON" -> "HiSilicon"
    else -> value
}
