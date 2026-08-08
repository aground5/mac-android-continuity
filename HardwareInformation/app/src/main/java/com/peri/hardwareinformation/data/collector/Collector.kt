package com.peri.hardwareinformation.data.collector

import androidx.compose.ui.graphics.vector.ImageVector
import com.peri.hardwareinformation.data.model.Section
import kotlinx.coroutines.flow.Flow

/**
 * One hardware domain. Implementations must never throw out of [sections] for a single unreadable
 * value — use [com.peri.hardwareinformation.data.model.SectionScope.attempt] instead, so one
 * missing sysfs node does not blank the whole category.
 */
interface Collector {
    val id: String
    val title: String

    /** Shown beside the title in the list pane. Material Icons, never a hand-drawn drawable. */
    val icon: ImageVector

    /** Values that do not change while the process lives. Collected once and cached. */
    suspend fun sections(): List<Section>

    /**
     * Non-null only for categories whose values change on screen (battery, clocks, free memory).
     * Collected while the category is visible and dropped shortly after it stops being observed.
     */
    fun live(): Flow<List<Section>>? = null
}
