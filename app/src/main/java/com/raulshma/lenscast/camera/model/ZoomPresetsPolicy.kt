package com.raulshma.lenscast.camera.model

import androidx.camera.core.CameraSelector
import java.util.Locale

/**
 * The pure zoom-preset knowledge behind the camera screen's preset chip row:
 * which snap targets exist, their labels, which one is active, and where an
 * arbitrary zoom ratio snaps to. Derived from the lens inventory's focal
 * lengths — the back lenses at/above 1x relative to the main (Wide-band)
 * lens, i.e. the "1x / 2x / 5x" tele ladder — and clamped into the effective
 * zoom range, so a preset never asks the camera for a ratio it cannot reach.
 * Sub-1x back lenses (the ultra-wide) are deliberately *not* presets: the
 * zoom range's floor is 1x ([CameraSettings.effectiveZoomRange]), and the
 * ultra-wide is a lens-switch target, not a zoom ratio.
 *
 * With no multi-lens information (a single lens, or focal lengths all zero —
 * the enumeration-failure fallback), the row degrades to the two ratios a
 * single-lens camera still meaningfully offers: 1x and the device max —
 * and with a 1x-only range even that collapses to one chip, which callers
 * render as "no row".
 */
object ZoomPresetsPolicy {

    /** Two zoom ratios count as "the same preset" within this epsilon. */
    const val ACTIVE_EPSILON = 0.01f

    /** The chip row renders only when at least this many presets exist. */
    const val MIN_PRESETS_FOR_ROW = 2

    /** Below this focal (mm) a back lens is the ultra-wide — never the 1x reference. */
    const val ULTRAWIDE_MAX_FOCAL_MM = 2.5f

    /** One snap target: the ratio to apply and the chip's label ("0.5x", "MAX"). */
    data class ZoomPreset(val ratio: Float, val label: String)

    /**
     * The widest back lens's focal length — the 1x reference. "Widest" is the
     * main (Wide-band) camera, not the ultra-wide: the chip ladder's 0.5x is
     * the ultra-wide *relative to* this lens (the camera-app convention).
     * Ultrawide exclusion follows [com.raulshma.lenscast.camera.LensInventory]'s
     * band (below 2.5 mm); when every back lens is ultrawide or zero, the
     * smallest positive focal is the only meaningful reference. Null with no
     * back lens info.
     */
    fun mainFocalLength(lenses: List<CameraLensInfo>): Float? {
        val back = lenses.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        val positive = back.map { it.focalLength }.filter { it > 0f }
        val nonUltrawide = positive.filter { it >= ULTRAWIDE_MAX_FOCAL_MM }
        return (nonUltrawide.ifEmpty { positive }).minOrNull()
    }

    /**
     * The preset ladder for a lens inventory and zoom range:
     *
     * - Multi-lens information present: one preset per distinct back-lens
     *   focal ratio (`focal / mainFocal`), ascending, clamped into
     *   `[zoomRange]` — a lens whose ratio exceeds the device ceiling becomes
     *   the MAX chip at the ceiling; duplicates after clamping collapse.
     * - No lens information (or a front-only inventory): `[1x, max]`, again
     *   collapsing when the ceiling is 1x.
     *
     * The returned list is ascending, deduped, and every ratio lies inside
     * [zoomRange]. The label is the ratio formatted the way
     * [QuickSettingCatalog.zoomLabel] formats it ("0.5x"); the last entry —
     * when it sits at (or was clamped to) the range ceiling and the raw
     * ladder's top overshot it — reads "MAX" instead, the camera-app
     * convention for "as far as this device zooms".
     */
    fun presets(
        lenses: List<CameraLensInfo>,
        zoomRange: ClosedFloatingPointRange<Float>,
    ): List<ZoomPreset> {
        val main = mainFocalLength(lenses)
        // No multi-lens information (single lens, front-only inventory, or a
        // failed enumeration): the two ratios a single-lens camera still
        // meaningfully offers — 1x and the device ceiling ("MAX").
        val fallbackLadder = main == null
        val rawRatios = if (fallbackLadder) {
            listOf(1f, zoomRange.endInclusive)
        } else {
            lenses.asSequence()
                .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                .map { it.focalLength / main }
                // Sub-1x ratios (the ultra-wide relative to the main lens) are
                // outside the zoom range's 1x floor — a lens switch, never a
                // zoom preset.
                .filter { it >= 1f - ACTIVE_EPSILON }
                .toList()
                .ifEmpty { listOf(1f, zoomRange.endInclusive) }
        }

        val clamped = rawRatios
            .map { it.coerceIn(zoomRange.start, zoomRange.endInclusive) }
            .distinctBy { String.format(Locale.US, "%.2f", it) }
            .sorted()

        val topOvershot = rawRatios.max() > zoomRange.endInclusive + ACTIVE_EPSILON
        return clamped.mapIndexed { index, ratio ->
            val isLast = index == clamped.lastIndex
            ZoomPreset(
                ratio = ratio,
                label = if (isLast && (topOvershot || fallbackLadder)) {
                    "MAX"
                } else {
                    QuickSettingCatalog.zoomLabel(ratio)
                },
            )
        }
    }

    /** Whether [ratio] sits on [preset]'s target within the active epsilon. */
    fun isActive(preset: ZoomPreset, ratio: Float): Boolean =
        kotlin.math.abs(preset.ratio - ratio) < ACTIVE_EPSILON

    /**
     * Where an arbitrary zoom ratio snaps on a double-tap-style gesture:
     * the nearest preset strictly above [ratio], wrapping back to the first
     * (1x-class) preset at the top — the cycle every camera app uses. Null
     * when there is nothing to snap between (fewer than two presets).
     */
    fun nextPreset(presets: List<ZoomPreset>, ratio: Float): ZoomPreset? {
        if (presets.size < 2) return null
        val current = presets.indexOfFirst { isActive(it, ratio) }
        return when {
            current == -1 -> presets.firstOrNull { it.ratio > ratio + ACTIVE_EPSILON } ?: presets.first()
            current == presets.lastIndex -> presets.first()
            else -> presets[current + 1]
        }
    }
}
