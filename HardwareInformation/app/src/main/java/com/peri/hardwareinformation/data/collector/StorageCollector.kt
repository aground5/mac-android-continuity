package com.peri.hardwareinformation.data.collector

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import com.peri.hardwareinformation.data.formatBytes
import com.peri.hardwareinformation.data.formatMarketedBytes
import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.SectionScope
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section

/**
 * Volumes as the platform sees them. The headline total comes from [StorageStatsManager] rather than
 * [StatFs] because that is the figure the device is sold with — `StatFs` reports the filesystem after
 * the vendor partitions are carved out, which is why a "256 GB" phone shows about 230 GB.
 */
class StorageCollector(context: Context) : Collector {
    override val id = "storage"
    override val title = "Storage"
    override val icon = Icons.Filled.Storage

    private val appContext = context.applicationContext
    private val storageManager = appContext.getSystemService(StorageManager::class.java)
    private val storageStats = appContext.getSystemService(StorageStatsManager::class.java)

    override suspend fun sections(): List<Section> {
        val volumes = storageManager.storageVolumes
        val total = runCatching { storageStats.getTotalBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
        val free = runCatching { storageStats.getFreeBytes(StorageManager.UUID_DEFAULT) }.getOrNull()

        return buildList {
            add(
                section("Overview", fullWidth = true) {
                    if (total != null && free != null) {
                        metric("In use", used = total - free, total = total)
                        // getTotalBytes is already vendor-rounded to the marketing size, but in a
                        // vendor-specific base; formatMarketedBytes undoes that base difference.
                        // Raw mode shows the vendor's exact byte figure, rounding and all.
                        text(
                            "Advertised capacity",
                            formatMarketedBytes(total) ?: formatBytes(total),
                            raw = total.toString(),
                        )
                        text("Free", formatBytes(free), raw = free.toString())
                    } else {
                        unavailable("In use", UnavailableReason.READ_FAILED)
                    }
                    text("Volumes", volumes.size.toString())
                    // The filesystem is a kernel choice, not a property of the flash: a device can
                    // ship the same storage with ext4 or f2fs, and `/` is read-only compressed
                    // erofs on current Samsung builds while `/data` is writable f2fs.
                    val mounts = mountedFilesystems()
                    val data = mounts["/data"]
                    text("Data filesystem", data?.let(::filesystemName), raw = data)
                    val root = mounts["/"]
                    text("System filesystem", root?.let(::filesystemName), raw = root)
                },
            )
            volumes.forEachIndexed { index, volume ->
                add(volumeSection(volume, index, expanded = volumes.size == 1))
            }
        }
    }

    /**
     * `/proc/mounts` keyed by mount point. Readable by an ordinary app, unlike most of `/proc`,
     * because the kernel filters the list down to what the caller's namespace can see.
     */
    private fun mountedFilesystems(): Map<String, String> = runCatching {
        java.io.File("/proc/mounts").useLines { lines ->
            lines.mapNotNull { line ->
                val fields = line.split(' ')
                if (fields.size < 3) null else fields[1] to fields[2]
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    /** Only the acronyms have a conventional capitalisation; anything else is left alone. */
    private fun filesystemName(type: String): String = when (type.lowercase(java.util.Locale.ROOT)) {
        "f2fs" -> "F2FS"
        "erofs" -> "EROFS"
        "exfat" -> "exFAT"
        "vfat" -> "FAT"
        "ntfs", "ntfs3" -> "NTFS"
        "fuse" -> "FUSE"
        else -> type
    }

    private fun volumeSection(volume: StorageVolume, index: Int, expanded: Boolean): Section {
        val name = volume.getDescription(appContext) ?: "Volume ${index + 1}"
        return section(name, initiallyExpanded = expanded) {
            text("State", volume.state)
            // Null uuid is the platform's way of saying "this is the built-in volume".
            text("UUID", volume.uuid ?: "—")
            flag("Primary", volume.isPrimary)
            flag("Removable", volume.isRemovable)
            flag("Emulated", volume.isEmulated)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                text("Path", volume.directory?.absolutePath)
                text("MediaStore volume", volume.mediaStoreVolumeName)
                statFsRows(volume.directory?.absolutePath)
            } else {
                statFsRows(null)
            }
        }
    }

    private fun SectionScope.statFsRows(path: String?) {
        val stat = path?.let { runCatching { StatFs(it) }.getOrNull() }
        if (stat == null) {
            attempt("Filesystem size") { null }
            return
        }
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        metric("Filesystem in use", used = totalBytes - availableBytes, total = totalBytes)
        text("Filesystem size", formatBytes(totalBytes), raw = totalBytes.toString())
        text("Available to apps", formatBytes(availableBytes), raw = availableBytes.toString())
        text("Block size", formatBytes(stat.blockSizeLong), raw = stat.blockSizeLong.toString())
    }
}
