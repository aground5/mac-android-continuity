package com.peri.hardwareinformation.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun `bytes below a kilobyte keep their exact value`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun `larger values step through binary units`() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("12.0 GB", formatBytes(12L * 1024 * 1024 * 1024))
        assertEquals("1.5 TB", formatBytes(1536L * 1024 * 1024 * 1024))
    }

    @Test
    fun `three-digit values drop the decimal so rows stay narrow`() {
        assertEquals("512 MB", formatBytes(512L * 1024 * 1024))
        assertEquals("768 KB", formatBytes(768L * 1024))
    }

    @Test
    fun `negative input is rendered as absent rather than as a negative size`() {
        assertEquals("—", formatBytes(-1))
    }

    @Test
    fun `counts get thousands separators so long digit runs stay readable`() {
        assertEquals("0", formatGrouped(0))
        assertEquals("999", formatGrouped(999))
        assertEquals("200,000", formatGrouped(200_000))
        assertEquals("1,234,567", formatGrouped(1_234_567))
    }

    @Test
    fun `marketed size is recovered from either vendor rounding base`() {
        // AOSP rounds a 256 GB disk to decimal, Samsung to binary; both must read "256 GB".
        assertEquals("256 GB", formatMarketedBytes(256_000_000_000L))
        assertEquals("256 GB", formatMarketedBytes(274_877_906_944L))
        assertEquals("512 GB", formatMarketedBytes(549_755_813_888L))
        assertEquals("1 TB", formatMarketedBytes(1_099_511_627_776L))
        assertEquals("64 GB", formatMarketedBytes(63_000_000_000L))
    }

    @Test
    fun `sizes too far from any marketed capacity give up rather than guess`() {
        // 350 GB sits between 256 and 512 — closer to 256 but 37% off, so no match.
        assertEquals(null, formatMarketedBytes(350_000_000_000L))
        assertEquals(null, formatMarketedBytes(0L))
        assertEquals(null, formatMarketedBytes(-5L))
    }

    @Test
    fun `packed capability numbers decode to the values the platform means`() {
        // Real readings from a Galaxy S25 and a Galaxy Tab S10+.
        assertEquals("1.3.0", formatVulkanVersion(4206592))
        assertEquals("2023-03-01", formatDeqpLevel(132580097))
        assertEquals("2024-03-01", formatDeqpLevel(132645633))
        assertEquals("3.2", formatGlEsVersion(0x30002))
        // Single-digit month and day keep their padding so the dates stay sortable.
        assertEquals("2024-01-09", formatDeqpLevel((2024 shl 16) or (1 shl 8) or 9))
    }

    @Test
    fun `percentages round to whole numbers and tolerate a zero total`() {
        assertEquals("50%", formatPercent(1, 2))
        assertEquals("0%", formatPercent(0, 100))
        assertEquals("100%", formatPercent(100, 100))
        assertEquals("—", formatPercent(1, 0))
    }
}
