package com.peri.hardwareinformation.data

import com.peri.hardwareinformation.data.model.UnavailableReason
import com.peri.hardwareinformation.data.model.Value
import com.peri.hardwareinformation.data.model.section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryTextTest {

    private val labels = ValueLabels(
        yes = "Yes",
        no = "No",
        needsPermission = "Requires permission",
        osRestricted = "Restricted by Android",
        notSupported = "Not supported",
        readFailed = "Could not be read",
    )

    @Test
    fun `every value kind renders as one line of text`() {
        assertEquals("SM-X820", Value.Text("SM-X820").plainText(labels))
        assertEquals("a/b/c", Value.LongText("a/b/c").plainText(labels))
        assertEquals("arm64-v8a, armeabi-v7a", Value.Tokens(listOf("arm64-v8a", "armeabi-v7a")).plainText(labels))
        assertEquals("Yes", Value.Flag(true).plainText(labels))
        assertEquals("No", Value.Flag(false).plainText(labels))
        assertEquals("7.3 GB / 11.3 GB", Value.Metric(1, 2, "7.3 GB / 11.3 GB").plainText(labels))
    }

    @Test
    fun `unavailable rows are worded, never left blank`() {
        val worded = UnavailableReason.entries.map { Value.Unavailable(it).plainText(labels) }
        assertEquals(
            listOf("Requires permission", "Restricted by Android", "Not supported", "Could not be read"),
            worded,
        )
        assertTrue(worded.none(String::isBlank))
    }

    @Test
    fun `the export lays labels out in a fixed column`() {
        val rendered = renderInventory(
            heading = "Hardware Information",
            subtitle = listOf("SM-X820 · Android 16", ""),
            categories = listOf(
                CollectedCategory(
                    id = "device",
                    title = "Device",
                    sections = listOf(
                        section("Identity") {
                            text("Manufacturer", "samsung")
                            unavailable("Serial number", UnavailableReason.OS_RESTRICTED)
                        },
                    ),
                ),
            ),
            labels = labels,
        )

        val lines = rendered.lines()
        assertEquals("Hardware Information", lines[0])
        // The blank subtitle entry is dropped rather than printed as an empty line.
        assertEquals("SM-X820 · Android 16", lines[1])
        assertTrue(rendered.contains("Device"))
        assertTrue(rendered.contains("[Identity]"))
        assertTrue(rendered.contains("  Manufacturer                  samsung"))
        assertTrue(rendered.contains("  Serial number                 Restricted by Android"))
    }

    @Test
    fun `a label longer than the column still keeps a separating gap`() {
        val rendered = renderInventory(
            heading = "H",
            subtitle = emptyList(),
            categories = listOf(
                CollectedCategory(
                    "x",
                    "X",
                    listOf(section("S") { text("A very long label that overruns the column", "v") }),
                ),
            ),
            labels = labels,
        )
        assertTrue(rendered.contains("A very long label that overruns the column  v"))
    }

    @Test
    fun `an empty inventory still produces a usable header`() {
        val rendered = renderInventory("H", listOf("sub"), emptyList(), labels)
        assertEquals(listOf("H", "sub", ""), rendered.lines())
    }

    @Test
    fun `the export carries both forms, and only where the raw one adds something`() {
        val rendered = renderInventory(
            heading = "H",
            subtitle = emptyList(),
            categories = listOf(
                CollectedCategory(
                    "cpu",
                    "CPU",
                    listOf(
                        section("Overview") {
                            text("Implementer", "ARM", raw = "0x41")
                            text("Technology", "Li-ion")
                            flag("64-bit", true)
                            metric("In use", used = 512, total = 1024)
                        },
                    ),
                ),
            ),
            labels = labels,
        )
        assertTrue(rendered.contains("Implementer                   ARM  [0x41]"))
        // No raw form of its own, so no brackets.
        assertTrue(rendered.contains("Technology                    Li-ion\n"))
        // A flag's "true" only restates "Yes"; bracketing every one would bury the useful raws.
        assertTrue(rendered.contains("64-bit                        Yes\n"))
        assertTrue(rendered.contains("[512 / 1024]"))
    }

    @Test
    fun `raw mode selects the machine form and falls back to the readable one`() {
        assertEquals("0x41", Value.Text("ARM", raw = "0x41").plainText(labels, raw = true))
        assertEquals("ARM", Value.Text("ARM", raw = "0x41").plainText(labels, raw = false))
        assertEquals("Li-ion", Value.Text("Li-ion").plainText(labels, raw = true))
        assertEquals("6, 8", Value.Tokens(listOf("ax", "be"), raw = listOf("6", "8")).plainText(labels, raw = true))
        assertEquals("true", Value.Flag(true).plainText(labels, raw = true))
        assertEquals("512 / 1024", Value.Metric(512, 1024, "0.5 KB / 1.0 KB", "512 / 1024").plainText(labels, raw = true))
    }
}
