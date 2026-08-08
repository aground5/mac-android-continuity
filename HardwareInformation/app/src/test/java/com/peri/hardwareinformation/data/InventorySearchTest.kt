package com.peri.hardwareinformation.data

import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventorySearchTest {

    private val inventory = listOf(
        CollectedCategory(
            id = "device",
            title = "Device",
            sections = listOf(
                section("Identity") {
                    text("Manufacturer", "samsung")
                    text("Model", "SM-X820")
                    unavailable("Serial number", UnavailableReason.OS_RESTRICTED)
                },
                section("SoC") {
                    text("Model", "MT6989")
                    tokens("Supported ABIs", listOf("arm64-v8a"))
                },
            ),
        ),
        CollectedCategory(
            id = "cpu",
            title = "CPU",
            sections = listOf(
                section("Clocks") {
                    metric("Core 0", 1_600_000, 2_000_000, summary = "1.60 GHz of 2.00 GHz")
                },
            ),
        ),
    )

    private fun labelsOf(query: String) = inventory.searchInventory(query).map { it.item.label }

    @Test
    fun `a blank query matches nothing rather than everything`() {
        assertEquals(emptyList<String>(), labelsOf(""))
        assertEquals(emptyList<String>(), labelsOf("   "))
    }

    @Test
    fun `labels match, case insensitively`() {
        assertEquals(listOf("Manufacturer"), labelsOf("manufact"))
        assertEquals(listOf("Manufacturer"), labelsOf("MANUFACT"))
    }

    @Test
    fun `values match`() {
        assertEquals(listOf("Model"), labelsOf("SM-X820"))
        assertEquals(listOf("Supported ABIs"), labelsOf("arm64"))
        assertEquals(listOf("Core 0"), labelsOf("1.60 GHz"))
    }

    @Test
    fun `a section title yields every item under it`() {
        val hits = inventory.searchInventory("identity")
        assertEquals(listOf("Manufacturer", "Model", "Serial number"), hits.map { it.item.label })
        assertTrue(hits.all { it.matchedOn == MatchField.SECTION })
    }

    @Test
    fun `a category title yields every item under it`() {
        val hits = inventory.searchInventory("cpu")
        assertEquals(listOf("Core 0"), hits.map { it.item.label })
        assertEquals(MatchField.CATEGORY, hits.single().matchedOn)
    }

    @Test
    fun `a match on a label outranks one inherited from a title`() {
        // The section-title match sits first in natural order, so the ranking has to lift the label
        // match above it rather than just preserving document order.
        val tree = listOf(
            CollectedCategory(
                id = "x",
                title = "X",
                sections = listOf(
                    section("Serial bus") { text("Lanes", "4") },
                    section("Identity") { text("Serial number", "n/a") },
                ),
            ),
        )
        val hits = tree.searchInventory("serial")
        assertEquals(listOf(MatchField.LABEL, MatchField.SECTION), hits.map { it.matchedOn })
        assertEquals(listOf("Serial number", "Lanes"), hits.map { it.item.label })
    }

    @Test
    fun `within one tier the fixed category order is preserved`() {
        val hits = inventory.searchInventory("model")
        assertEquals(listOf(MatchField.LABEL, MatchField.LABEL), hits.map { it.matchedOn })
        assertEquals(listOf("Identity", "SoC"), hits.map { it.sectionTitle })
    }

    @Test
    fun `hits carry the breadcrumb needed to show where they came from`() {
        val hit = inventory.searchInventory("MT6989").single()
        assertEquals("device", hit.categoryId)
        assertEquals("Device", hit.categoryTitle)
        assertEquals("SoC", hit.sectionTitle)
        assertEquals("Model", hit.item.label)
    }

    @Test
    fun `raw forms are searchable even though the screen may show the readable one`() {
        val tree = listOf(
            CollectedCategory(
                id = "cpu",
                title = "CPU",
                sections = listOf(
                    section("Overview") {
                        text("Implementer", "ARM", raw = "0x41")
                        tokens("Core types", listOf("4 × Cortex-A720"), raw = listOf("4 × 0xd81"))
                    },
                ),
            ),
        )
        assertEquals(listOf("Implementer"), tree.searchInventory("0x41").map { it.item.label })
        assertEquals(listOf("Core types"), tree.searchInventory("0xd81").map { it.item.label })
        // The readable side still matches too.
        assertEquals(listOf("Core types"), tree.searchInventory("cortex").map { it.item.label })
    }

    @Test
    fun `flags and unavailable rows are not matched on their displayed words`() {
        // "Restricted by Android" is chosen by the UI layer, so searching it would match a
        // translation rather than the data.
        assertEquals(emptyList<String>(), labelsOf("restricted"))
        // The label still works.
        assertEquals(listOf("Serial number"), labelsOf("serial"))
    }
}
