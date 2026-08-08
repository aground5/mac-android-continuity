package com.peri.hardwareinformation.data.model

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The whole app renders exactly one data shape: a category is a list of sections, a section is a
 * list of label/value items, and a value is one of five kinds. Adding hardware information means
 * adding a [com.peri.hardwareinformation.data.collector.Collector] that produces [Section]s — it
 * never means writing a new screen or a new layout.
 */

/** Identifies a category without collecting it, so the list pane can render instantly. */
data class CategoryRef(
    val id: String,
    val title: String,
    val icon: ImageVector,
)

data class Section(
    val title: String,
    val items: List<Item>,
    /**
     * Categories that produce many sections (sensors, cameras, CPU cores) should collapse them so
     * the detail pane stays scannable.
     */
    val initiallyExpanded: Boolean = true,
    /**
     * Asks for the whole row on multi-column layouts. Set it on a summary that sits above a run of
     * uniform sections — a tall card paired against a one-line card leaves a hole beside it, and the
     * collector is the only thing that knows which section is the summary.
     */
    val fullWidth: Boolean = false,
)

data class Item(val label: String, val value: Value)

/** Why a value could not be shown. Rendered consistently, never as a blank or "null". */
enum class UnavailableReason {
    /** A runtime permission would unlock it. */
    NEEDS_PERMISSION,

    /** Android forbids non-privileged apps from reading it at all (IMEI, serial, …). */
    OS_RESTRICTED,

    /** This device or API level does not expose it. */
    NOT_SUPPORTED,

    /** The read was attempted and failed (missing sysfs node, SELinux denial, …). */
    READ_FAILED,
}

/**
 * Every value has two potential forms: the human-readable one that is always present, and an
 * optional machine/raw one — the enum code, the hex id, the unscaled unit — for the app-wide raw
 * mode. A null raw form means the readable text *is* the raw value (a sensor name, "Li-ion"), so
 * both modes display identically. The convention keeps raw↔readable mapping in one place per row
 * instead of ad-hoc "Readable (raw)" concatenations.
 */
sealed interface Value {
    /** A short value that usually fits beside its label. */
    data class Text(val text: String, val raw: String? = null) : Value

    /** A long value that is clamped and expandable: fingerprints, GL extension strings. */
    data class LongText(val text: String) : Value

    /** A set of short values rendered as pills: ABIs, supported codecs. */
    data class Tokens(val tokens: List<String>, val raw: List<String>? = null) : Value

    /**
     * A yes/no capability. Kept as a boolean rather than pre-worded text so the wording stays in
     * the UI layer where it can be translated — the same reason [Unavailable] carries a reason
     * instead of a sentence.
     */
    data class Flag(val value: Boolean) : Value

    /**
     * A used-of-total quantity rendered with a progress track.
     *
     * [summary] is the text shown above the bar, supplied already formatted — bytes, hertz or
     * anything else. Every other value kind carries display-ready text, and this one used to be the
     * exception that formatted itself in the UI, which quietly locked it to bytes.
     */
    data class Metric(
        val used: Long,
        val total: Long,
        val summary: String,
        /** Raw-mode counterpart of [summary]; the builder defaults it to the unscaled numbers. */
        val rawSummary: String? = null,
    ) : Value

    data class Unavailable(val reason: UnavailableReason) : Value
}
