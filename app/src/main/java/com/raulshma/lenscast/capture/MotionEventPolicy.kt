package com.raulshma.lenscast.capture

import com.raulshma.lenscast.camera.model.MotionZone

/**
 * Pure motion-event policy: frame-luma delta → trigger verdict with cooldown.
 * JVM-tested; [MotionDetector] keeps last grid + last-fire time.
 */
object MotionEventPolicy {
    /** Mean-absolute luma delta (0-255) that counts as motion at default sensitivity. */
    const val DEFAULT_THRESHOLD = 12.0
    /** Minimum ms between two motion events. */
    const val DEFAULT_COOLDOWN_MS = 10_000L
    /** Frames to skip after start before arming (exposure settles). */
    const val WARMUP_FRAMES = 10L
    /** Sensitivity ladder endpoints: 0 → MAX (deaf), 1 → MIN (eager). */
    const val SENSITIVITY_THRESHOLD_MAX = 24.0
    const val SENSITIVITY_THRESHOLD_MIN = 4.0
    /** Detection grid resolution: the frame is averaged into COLSxROWS tiles. */
    const val GRID_COLS = 8
    const val GRID_ROWS = 6

    /** Sensitivity (0..1, coerced) → luma-delta threshold. */
    fun thresholdFor(sensitivity01: Float): Double =
        SENSITIVITY_THRESHOLD_MAX -
            sensitivity01.coerceIn(0f, 1f) * (SENSITIVITY_THRESHOLD_MAX - SENSITIVITY_THRESHOLD_MIN)

    data class Verdict(val fire: Boolean, val delta: Double)

    fun evaluate(
        lastAvg: Double?,
        currentAvg: Double,
        nowMs: Long,
        lastFireMs: Long,
        threshold: Double = DEFAULT_THRESHOLD,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        framesSeen: Long = WARMUP_FRAMES,
    ): Verdict {
        if (lastAvg == null || framesSeen < WARMUP_FRAMES) return Verdict(false, 0.0)
        val delta = kotlin.math.abs(currentAvg - lastAvg)
        return gate(delta, nowMs, lastFireMs, threshold, cooldownMs)
    }

    /**
     * Zone-aware verdict over the tile grid: the delta is the max per-tile
     * luma delta across considered tiles — only tiles overlapping an enabled
     * [MotionZone] when zones exist, every tile otherwise. A single hot tile
     * inside a zone fires, which whole-frame averaging used to wash out.
     */
    fun evaluateGrid(
        lastGrid: DoubleArray?,
        currentGrid: DoubleArray,
        zones: List<MotionZone>,
        nowMs: Long,
        lastFireMs: Long,
        threshold: Double = DEFAULT_THRESHOLD,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        framesSeen: Long = WARMUP_FRAMES,
    ): Verdict {
        if (lastGrid == null || framesSeen < WARMUP_FRAMES) return Verdict(false, 0.0)
        if (lastGrid.size != currentGrid.size || currentGrid.isEmpty()) return Verdict(false, 0.0)
        val cols = GRID_COLS
        val rows = GRID_ROWS
        // Every zone disabled behaves like no zones: the whole frame detects.
        val activeZones = zones.filter { it.enabled }
        val tileW = 1f / cols
        val tileH = 1f / rows
        var maxDelta = 0.0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val index = row * cols + col
                if (index >= currentGrid.size) continue
                if (activeZones.isNotEmpty()) {
                    val sampleX = col * tileW
                    val sampleY = row * tileH
                    val considered = activeZones.any { zone ->
                        MotionZone.overlapsSample(zone, sampleX, sampleY, tileW, tileH)
                    }
                    if (!considered) continue
                }
                val delta = kotlin.math.abs(currentGrid[index] - lastGrid[index])
                if (delta > maxDelta) maxDelta = delta
            }
        }
        return gate(maxDelta, nowMs, lastFireMs, threshold, cooldownMs)
    }

    private fun gate(
        delta: Double,
        nowMs: Long,
        lastFireMs: Long,
        threshold: Double,
        cooldownMs: Long,
    ): Verdict {
        if (delta < threshold) return Verdict(false, delta)
        if (nowMs - lastFireMs < cooldownMs) return Verdict(false, delta)
        return Verdict(true, delta)
    }

    /** Cheap luma estimate: mean of sampled Y bytes (NV21 Y plane first). */
    fun lumaAverage(yuv: ByteArray, width: Int, height: Int, stride: Int = 32): Double {
        val ySize = width * height
        if (ySize <= 0 || yuv.isEmpty()) return 0.0
        val limit = minOf(ySize, yuv.size)
        var sum = 0L
        var n = 0L
        var i = 0
        while (i < limit) {
            sum += yuv[i].toInt() and 0xFF
            n++
            i += stride
        }
        return if (n == 0L) 0.0 else sum.toDouble() / n
    }

    /**
     * Per-tile luma averages over a [GRID_COLS]x[GRID_ROWS] grid, sampled
     * with the same cheap stride walk inside each tile's Y-plane sub-rectangle.
     */
    fun lumaGrid(
        yuv: ByteArray,
        width: Int,
        height: Int,
        cols: Int = GRID_COLS,
        rows: Int = GRID_ROWS,
        stride: Int = 32,
    ): DoubleArray {
        val grid = DoubleArray(cols * rows)
        if (width <= 0 || height <= 0 || yuv.isEmpty()) return grid
        val tileW = width / cols
        val tileH = height / rows
        if (tileW <= 0 || tileH <= 0) {
            grid[0] = lumaAverage(yuv, width, height, stride)
            return grid
        }
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val startX = col * tileW
                val startY = row * tileH
                var sum = 0L
                var n = 0L
                var y = startY
                while (y < startY + tileH) {
                    var x = startX
                    val rowStart = y * width
                    while (x < startX + tileW) {
                        val index = rowStart + x
                        if (index < yuv.size) {
                            sum += yuv[index].toInt() and 0xFF
                            n++
                        }
                        x += stride
                    }
                    y += 2
                }
                grid[row * cols + col] = if (n == 0L) 0.0 else sum.toDouble() / n
            }
        }
        return grid
    }
}
