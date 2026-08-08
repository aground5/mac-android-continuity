package com.peri.hardwareinformation.ui.detail

import com.peri.hardwareinformation.data.model.Section

/** A section together with its position in the category, which is what keys its collapse state. */
typealias PlacedSection = IndexedValue<Section>

/**
 * Packs sections into rows of at most [columns].
 *
 * Packing is strictly positional: sections keep their order and are simply taken two at a time. A
 * masonry layout that fed each section to the shortest column would reorder the page whenever the
 * window resized, and a hardware list has to look the same every time it is opened.
 *
 * A section that asks for [Section.fullWidth] gets a row to itself, which is how a tall summary
 * avoids being paired against a one-line card and leaving a hole beside it.
 */
internal fun packSections(sections: List<Section>, columns: Int): List<List<PlacedSection>> {
    if (columns <= 1) return sections.withIndex().map(::listOf)

    val rows = mutableListOf<List<PlacedSection>>()
    var pending = mutableListOf<PlacedSection>()

    fun flush() {
        if (pending.isNotEmpty()) {
            rows += pending
            pending = mutableListOf()
        }
    }

    sections.withIndex().forEach { placed ->
        if (placed.value.fullWidth) {
            flush()
            rows += listOf(placed)
        } else {
            pending += placed
            if (pending.size == columns) flush()
        }
    }
    flush()
    return rows
}

/** True when the row is a single section that asked for the full width, rather than a short tail. */
internal fun List<PlacedSection>.spansRow(): Boolean = size == 1 && single().value.fullWidth
