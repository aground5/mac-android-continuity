package com.peri.hardwareinformation.data

import android.content.Context
import com.peri.hardwareinformation.data.collector.AudioCollector
import com.peri.hardwareinformation.data.collector.BatteryCollector
import com.peri.hardwareinformation.data.collector.CameraCollector
import com.peri.hardwareinformation.data.collector.CodecCollector
import com.peri.hardwareinformation.data.collector.Collector
import com.peri.hardwareinformation.data.collector.CpuCollector
import com.peri.hardwareinformation.data.collector.DeviceCollector
import com.peri.hardwareinformation.data.collector.DisplayCollector
import com.peri.hardwareinformation.data.collector.FeatureCollector
import com.peri.hardwareinformation.data.collector.FirmwareCollector
import com.peri.hardwareinformation.data.collector.GpuCollector
import com.peri.hardwareinformation.data.collector.MemoryCollector
import com.peri.hardwareinformation.data.collector.NetworkCollector
import com.peri.hardwareinformation.data.collector.SecurityCollector
import com.peri.hardwareinformation.data.collector.SensorCollector
import com.peri.hardwareinformation.data.collector.StorageCollector
import com.peri.hardwareinformation.data.collector.SystemCollector
import com.peri.hardwareinformation.data.model.CategoryRef

/**
 * The single registry of hardware categories. Adding a category is one line here plus one
 * [Collector] — no UI change, no navigation change.
 *
 * Order is deliberate and fixed: silicon first, then the things attached to it, then software. The
 * list must look identical on every launch so users can navigate it from memory.
 */
class HardwareRepository(context: Context) {
    private val appContext = context.applicationContext

    private val collectors: List<Collector> = listOf(
        DeviceCollector(),
        CpuCollector(),
        GpuCollector(appContext),
        MemoryCollector(appContext),
        StorageCollector(appContext),
        DisplayCollector(appContext),
        BatteryCollector(appContext),
        SensorCollector(appContext),
        CameraCollector(appContext),
        AudioCollector(appContext),
        CodecCollector(),
        NetworkCollector(appContext),
        SystemCollector(appContext),
        FirmwareCollector(),
        SecurityCollector(appContext),
        FeatureCollector(appContext),
    )

    /** Cheap to build, so the list pane never waits on collection. */
    val catalog: List<CategoryRef> = collectors.map { CategoryRef(it.id, it.title, it.icon) }

    fun collector(id: String): Collector? = collectors.firstOrNull { it.id == id }
}
