package com.raulshma.lenscast.camera.model

import kotlin.math.abs

/**
 * The pure pro-tools frame analysis: one pass over the NV21 luma plane
 * computing the luma histogram, the zebra cells (over/under-exposure bands),
 * and the focus-peaking cells (simple luma gradient), plus the rotation
 * mapping that turns per-cell results into preview-normalized rects. The
 * analyzer runtime ([com.raulshma.lenscast.camera.ProToolsFrameAnalyzer])
 * owns only throttling and frame plumbing; every threshold and verdict is
 * decided here and JVM-tested.
 *
 * Input contract: the frame is a tightly packed NV21 buffer (Y plane first,
 * row stride == width) — exactly the shape [com.raulshma.lenscast.camera.
 * CameraService] produces for the motion-detection path.
 */
object ProToolsPolicy {

    /** Luma histogram resolution over the 0..255 range. */
    const val HISTOGRAM_BINS = 32

    /** Luma at or above this is blown out → over-exposure zebra. */
    const val ZEBRA_OVER_LUMA = 250

    /** Luma at or below this is crushed → under-exposure zebra. */
    const val ZEBRA_UNDER_LUMA = 100

    /** Adjacent-sample luma difference that marks an in-focus edge. */
    const val PEAKING_GRADIENT_LUMA = 50

    /** The zebra/peaking heat-grid resolution the overlay renders. */
    const val CELL_COLUMNS = 24
    const val CELL_ROWS = 14

    /** A cell draws a zebra rect only when at least this share of its samples sit in the band. */
    const val CELL_COVERAGE_MIN_RATIO = 0.25f

    /** A cell draws a peaking rect when at least this share of its samples carry a strong gradient. */
    const val PEAKING_CELL_MIN_RATIO = 0.08f

    /** Samples per axis per cell: enough to judge coverage, cheap enough for every frame. */
    private const val SAMPLES_PER_CELL_AXIS = 6

    /** Which tools one analysis pass computes. */
    data class Flags(
        val histogram: Boolean = false,
        val zebras: Boolean = false,
        val peaking: Boolean = false,
    ) {
        val any: Boolean get() = histogram || zebras || peaking
    }

    enum class CellKind { ZEBRA_OVER, ZEBRA_UNDER, PEAK }

    /** A heat rect in preview-normalized coordinates (after rotation mapping). */
    data class CellRect(
        val kind: CellKind,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    /** One pass's result; histogram is null when not requested. */
    data class ProToolsFrame(
        val histogram: IntArray?,
        val cells: List<CellRect>,
    )

    /** The sampling stride along one axis, derived from the cell resolution. */
    fun sampleStep(frameDimension: Int, cellCount: Int): Int =
        (frameDimension / (cellCount * SAMPLES_PER_CELL_AXIS)).coerceAtLeast(1)

    /**
     * One pass over the luma plane. Samples every [sampleStep] pixels,
     * accumulating per-cell zebra/peaking ratios and the global histogram in
     * the same loop; cells whose coverage ratio clears the tool's threshold
     * become [CellRect]s, rotated by [rotationDegrees] onto display space.
     */
    fun analyze(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        flags: Flags,
    ): ProToolsFrame {
        if (width <= 0 || height <= 0 || !flags.any) return ProToolsFrame(null, emptyList())

        val stepX = sampleStep(width, CELL_COLUMNS)
        val stepY = sampleStep(height, CELL_ROWS)
        val cellWidthPx = width / CELL_COLUMNS
        val cellHeightPx = height / CELL_ROWS
        if (cellWidthPx <= 0 || cellHeightPx <= 0) return ProToolsFrame(null, emptyList())

        val histogram = if (flags.histogram) IntArray(HISTOGRAM_BINS) else null
        val overCounts = if (flags.zebras) IntArray(CELL_COLUMNS * CELL_ROWS) else null
        val underCounts = if (flags.zebras) IntArray(CELL_COLUMNS * CELL_ROWS) else null
        val peakCounts = if (flags.peaking) IntArray(CELL_COLUMNS * CELL_ROWS) else null
        val sampleCounts = IntArray(CELL_COLUMNS * CELL_ROWS)

        // Previous sampled row's luma (for the vertical gradient term). Filled
        // with the "no sample yet" sentinel: 0 is a real luma, so a zeroed array
        // would invent a gradient on the first sampled row of every flat frame.
        val rowSamples = IntArray((width + stepX - 1) / stepX) { -1 }

        var y = 0
        while (y < height) {
            val rowBase = y * width
            val cellRow = (y / cellHeightPx).coerceAtMost(CELL_ROWS - 1)
            var colIndex = 0
            var leftLuma = -1
            var x = 0
            while (x < width) {
                val luma = nv21[rowBase + x].toInt() and 0xFF
                val cellCol = (x / cellWidthPx).coerceAtMost(CELL_COLUMNS - 1)
                val cell = cellRow * CELL_COLUMNS + cellCol
                sampleCounts[cell]++

                histogram?.let { it[luma * HISTOGRAM_BINS / 256]++ }
                if (overCounts != null && luma >= ZEBRA_OVER_LUMA) overCounts[cell]++
                if (underCounts != null && luma <= ZEBRA_UNDER_LUMA) underCounts[cell]++
                if (peakCounts != null) {
                    // Edge against the left and top sampled neighbors: a
                    // strong jump either way is an in-focus contrast edge.
                    val horizontal = leftLuma >= 0 && abs(luma - leftLuma) >= PEAKING_GRADIENT_LUMA
                    val vertical = rowSamples[colIndex] >= 0 &&
                        abs(luma - rowSamples[colIndex]) >= PEAKING_GRADIENT_LUMA
                    if (horizontal || vertical) peakCounts[cell]++
                }
                rowSamples[colIndex] = luma
                leftLuma = luma
                colIndex++
                x += stepX
            }
            y += stepY
        }

        val cells = buildList {
            for (cell in sampleCounts.indices) {
                val samples = sampleCounts[cell]
                if (samples == 0) continue
                val col = cell % CELL_COLUMNS
                val row = cell / CELL_COLUMNS
                val base = CellRect(
                    // Placeholder kind; the real kind is set below per flag.
                    kind = CellKind.ZEBRA_OVER,
                    left = col / CELL_COLUMNS.toFloat(),
                    top = row / CELL_ROWS.toFloat(),
                    right = (col + 1) / CELL_COLUMNS.toFloat(),
                    bottom = (row + 1) / CELL_ROWS.toFloat(),
                )
                if (overCounts != null && overCounts[cell].toFloat() / samples >= CELL_COVERAGE_MIN_RATIO) {
                    add(rotate(base.copy(kind = CellKind.ZEBRA_OVER), rotationDegrees))
                }
                if (underCounts != null && underCounts[cell].toFloat() / samples >= CELL_COVERAGE_MIN_RATIO) {
                    add(rotate(base.copy(kind = CellKind.ZEBRA_UNDER), rotationDegrees))
                }
                if (peakCounts != null && peakCounts[cell].toFloat() / samples >= PEAKING_CELL_MIN_RATIO) {
                    add(rotate(base.copy(kind = CellKind.PEAK), rotationDegrees))
                }
            }
        }
        return ProToolsFrame(histogram, cells)
    }

    /**
     * Maps a frame-normalized rect into display space for the sensor
     * rotation the analysis frames carry: 90/270 swap the axes, 180 mirrors
     * both. (The preview's exact aspect-fill may crop edges; the overlay
     * renders inside the preview box, so this mapping keeps cells on the
     * right region of the image.)
     */
    fun rotate(rect: CellRect, rotationDegrees: Int): CellRect {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return when (normalized) {
            0 -> rect
            90 -> CellRect(
                kind = rect.kind,
                left = 1f - rect.bottom,
                top = rect.left,
                right = 1f - rect.top,
                bottom = rect.right,
            )
            180 -> CellRect(
                kind = rect.kind,
                left = 1f - rect.right,
                top = 1f - rect.bottom,
                right = 1f - rect.left,
                bottom = 1f - rect.top,
            )
            270 -> CellRect(
                kind = rect.kind,
                left = rect.top,
                top = 1f - rect.right,
                right = rect.bottom,
                bottom = 1f - rect.left,
            )
            else -> rect
        }
    }
}
