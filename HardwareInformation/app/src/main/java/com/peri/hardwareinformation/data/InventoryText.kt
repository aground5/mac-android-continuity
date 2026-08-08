package com.peri.hardwareinformation.data

import com.peri.hardwareinformation.data.model.Section
import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.Value

/**
 * The words for the value kinds the UI phrases rather than stores — flags and unavailable reasons.
 * Passed in so this file stays free of Android resources and can be unit tested, while the export
 * and the clipboard both read the same as the screen.
 */
data class ValueLabels(
    val yes: String,
    val no: String,
    val needsPermission: String,
    val osRestricted: String,
    val notSupported: String,
    val readFailed: String,
)

/**
 * One line of plain text for a value, identical to what the row shows. [raw] selects the machine
 * form where one exists; flags become the literal "true"/"false" there — deliberately unlocalized,
 * because raw mode shows code, not prose.
 */
fun Value.plainText(labels: ValueLabels, raw: Boolean = false): String = when (this) {
    is Value.Text -> if (raw) this.raw ?: text else text
    is Value.LongText -> text
    is Value.Tokens -> (if (raw) this.raw ?: tokens else tokens).joinToString(", ")
    is Value.Flag -> if (raw) value.toString() else if (value) labels.yes else labels.no
    is Value.Metric -> if (raw) rawSummary ?: summary else summary
    is Value.Unavailable -> when (reason) {
        UnavailableReason.NEEDS_PERMISSION -> labels.needsPermission
        UnavailableReason.OS_RESTRICTED -> labels.osRestricted
        UnavailableReason.NOT_SUPPORTED -> labels.notSupported
        UnavailableReason.READ_FAILED -> labels.readFailed
    }
}

/**
 * The machine form, but only when it adds something the readable form does not.
 *
 * A flag's "true" and an unavailable row's reason merely restate their own words, so including
 * them would put `[true]` on several hundred feature rows and bury the raw forms that do carry
 * information — a part id, a byte count, an enum constant.
 */
private fun Value.informativeRaw(): String? = when (this) {
    is Value.Text -> raw
    is Value.Tokens -> raw?.joinToString(", ")
    is Value.Metric -> rawSummary?.takeIf { it != summary }
    is Value.LongText, is Value.Flag, is Value.Unavailable -> null
}

/** A category and its collected sections, which is what both search and export consume. */
data class CollectedCategory(val id: String, val title: String, val sections: List<Section>)

/**
 * Renders the whole inventory as plain text.
 *
 * Labels are padded to a fixed column so the result stays readable in a monospaced viewer, which is
 * where a dump like this usually ends up — a bug report, a gist, a message to a vendor. Long values
 * are left unwrapped on purpose: re-flowing them would corrupt fingerprints and extension lists.
 *
 * Both value forms are emitted, readable first with the machine form in brackets after it. A file
 * headed for a vendor has to carry the exact constant, and the reader of that file cannot toggle
 * anything — so unlike the screen, the export does not choose between the two.
 */
fun renderInventory(
    heading: String,
    subtitle: List<String>,
    categories: List<CollectedCategory>,
    labels: ValueLabels,
): String = buildString {
    appendLine(heading)
    subtitle.filter(String::isNotBlank).forEach(::appendLine)

    categories.forEach { category ->
        appendLine()
        appendLine("=".repeat(RULE_WIDTH))
        appendLine(category.title)
        appendLine("=".repeat(RULE_WIDTH))
        category.sections.forEach { section ->
            appendLine()
            appendLine("[${section.title}]")
            section.items.forEach { item ->
                val readable = item.value.plainText(labels)
                val raw = item.value.informativeRaw()
                val value = if (raw == null) readable else "$readable  [$raw]"
                appendLine("  ${item.label.padEnd(LABEL_COLUMN)}  $value")
            }
        }
    }
}

private const val LABEL_COLUMN = 28
private const val RULE_WIDTH = 60
