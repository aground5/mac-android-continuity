package com.peri.hardwareinformation.data.collector

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section

/**
 * Radio capabilities rather than radio state.
 *
 * Everything here is a "can this device do X" query, which needs no runtime permission. The state
 * side — SSID, MAC, IMEI, signal strength — is deliberately absent: it would demand location or
 * phone permissions, and a hardware inventory should not have to ask. The few identity fields worth
 * naming are reported as [UnavailableReason.NEEDS_PERMISSION] so their absence is explained rather
 * than silent.
 */
class NetworkCollector(context: Context) : Collector {
    override val id = "network"
    override val title = "Network"
    override val icon = Icons.Filled.Wifi

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    override suspend fun sections(): List<Section> = listOf(
        section("Wi-Fi", fullWidth = true) {
            if (wifiManager == null) {
                unavailable("Supported", UnavailableReason.NOT_SUPPORTED)
            } else {
                flag("Supported", hasFeature(PackageManager.FEATURE_WIFI))
                flag("2.4 GHz band", sinceApi(31) { wifiManager.is24GHzBandSupported })
                flag("5 GHz band", wifiManager.is5GHzBandSupported)
                flag("6 GHz band", sinceApi(30) { wifiManager.is6GHzBandSupported })
                val standards = wifiStandards()
                tokens(
                    "Standards",
                    standards.map { (name, _) -> name },
                    raw = standards.map { (_, code) -> code.toString() },
                )
                flag("Wi-Fi Direct", hasFeature(PackageManager.FEATURE_WIFI_DIRECT))
                flag("Wi-Fi Aware", hasFeature(PackageManager.FEATURE_WIFI_AWARE))
                flag("Wi-Fi RTT ranging", hasFeature(PackageManager.FEATURE_WIFI_RTT))
                flag("WPA3 SAE", wifiManager.isWpa3SaeSupported)
                flag("WPA3 Suite-B", wifiManager.isWpa3SuiteBSupported)
                flag("Enhanced Open (OWE)", wifiManager.isEnhancedOpenSupported)
                flag("TDLS", wifiManager.isTdlsSupported)
                flag("Station/AP concurrency", sinceApi(30) { wifiManager.isStaApConcurrencySupported })
                unavailable("Current SSID", UnavailableReason.NEEDS_PERMISSION)
            }
        },
        section("Bluetooth") {
            if (bluetoothAdapter == null) {
                unavailable("Supported", UnavailableReason.NOT_SUPPORTED)
            } else {
                flag("Supported", hasFeature(PackageManager.FEATURE_BLUETOOTH))
                flag("Low Energy", hasFeature(PackageManager.FEATURE_BLUETOOTH_LE))
                flag("LE 2M PHY", bluetoothAdapter.isLe2MPhySupported)
                flag("LE Coded PHY", bluetoothAdapter.isLeCodedPhySupported)
                flag("LE extended advertising", bluetoothAdapter.isLeExtendedAdvertisingSupported)
                flag("LE periodic advertising", bluetoothAdapter.isLePeriodicAdvertisingSupported)
                flag("Multiple advertisement", bluetoothAdapter.isMultipleAdvertisementSupported)
                flag("Offloaded filtering", bluetoothAdapter.isOffloadedFilteringSupported)
                flag("Offloaded scan batching", bluetoothAdapter.isOffloadedScanBatchingSupported)
                // Adapter name and address both sit behind BLUETOOTH_CONNECT.
                unavailable("Adapter name", UnavailableReason.NEEDS_PERMISSION)
            }
        },
        section("Cellular") {
            flag("Telephony", hasFeature(PackageManager.FEATURE_TELEPHONY))
            flag("GSM", hasFeature(PackageManager.FEATURE_TELEPHONY_GSM))
            flag("CDMA", hasFeature(PackageManager.FEATURE_TELEPHONY_CDMA))
            flag("IMS", hasFeature(PackageManager.FEATURE_TELEPHONY_IMS))
            flag("eUICC", hasFeature(PackageManager.FEATURE_TELEPHONY_EUICC))
            flag("Mobile data", hasFeature(PackageManager.FEATURE_TELEPHONY_DATA))
            flag("Calling", hasFeature(PackageManager.FEATURE_TELEPHONY_CALLING))
            flag("Messaging", hasFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING))
            unavailable("IMEI", UnavailableReason.OS_RESTRICTED)
            unavailable("Operator", UnavailableReason.NEEDS_PERMISSION)
        },
        section("Other radios") {
            val nfc = NfcAdapter.getDefaultAdapter(appContext)
            flag("NFC", nfc != null)
            flag("NFC host card emulation", hasFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
            flag("Secure NFC", nfc?.isSecureNfcSupported)
            flag("UWB", hasFeature(PackageManager.FEATURE_UWB))
            flag("GPS", hasFeature(PackageManager.FEATURE_LOCATION_GPS))
            flag("Ethernet", hasFeature(PackageManager.FEATURE_ETHERNET))
            flag("USB host", hasFeature(PackageManager.FEATURE_USB_HOST))
            flag("USB accessory", hasFeature(PackageManager.FEATURE_USB_ACCESSORY))
        },
        // The one piece of network configuration an app may read without holding a network
        // permission, and the only row in this category that describes a choice rather than a
        // capability: everything above is what the radios can do, this is what the OS is doing.
        section("Private DNS") {
            val mode = globalSetting("private_dns_mode")
            text("Mode", mode?.let(::privateDnsName), raw = mode)
            text("Provider hostname", globalSetting("private_dns_specifier"))
        },
    )

    private fun globalSetting(name: String): String? =
        runCatching { Settings.Global.getString(appContext.contentResolver, name) }.getOrNull()

    /** The three values `ConnectivityManager` documents for the setting. */
    private fun privateDnsName(mode: String): String = when (mode) {
        "off" -> "Off"
        "opportunistic" -> "Opportunistic"
        "hostname" -> "Provider hostname"
        else -> mode
    }

    /**
     * `isWifiStandardSupported` arrived in API 30; below that the list is simply empty. Pairs the
     * generation name with the raw `ScanResult.WIFI_STANDARD_*` constant it came from.
     */
    private fun wifiStandards(): List<Pair<String, Int>> {
        val manager = wifiManager ?: return emptyList()
        val supported = { standard: Int ->
            sinceApi(30) { manager.isWifiStandardSupported(standard) } == true
        }
        return buildList {
            if (supported(ScanResult.WIFI_STANDARD_LEGACY)) add("802.11 a/b/g" to ScanResult.WIFI_STANDARD_LEGACY)
            if (supported(ScanResult.WIFI_STANDARD_11N)) add("Wi-Fi 4 (n)" to ScanResult.WIFI_STANDARD_11N)
            if (supported(ScanResult.WIFI_STANDARD_11AC)) add("Wi-Fi 5 (ac)" to ScanResult.WIFI_STANDARD_11AC)
            if (supported(ScanResult.WIFI_STANDARD_11AX)) add("Wi-Fi 6 (ax)" to ScanResult.WIFI_STANDARD_11AX)
            if (supported(ScanResult.WIFI_STANDARD_11AD)) add("WiGig (ad)" to ScanResult.WIFI_STANDARD_11AD)
            if (supported(ScanResult.WIFI_STANDARD_11BE)) add("Wi-Fi 7 (be)" to ScanResult.WIFI_STANDARD_11BE)
        }
    }

    private fun hasFeature(name: String) = appContext.packageManager.hasSystemFeature(name)
}
