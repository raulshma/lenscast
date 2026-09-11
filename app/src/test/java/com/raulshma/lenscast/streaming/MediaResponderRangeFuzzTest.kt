package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media-serving request parsers under hostile headers: a deterministic
 * corpus fuzzer over [MediaResponder.resolveRange] and
 * [MediaResponder.parseSnapshotQuery].
 *
 * ── The contract ──
 * resolveRange answers null (serve full content) or a pair satisfying
 * 0 <= start <= end <= totalSize-1 — never a negative body length, never an
 * exception. Every test runs under a timeout.
 *
 * ── The corpus ──
 * the browser shapes (suffix, open-ended, bounded), adversarial ones
 * (start past the file by 10^12, end past Long range, a-b-c, negatives,
 * empty, foreign units, junk), each against boundary file sizes.
 */
class MediaResponderRangeFuzzTest {

    private val totalSizes = listOf(1L, 2L, 1000L, 1L shl 40)

    // ── regression: an open-ended range past the file end produced a
    // negative Content-Length (end - start + 1 < 0) and a negative-length
    // body downstream ──

    @Test(timeout = 10_000)
    fun `regression - bytes-start-past-the-file clamps into range`() {
        val range = MediaResponder.resolveRange("bytes=999999999999-", totalSize = 1000)!!
        assertEquals(999L, range.start)
        assertEquals(999L, range.end)
        assertTrue(range.end - range.start + 1 > 0)
    }

    @Test(timeout = 10_000)
    fun `regression - an inverted range never yields a negative length`() {
        val range = MediaResponder.resolveRange("bytes=5-2", totalSize = 1000)!!
        assertEquals(5L, range.start)
        assertEquals(5L, range.end)
        assertEquals(1L, range.end - range.start + 1)
    }

    // ── the valid shapes stay exactly where they were ──

    @Test(timeout = 10_000)
    fun `the browser range shapes are unchanged`() {
        assertEquals(MediaResponder.ResolvedRange(0, 999), MediaResponder.resolveRange("bytes=0-", 1000))
        assertEquals(MediaResponder.ResolvedRange(100, 999), MediaResponder.resolveRange("bytes=100-", 1000))
        assertEquals(MediaResponder.ResolvedRange(100, 599), MediaResponder.resolveRange("bytes=100-599", 1000))
        assertEquals(MediaResponder.ResolvedRange(100, 999), MediaResponder.resolveRange("bytes=100-999999", 1000))
        // Suffix form: the parser reads it as start 0 / end 500 (documented
        // simplification — preserved as-is by the clamp fix).
        assertEquals(MediaResponder.ResolvedRange(0, 500), MediaResponder.resolveRange("bytes=-500", 1000))
        // The 2MB open-ended window cap.
        assertEquals(
            MediaResponder.ResolvedRange(0, 2L * 1024 * 1024 - 1),
            MediaResponder.resolveRange("bytes=0-", 1L shl 40),
        )
        assertNull(MediaResponder.resolveRange("bytes=100", 1000))
        assertNull(MediaResponder.resolveRange("item=0-5", 1000))
        assertNull(MediaResponder.resolveRange(null, 1000))
    }

    // ── the deterministic corpus ──

    @Test(timeout = 10_000)
    fun `corpus - every hostile range header answers null or an in-bounds pair`() {
        val hostile = listOf(
            "", "bytes=", "bytes=-", "bytes=a-b-c", "bytes=a-b", "bytes=abc-",
            "bytes=-abc", "bytes= 5 - 10 ", "bytes=+5-10", "bytes=0x10-20",
            "bytes=--5", "bytes=5--5", "bytes=-5-10", "bytes=999999999999-0",
            "bytes=0-99999999999999999999", "bytes=9223372036854775807-",
            "bytes=-9223372036854775808", "bytes=92233720368547758079-",
            "bytes=1e12-", "bytes=١٠-٢٠", "bytes=0-1-2-3", "BYTES=0-5",
            "bytes=0-5\r\nX-Injected: 1", "bytes=" + "9".repeat(400) + "-",
        )
        for (header in hostile) {
            for (totalSize in totalSizes + listOf(0L)) {
                val range = MediaResponder.resolveRange(header, totalSize)
                if (range != null) {
                    assertTrue("$header/$totalSize start", range.start >= 0)
                    assertTrue("$header/$totalSize order", range.start <= range.end)
                    assertTrue("$header/$totalSize end", range.end <= totalSize - 1)
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun `an empty file answers full content for any range header`() {
        for (header in listOf("bytes=0-", "bytes=0-100", "bytes=-5", "bytes=999-")) {
            assertNull(MediaResponder.resolveRange(header, totalSize = 0))
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - snapshot query flags over garbage strings answer booleans only`() {
        for (query in listOf(
            null, "", "?", "highres=1", "HIGHRES=1", "high_res=1&save=1",
            "highres=1highres=1", "save_to_disk=1&junk=%00", "?highres=1&&save=1&&",
            "x".repeat(100_000),
        )) {
            val options = MediaResponder.parseSnapshotQuery(query)
            assertTrue(options.highRes == (query?.contains("highres=1") == true || query?.contains("high_res=1") == true))
            assertTrue(options.saveToDisk == (query?.contains("save=1") == true || query?.contains("save_to_disk=1") == true))
        }
    }
}
