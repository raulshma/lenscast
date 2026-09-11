package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridViewPolicyTest {

    private fun fractions(style: GridStyle, axis: GridViewPolicy.Axis): List<Float> =
        GridViewPolicy.linesFor(style).filter { it.axis == axis }.map { it.fraction }

    // ── the line sets ──

    @Test
    fun `off draws nothing`() {
        assertTrue(GridViewPolicy.linesFor(GridStyle.OFF).isEmpty())
    }

    @Test
    fun `thirds draws one vertical and one horizontal line per third`() {
        val lines = GridViewPolicy.linesFor(GridStyle.GRID_3X3)

        assertEquals(4, lines.size)
        assertEquals(listOf(1f / 3f, 2f / 3f), fractions(GridStyle.GRID_3X3, GridViewPolicy.Axis.VERTICAL))
        assertEquals(listOf(1f / 3f, 2f / 3f), fractions(GridStyle.GRID_3X3, GridViewPolicy.Axis.HORIZONTAL))
    }

    @Test
    fun `quarters draws one vertical and one horizontal line per quarter`() {
        val lines = GridViewPolicy.linesFor(GridStyle.GRID_4X4)

        assertEquals(6, lines.size)
        assertEquals(listOf(0.25f, 0.5f, 0.75f), fractions(GridStyle.GRID_4X4, GridViewPolicy.Axis.VERTICAL))
        assertEquals(listOf(0.25f, 0.5f, 0.75f), fractions(GridStyle.GRID_4X4, GridViewPolicy.Axis.HORIZONTAL))
    }

    @Test
    fun `golden ratio draws the two golden splits on both axes`() {
        val lines = GridViewPolicy.linesFor(GridStyle.GOLDEN_RATIO)

        assertEquals(4, lines.size)
        assertEquals(
            listOf(GridViewPolicy.GOLDEN_RATIO_SMALL, GridViewPolicy.GOLDEN_RATIO_LARGE),
            fractions(GridStyle.GOLDEN_RATIO, GridViewPolicy.Axis.VERTICAL),
        )
        assertEquals(
            listOf(GridViewPolicy.GOLDEN_RATIO_SMALL, GridViewPolicy.GOLDEN_RATIO_LARGE),
            fractions(GridStyle.GOLDEN_RATIO, GridViewPolicy.Axis.HORIZONTAL),
        )
    }

    // ── the golden constants ──

    @Test
    fun `the golden splits complement each other to one`() {
        assertEquals(
            1f,
            GridViewPolicy.GOLDEN_RATIO_SMALL + GridViewPolicy.GOLDEN_RATIO_LARGE,
            1e-4f,
        )
        assertEquals(1f / 1.618034f, GridViewPolicy.GOLDEN_RATIO_LARGE, 1e-4f)
    }

    // ── the line shape ──

    @Test
    fun `every line carries a fraction inside the preview box`() {
        GridStyle.entries.forEach { style ->
            GridViewPolicy.linesFor(style).forEach { line ->
                assertTrue(line.fraction > 0f && line.fraction < 1f)
            }
        }
    }
}
