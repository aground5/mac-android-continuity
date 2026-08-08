package com.peri.hardwareinformation.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The builder is the contract every collector goes through, so these tests are what stop a blank,
 * a "null", or an "unknown" from ever reaching the UI.
 */
class SectionBuilderTest {

    @Test
    fun `a present value becomes text`() {
        val section = section("SoC") { text("Model", "SM8650") }
        assertEquals(Value.Text("SM8650"), section.items.single().value)
    }

    @Test
    fun `null blank and unknown all become unavailable rather than empty rows`() {
        val section = section("Identity") {
            text("Null", null)
            text("Blank", "   ")
            text("Unknown", "unknown")
            text("Unknown mixed case", "Unknown")
        }
        val expected = Value.Unavailable(UnavailableReason.NOT_SUPPORTED)
        assertEquals(List(4) { expected }, section.items.map(Item::value))
    }

    @Test
    fun `values are trimmed`() {
        val section = section("Build") { text("Tags", "  release-keys  ") }
        assertEquals(Value.Text("release-keys"), section.items.single().value)
    }

    @Test
    fun `token lists drop meaningless entries and collapse to unavailable when empty`() {
        val section = section("ABIs") {
            tokens("Mixed", listOf("arm64-v8a", "", "unknown", "armeabi-v7a"))
            tokens("All junk", listOf("", "unknown"))
            tokens("Absent", null)
        }
        assertEquals(
            listOf(
                Value.Tokens(listOf("arm64-v8a", "armeabi-v7a")),
                Value.Unavailable(UnavailableReason.NOT_SUPPORTED),
                Value.Unavailable(UnavailableReason.NOT_SUPPORTED),
            ),
            section.items.map(Item::value),
        )
    }

    @Test
    fun `flags stay boolean so the UI can word them, and null is still unavailable`() {
        val section = section("Sensor") {
            flag("Wake-up", true)
            flag("Dynamic", false)
            flag("Unknown", null)
        }
        assertEquals(
            listOf(
                Value.Flag(true),
                Value.Flag(false),
                Value.Unavailable(UnavailableReason.NOT_SUPPORTED),
            ),
            section.items.map(Item::value),
        )
    }

    @Test
    fun `a metric with no total is a failed read, not a division by zero`() {
        val section = section("RAM") { metric("In use", used = 5, total = 0) }
        assertEquals(
            Value.Unavailable(UnavailableReason.READ_FAILED),
            section.items.single().value,
        )
    }

    @Test
    fun `metric usage is clamped into range`() {
        val section = section("RAM") {
            metric("Over", used = 999, total = 100)
            metric("Under", used = -5, total = 100)
        }
        assertEquals(
            listOf(
                Value.Metric(100, 100, "100 B / 100 B  ·  100%", "100 / 100"),
                Value.Metric(0, 100, "0 B / 100 B  ·  0%", "0 / 100"),
            ),
            section.items.map(Item::value),
        )
    }

    @Test
    fun `a metric summarises itself as bytes unless told otherwise`() {
        val section = section("Mixed") {
            metric("Bytes", used = 512, total = 1024)
            metric("Clock", used = 1_800_000, total = 3_400_000, summary = "1.80 GHz of 3.40 GHz")
        }
        assertEquals(
            listOf(
                Value.Metric(512, 1024, "512 B / 1.0 KB  ·  50%", "512 / 1024"),
                Value.Metric(1_800_000, 3_400_000, "1.80 GHz of 3.40 GHz", "1800000 / 3400000"),
            ),
            section.items.map(Item::value),
        )
    }

    @Test
    fun `a negative total cannot blow up the summary before the guard runs`() {
        val section = section("Broken") { metric("Nonsense", used = 5, total = -1) }
        assertEquals(
            Value.Unavailable(UnavailableReason.READ_FAILED),
            section.items.single().value,
        )
    }

    @Test
    fun `attempt turns a throwing read into an unavailable row`() {
        val section = section("SoC") {
            attempt("Explodes") { error("no such sysfs node") }
            attempt("Returns null") { null }
            attempt("Custom reason", UnavailableReason.NEEDS_PERMISSION) { error("denied") }
            attempt("Succeeds") { "value" }
        }
        assertEquals(
            listOf(
                Value.Unavailable(UnavailableReason.READ_FAILED),
                Value.Unavailable(UnavailableReason.READ_FAILED),
                Value.Unavailable(UnavailableReason.NEEDS_PERMISSION),
                Value.Text("value"),
            ),
            section.items.map(Item::value),
        )
    }

    @Test
    fun `sections carry their expansion default`() {
        assertEquals(true, section("Overview") { }.initiallyExpanded)
        assertEquals(false, section("Accelerometer", initiallyExpanded = false) { }.initiallyExpanded)
    }

    @Test
    fun `a raw form identical to the readable text is dropped`() {
        val section = section("CPU") {
            text("Implementer", "ARM", raw = "0x41")
            text("Technology", "Li-ion", raw = "Li-ion")
            tokens("Standards", listOf("Wi-Fi 6 (ax)"), raw = listOf("6"))
            tokens("Features", listOf("aes", "sve"), raw = listOf("aes", "sve"))
        }
        assertEquals(
            listOf(
                Value.Text("ARM", raw = "0x41"),
                Value.Text("Li-ion", raw = null),
                Value.Tokens(listOf("Wi-Fi 6 (ax)"), raw = listOf("6")),
                Value.Tokens(listOf("aes", "sve"), raw = null),
            ),
            section.items.map(Item::value),
        )
    }

    @Test
    fun `a missing text value reports the reason the caller chose`() {
        val section = section("Clocks") {
            text("Core 0", null, missing = UnavailableReason.READ_FAILED)
            text("Core 1", null)
        }
        assertEquals(
            listOf(
                Value.Unavailable(UnavailableReason.READ_FAILED),
                Value.Unavailable(UnavailableReason.NOT_SUPPORTED),
            ),
            section.items.map(Item::value),
        )
    }

    @Test
    fun `metrics default their raw summary to the unscaled numbers`() {
        val section = section("RAM") {
            metric("In use", used = 512, total = 1024)
            metric("Clock", used = 1, total = 2, rawSummary = "custom")
        }
        val metrics = section.items.map { it.value as Value.Metric }
        assertEquals("512 / 1024", metrics[0].rawSummary)
        assertEquals("custom", metrics[1].rawSummary)
    }
}
