package com.peri.hardwareinformation.ui.detail

import com.peri.hardwareinformation.data.model.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionLayoutTest {

    private fun section(title: String, fullWidth: Boolean = false) =
        Section(title = title, items = emptyList(), fullWidth = fullWidth)

    /** Titles per row, which is all the packing decides. */
    private fun packedTitles(sections: List<Section>, columns: Int): List<List<String>> =
        packSections(sections, columns).map { row -> row.map { it.value.title } }

    @Test
    fun `a single column gives every section its own row`() {
        val sections = listOf(section("A"), section("B"), section("C"))
        assertEquals(listOf(listOf("A"), listOf("B"), listOf("C")), packedTitles(sections, 1))
    }

    @Test
    fun `two columns take sections in order, two at a time`() {
        val sections = listOf(section("A"), section("B"), section("C"), section("D"))
        assertEquals(listOf(listOf("A", "B"), listOf("C", "D")), packedTitles(sections, 2))
    }

    @Test
    fun `an odd count leaves a single section in the last row`() {
        val sections = listOf(section("A"), section("B"), section("C"))
        assertEquals(listOf(listOf("A", "B"), listOf("C")), packedTitles(sections, 2))
    }

    @Test
    fun `a full-width section takes a row alone and does not disturb the order`() {
        val sections = listOf(
            section("Overview", fullWidth = true),
            section("A"),
            section("B"),
            section("C"),
        )
        assertEquals(
            listOf(listOf("Overview"), listOf("A", "B"), listOf("C")),
            packedTitles(sections, 2),
        )
    }

    @Test
    fun `a full-width section flushes a half-filled row rather than reordering around it`() {
        val sections = listOf(
            section("A"),
            section("Summary", fullWidth = true),
            section("B"),
            section("C"),
        )
        assertEquals(
            listOf(listOf("A"), listOf("Summary"), listOf("B", "C")),
            packedTitles(sections, 2),
        )
    }

    @Test
    fun `full width is ignored in a single column since every row already spans`() {
        val sections = listOf(section("Overview", fullWidth = true), section("A"))
        assertEquals(listOf(listOf("Overview"), listOf("A")), packedTitles(sections, 1))
    }

    @Test
    fun `indices survive packing so collapse state stays attached to the right section`() {
        val sections = listOf(
            section("Overview", fullWidth = true),
            section("A"),
            section("B"),
        )
        assertEquals(
            listOf(listOf(0), listOf(1, 2)),
            packSections(sections, 2).map { row -> row.map { it.index } },
        )
    }

    @Test
    fun `no sections produces no rows`() {
        assertEquals(emptyList<List<String>>(), packedTitles(emptyList(), 2))
    }

    @Test
    fun `a spanning row is distinguished from a short tail`() {
        val spanning = packSections(listOf(section("Overview", fullWidth = true)), 2).single()
        assertTrue(spanning.spansRow())

        val tail = packSections(listOf(section("A")), 2).single()
        assertFalse(tail.spansRow())
    }
}
