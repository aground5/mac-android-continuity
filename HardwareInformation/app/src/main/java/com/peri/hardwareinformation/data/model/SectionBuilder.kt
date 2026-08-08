package com.peri.hardwareinformation.data.model

import com.peri.hardwareinformation.data.formatBytes
import com.peri.hardwareinformation.data.formatPercent

/**
 * Guards against a zero or negative total before any division or clamping, so a failed read can
 * never turn into an exception inside a default argument.
 */
private fun byteSummary(used: Long, total: Long): String =
    if (total <= 0L) {
        "—"
    } else {
        "${formatBytes(used.coerceIn(0L, total))} / ${formatBytes(total)}  ·  " +
            formatPercent(used.coerceIn(0L, total), total)
    }

/**
 * Terse builder for collectors. Every entry point funnels missing data into
 * [Value.Unavailable] so a collector can never emit a blank or a literal "null".
 */
class SectionScope internal constructor() {
    private val items = mutableListOf<Item>()

    /**
     * Adds [value], or a [missing] row when it is null/blank/"unknown". [raw] is the machine form
     * for raw mode; it is dropped when identical to the readable text, so a row without a distinct
     * raw form renders the same in both modes.
     */
    fun text(
        label: String,
        value: String?,
        raw: String? = null,
        missing: UnavailableReason = UnavailableReason.NOT_SUPPORTED,
    ) {
        val readable = meaningful(value)
        if (readable == null) {
            unavailable(label, missing)
        } else {
            items += Item(label, Value.Text(readable, raw?.trim()?.takeIf { it.isNotEmpty() && it != readable }))
        }
    }

    fun longText(label: String, value: String?) = add(label, meaningful(value)?.let(Value::LongText))

    fun tokens(label: String, values: List<String>?, raw: List<String>? = null) {
        val shown = values?.mapNotNull(::meaningful)?.takeIf { it.isNotEmpty() }
        add(label, shown?.let { Value.Tokens(it, raw?.takeIf { r -> r.isNotEmpty() && r != it }) })
    }

    /** Prefer this over `text(label, flag.toString())`: "true"/"false" is code, not an answer. */
    fun flag(label: String, value: Boolean?) = add(label, value?.let(Value::Flag))

    /**
     * A used-of-total bar. [summary] defaults to a byte rendering, which is what most callers want;
     * pass it explicitly for clocks or anything else measured in other units.
     */
    fun metric(
        label: String,
        used: Long,
        total: Long,
        summary: String = byteSummary(used, total),
        rawSummary: String? = null,
    ) {
        if (total <= 0L) {
            unavailable(label, UnavailableReason.READ_FAILED)
        } else {
            val clamped = used.coerceIn(0L, total)
            items += Item(label, Value.Metric(clamped, total, summary, rawSummary ?: "$clamped / $total"))
        }
    }

    fun unavailable(label: String, reason: UnavailableReason) {
        items += Item(label, Value.Unavailable(reason))
    }

    /**
     * Reads a value that is expected to fail on some devices — sysfs nodes, reflective lookups.
     * A throw or a blank result becomes an [UnavailableReason.READ_FAILED] row instead of taking
     * the whole category down.
     */
    fun attempt(
        label: String,
        reason: UnavailableReason = UnavailableReason.READ_FAILED,
        read: () -> String?,
    ) {
        val value = runCatching(read).getOrNull()
        if (meaningful(value) == null) unavailable(label, reason) else text(label, value)
    }

    private fun add(label: String, value: Value?) {
        items += Item(label, value ?: Value.Unavailable(UnavailableReason.NOT_SUPPORTED))
    }

    internal fun items(): List<Item> = items.toList()

    private companion object {
        /** `Build.UNKNOWN` and friends carry no information, so they are treated as absent. */
        fun meaningful(value: String?): String? =
            value?.trim()?.takeUnless { it.isEmpty() || it.equals("unknown", ignoreCase = true) }
    }
}

fun section(
    title: String,
    initiallyExpanded: Boolean = true,
    fullWidth: Boolean = false,
    build: SectionScope.() -> Unit,
): Section = Section(title, SectionScope().apply(build).items(), initiallyExpanded, fullWidth)
