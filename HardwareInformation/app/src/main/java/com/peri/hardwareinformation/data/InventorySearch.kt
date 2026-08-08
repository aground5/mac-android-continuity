package com.peri.hardwareinformation.data

import com.peri.hardwareinformation.data.model.Item
import com.peri.hardwareinformation.data.model.Value
import java.util.Locale

/** Which part of the tree the needle was found in. Drives result ordering. */
enum class MatchField { LABEL, VALUE, SECTION, CATEGORY }

/** A single matched item, carrying enough context to say where in the app it lives. */
data class SearchHit(
    val categoryId: String,
    val categoryTitle: String,
    val sectionTitle: String,
    val item: Item,
    val matchedOn: MatchField,
)

/**
 * Searches labels, values, section titles and category titles.
 *
 * Matching the two title levels is what makes "identity" or "clocks" work as queries — without it
 * the only way to reach a group is to already know a value inside it. A title match yields every item
 * under that title, which is the useful reading of "show me the Clocks section".
 *
 * Results are ordered by [MatchField], so a row whose own label matches always outranks one that came
 * along because its section happened to be named similarly. The sort is stable, so within a tier the
 * fixed category order is preserved.
 *
 * Flags and unavailable rows are matched by label and title only: their displayed words are chosen by
 * the UI layer, so matching them would mean matching a translation rather than the data.
 */
fun List<CollectedCategory>.searchInventory(query: String): List<SearchHit> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return emptyList()

    val hits = mutableListOf<SearchHit>()
    forEach { category ->
        val categoryMatches = category.title.containsNeedle(needle)
        category.sections.forEach { section ->
            val sectionMatches = section.title.containsNeedle(needle)
            section.items.forEach { item ->
                val field = when {
                    item.label.containsNeedle(needle) -> MatchField.LABEL
                    item.value.containsNeedle(needle) -> MatchField.VALUE
                    sectionMatches -> MatchField.SECTION
                    categoryMatches -> MatchField.CATEGORY
                    else -> null
                }
                if (field != null) {
                    hits += SearchHit(category.id, category.title, section.title, item, field)
                }
            }
        }
    }
    return hits.sortedBy { it.matchedOn.ordinal }
}

private fun String.containsNeedle(needle: String) = lowercase(Locale.ROOT).contains(needle)

// Raw forms are searched regardless of the current display mode: someone who knows the part id
// "0xd81" should find the row even while the screen shows "Cortex-A720".
private fun Value.containsNeedle(needle: String): Boolean = when (this) {
    is Value.Text -> text.containsNeedle(needle) || raw?.containsNeedle(needle) == true
    is Value.LongText -> text.containsNeedle(needle)
    is Value.Tokens ->
        tokens.any { it.containsNeedle(needle) } || raw?.any { it.containsNeedle(needle) } == true
    is Value.Metric -> summary.containsNeedle(needle)
    is Value.Flag, is Value.Unavailable -> false
}
