package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProToolsPolicyTest {

    /** A luma-only NV21 buffer (the policy reads the Y plane exclusively). */
    private fun lumaBuffer(width: Int, height: Int, valueAt: (x: Int, y: Int) -> Int): ByteArray {
        val nv21 = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                nv21[y * width + x] = valueAt(x, y).toByte()
            }
        }
        return nv21
    }

    /** A size whose steps collapse to 1, so every pixel is sampled exactly. */
    private val width = 48   // CELL_COLUMNS (24) × 2px cells
    private val height = 28  // CELL_ROWS (14) × 2px cells
    private val totalSamples = width * height

    // ── the sampling stride ──

    @Test
    fun `the stride derives from the cell resolution`() {
        assertEquals(13, ProToolsPolicy.sampleStep(1920, ProToolsPolicy.CELL_COLUMNS))
        assertEquals(12, ProToolsPolicy.sampleStep(1080, ProToolsPolicy.CELL_ROWS))
    }

    @Test
    fun `a tiny frame never strides below one`() {
        assertEquals(1, ProToolsPolicy.sampleStep(24, ProToolsPolicy.CELL_COLUMNS))
        assertEquals(1, ProToolsPolicy.sampleStep(14, ProToolsPolicy.CELL_ROWS))
    }

    // ── the pass guards ──

    @Test
    fun `no tools enabled is an empty pass`() {
        val frame = ProToolsPolicy.analyze(
            lumaBuffer(width, height) { _, _ -> 128 }, width, height, 0, ProToolsPolicy.Flags(),
        )

        assertNull(frame.histogram)
        assertTrue(frame.cells.isEmpty())
    }

    @Test
    fun `a degenerate frame is an empty pass`() {
        val flags = ProToolsPolicy.Flags(histogram = true)
        val frame = ProToolsPolicy.analyze(ByteArray(0), 0, 0, 0, flags)

        assertNull(frame.histogram)
        assertTrue(frame.cells.isEmpty())
    }

    // ── the histogram ──

    @Test
    fun `a flat frame lands every sample in one luma bin`() {
        val frame = ProToolsPolicy.analyze(
            lumaBuffer(width, height) { _, _ -> 128 }, width, height, 0,
            ProToolsPolicy.Flags(histogram = true),
        )
        val histogram = frame.histogram!!

        assertEquals(ProToolsPolicy.HISTOGRAM_BINS, histogram.size)
        assertEquals(totalSamples, histogram[128 * ProToolsPolicy.HISTOGRAM_BINS / 256])
        assertEquals(
            totalSamples,
            histogram.sum(),
        )
    }

    @Test
    fun `a black-to-white split fills the extreme bins`() {
        val frame = ProToolsPolicy.analyze(
            lumaBuffer(width, height) { x, _ -> if (x < width / 2) 0 else 255 },
            width, height, 0,
            ProToolsPolicy.Flags(histogram = true),
        )
        val histogram = frame.histogram!!

        assertEquals(totalSamples / 2, histogram[0])
        assertEquals(
            totalSamples / 2,
            histogram[255 * ProToolsPolicy.HISTOGRAM_BINS / 256],
        )
    }

    // ── the zebras ──

    @Test
    fun `blown-out regions flag over-exposure cells only in the top half`() {
        val frame = ProToolsPolicy.analyze(
            lumaBuffer(width, height) { _, y -> if (y < height / 2) 255 else 128 },
            width, height, 0,
            ProToolsPolicy.Flags(zebras = true),
        )

        val over = frame.cells.filter { it.kind == ProToolsPolicy.CellKind.ZEBRA_OVER }
        assertTrue(over.isNotEmpty())
        assertTrue(over.all { it.top < 0.5f && it.bottom <= 0.5f })
        assertTrue(frame.cells.none { it.kind == ProToolsPolicy.CellKind.ZEBRA_UNDER })
    }

    @Test
    fun `crushed regions flag under-exposure cells only in the bottom half`() {
        val frame = ProToolsPolicy.analyze(
            lumaBuffer(width, height) { _, y -> if (y < height / 2) 128 else 10 },
            width, height, 0,
            ProToolsPolicy.Flags(zebras = true),
        )

        val under = frame.cells.filter { it.kind == ProToolsPolicy.CellKind.ZEBRA_UNDER }
        assertTrue(under.isNotEmpty())
        assertTrue(under.all { it.top >= 0.5f })
        assertTrue(frame.cells.none { it.kind == ProToolsPolicy.CellKind.ZEBRA_OVER })
    }

    // ── the focus peaking ──

    @Test
    fun `a high-contrast checkerboard flags peaking cells`() {
        val frame = ProToolsPolicy.analyze(
            lumaBuffer(width, height) { x, _ -> if ((x / 2) % 2 == 0) 0 else 255 },
            width, height, 0,
            ProToolsPolicy.Flags(peaking = true),
        )

        val peaks = frame.cells.filter { it.kind == ProToolsPolicy.CellKind.PEAK }
        assertTrue(peaks.isNotEmpty())
        assertTrue(peaks.all { it.right - it.left <= 1f / ProToolsPolicy.CELL_COLUMNS + 1e-4f })
    }

    @Test
    fun `a flat frame never peaks`() {
        val frame = ProToolsPolicy.analyze(
            lumaBuffer(width, height) { _, _ -> 128 }, width, height, 0,
            ProToolsPolicy.Flags(peaking = true),
        )

        assertTrue(frame.cells.isEmpty())
    }

    // ── the rotation mapping ──

    @Test
    fun `a zero rotation leaves the rect untouched`() {
        val rect = ProToolsPolicy.CellRect(ProToolsPolicy.CellKind.PEAK, 0.25f, 0.5f, 0.75f, 1f)

        assertEquals(rect, ProToolsPolicy.rotate(rect, 0))
        assertEquals(rect, ProToolsPolicy.rotate(rect, 360))
    }

    @Test
    fun `a 90-degree rotation swaps the axes into display space`() {
        val rect = ProToolsPolicy.CellRect(ProToolsPolicy.CellKind.ZEBRA_OVER, 0f, 0f, 1f, 0.5f)

        val rotated = ProToolsPolicy.rotate(rect, 90)

        assertEquals(ProToolsPolicy.CellKind.ZEBRA_OVER, rotated.kind)
        assertEquals(0.5f, rotated.left, 1e-4f)
        assertEquals(0f, rotated.top, 1e-4f)
        assertEquals(1f, rotated.right, 1e-4f)
        assertEquals(1f, rotated.bottom, 1e-4f)
    }

    @Test
    fun `a 180-degree rotation mirrors both axes`() {
        val rect = ProToolsPolicy.CellRect(ProToolsPolicy.CellKind.PEAK, 0.25f, 0f, 0.5f, 0.25f)

        val rotated = ProToolsPolicy.rotate(rect, 180)

        assertEquals(0.5f, rotated.left, 1e-4f)
        assertEquals(0.75f, rotated.top, 1e-4f)
        assertEquals(0.75f, rotated.right, 1e-4f)
        assertEquals(1f, rotated.bottom, 1e-4f)
    }
}
