package com.raulshma.lenscast.capture

import com.raulshma.lenscast.camera.model.MotionZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEventPolicyGridTest {

    private val cols = MotionEventPolicy.GRID_COLS
    private val rows = MotionEventPolicy.GRID_ROWS

    private fun grid(): DoubleArray = DoubleArray(cols * rows) { 10.0 }

    private fun hotTile(col: Int, row: Int, value: Double = 30.0): DoubleArray =
        grid().also { it[row * cols + col] = value }

    @Test
    fun `warmup frames never fire`() {
        val verdict = MotionEventPolicy.evaluateGrid(
            lastGrid = grid(), currentGrid = hotTile(0, 0),
            zones = emptyList(), nowMs = 1_000, lastFireMs = 0,
            framesSeen = MotionEventPolicy.WARMUP_FRAMES - 1,
        )
        assertFalse(verdict.fire)
    }

    @Test
    fun `hot tile anywhere fires when no zones exist`() {
        val verdict = MotionEventPolicy.evaluateGrid(
            lastGrid = grid(), currentGrid = hotTile(cols - 1, rows - 1),
            zones = emptyList(), nowMs = 100_000, lastFireMs = 0,
            framesSeen = MotionEventPolicy.WARMUP_FRAMES,
        )
        assertTrue(verdict.fire)
        assertEquals(20.0, verdict.delta, 0.01)
    }

    @Test
    fun `motion outside the zone is ignored`() {
        val zones = listOf(MotionZone(x = 0f, y = 0f, width = 0.25f, height = 0.25f, enabled = true))
        // Bottom-right tile is hot but the zone covers only the top-left area.
        val verdict = MotionEventPolicy.evaluateGrid(
            lastGrid = grid(), currentGrid = hotTile(cols - 1, rows - 1),
            zones = zones, nowMs = 100_000, lastFireMs = 0,
            framesSeen = MotionEventPolicy.WARMUP_FRAMES,
        )
        assertFalse(verdict.fire)
    }

    @Test
    fun `motion inside the zone fires`() {
        val zones = listOf(MotionZone(x = 0.75f, y = 0.75f, width = 0.25f, height = 0.25f, enabled = true))
        val verdict = MotionEventPolicy.evaluateGrid(
            lastGrid = grid(), currentGrid = hotTile(cols - 1, rows - 1),
            zones = zones, nowMs = 100_000, lastFireMs = 0,
            framesSeen = MotionEventPolicy.WARMUP_FRAMES,
        )
        assertTrue(verdict.fire)
    }

    @Test
    fun `firing zone is attributed by label`() {
        val zones = listOf(
            MotionZone(label = "Doorway", x = 0.75f, y = 0.75f, width = 0.25f, height = 0.25f, enabled = true),
            MotionZone(label = "Window", x = 0f, y = 0f, width = 0.25f, height = 0.25f, enabled = true),
        )
        val verdict = MotionEventPolicy.evaluateGrid(
            lastGrid = grid(), currentGrid = hotTile(cols - 1, rows - 1),
            zones = zones, nowMs = 100_000, lastFireMs = 0,
            framesSeen = MotionEventPolicy.WARMUP_FRAMES,
        )
        assertTrue(verdict.fire)
        assertEquals(listOf("Doorway"), verdict.zones)
    }

    @Test
    fun `two hot tiles attribute both zones`() {
        val zones = listOf(
            MotionZone(label = "Doorway", x = 0.75f, y = 0.75f, width = 0.25f, height = 0.25f, enabled = true),
            MotionZone(label = "Window", x = 0f, y = 0f, width = 0.25f, height = 0.25f, enabled = true),
        )
        val current = hotTile(cols - 1, rows - 1).also { it[0] = 30.0 }
        val verdict = MotionEventPolicy.evaluateGrid(
            lastGrid = grid(), currentGrid = current,
            zones = zones, nowMs = 100_000, lastFireMs = 0,
            framesSeen = MotionEventPolicy.WARMUP_FRAMES,
        )
        assertTrue(verdict.fire)
        // Order follows zone scan order (top-left tile first), not label order.
        assertEquals(listOf("Window", "Doorway"), verdict.zones)
    }

    @Test
    fun `whole-frame fire with no zones attributes nothing`() {
        val verdict = MotionEventPolicy.evaluateGrid(
            lastGrid = grid(), currentGrid = hotTile(3, 3),
            zones = emptyList(), nowMs = 100_000, lastFireMs = 0,
            framesSeen = MotionEventPolicy.WARMUP_FRAMES,
        )
        assertTrue(verdict.fire)
        assertTrue(verdict.zones.isEmpty())
    }

    @Test
    fun `sub-threshold breach attributes no zone even when a tile is warm`() {
        val zones = listOf(MotionZone(label = "Window", x = 0f, y = 0f, width = 0.25f, height = 0.25f, enabled = true))
        val verdict = MotionEventPolicy.evaluateGrid(
            lastGrid = grid(), currentGrid = hotTile(0, 0, value = 16.0),
            zones = zones, nowMs = 100_000, lastFireMs = 0,
            threshold = 18.0, // delta 16 stays under the gate: no fire, no attribution
            framesSeen = MotionEventPolicy.WARMUP_FRAMES,
        )
        assertFalse(verdict.fire)
        assertTrue(verdict.zones.isEmpty())
    }

    @Test
    fun `disabled zones do not gate detection`() {
        val zones = listOf(MotionZone(x = 0f, y = 0f, width = 0.25f, height = 0.25f, enabled = false))
        val verdict = MotionEventPolicy.evaluateGrid(
            lastGrid = grid(), currentGrid = hotTile(cols - 1, rows - 1),
            zones = zones, nowMs = 100_000, lastFireMs = 0,
            framesSeen = MotionEventPolicy.WARMUP_FRAMES,
        )
        assertTrue(verdict.fire)
    }

    @Test
    fun `zone normalization clamps out-of-range rects`() {
        val zone = MotionZone.normalized(MotionZone(x = -0.5f, y = 0.2f, width = 2f, height = 0.1f))
        assertEquals(0f, zone.x)
        assertEquals(0.2f, zone.y)
        assertEquals(1f, zone.width)
        assertEquals(0.1f, zone.height)
    }

    @Test
    fun `sample overlap detects the corner tile`() {
        val zone = MotionZone(x = 0f, y = 0f, width = 0.3f, height = 0.3f)
        val tileW = 1f / cols
        val tileH = 1f / rows
        assertTrue(MotionZone.overlapsSample(zone, 0f, 0f, tileW, tileH))
        assertFalse(MotionZone.overlapsSample(zone, 1f - tileW, 1f - tileH, tileW, tileH))
    }
}
