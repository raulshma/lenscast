package com.raulshma.lenscast.camera.model

/**
 * The viewfinder grid style, persisted as its name (see the Settings Store's
 * `grid_style` descriptor). `OFF` draws nothing.
 */
enum class GridStyle { OFF, GRID_3X3, GRID_4X4, GOLDEN_RATIO }

/**
 * The pure grid-overlay geometry: which composition lines each [GridStyle]
 * draws, as normalized 0..1 fractions of the preview box. The composable only
 * maps [GridLine] fractions onto pixels — no line math lives in the screen.
 */
object GridViewPolicy {

    /** A composition line at a normalized position along one axis. */
    data class GridLine(val axis: Axis, val fraction: Float)

    enum class Axis { VERTICAL, HORIZONTAL }

    /** The two golden-ratio splits, 1/φ and 1 − 1/φ (fraction form). */
    const val GOLDEN_RATIO_SMALL = 0.381966f
    const val GOLDEN_RATIO_LARGE = 0.618034f

    private fun thirds(): List<GridLine> = lines(listOf(1f / 3f, 2f / 3f))

    private fun quarters(): List<GridLine> = lines(listOf(0.25f, 0.5f, 0.75f))

    private fun golden(): List<GridLine> = lines(listOf(GOLDEN_RATIO_SMALL, GOLDEN_RATIO_LARGE))

    private fun lines(fractions: List<Float>): List<GridLine> =
        fractions.flatMap { f -> listOf(GridLine(Axis.VERTICAL, f), GridLine(Axis.HORIZONTAL, f)) }

    /** The lines [style] draws; empty for [GridStyle.OFF]. */
    fun linesFor(style: GridStyle): List<GridLine> = when (style) {
        GridStyle.OFF -> emptyList()
        GridStyle.GRID_3X3 -> thirds()
        GridStyle.GRID_4X4 -> quarters()
        GridStyle.GOLDEN_RATIO -> golden()
    }
}
