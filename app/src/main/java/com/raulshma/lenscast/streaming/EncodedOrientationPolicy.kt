package com.raulshma.lenscast.streaming

/**
 * Pure orientation mapping for the shared encoded-stream pipeline: matches the
 * upright dimensions used by MJPEG ([FramePipeline]), WHEP, WHIP, and
 * snapshots so landscape resolution tiers (`1280×720`, `960×720`, `640×480`,
 * …) swap to portrait (`720×1280`, `720×960`, `480×640`) whenever the
 * effective frame rotation is 90° or 270°, instead of aspect-fill cropping a
 * portrait frame into a landscape rectangle.
 */
object EncodedOrientationPolicy {

    /** True when [rotation] turns a landscape sensor frame into portrait (90° or 270°). */
    fun isPortraitRotation(rotation: Int): Boolean {
        val normalized = ((rotation % 360) + 360) % 360
        return normalized == 90 || normalized == 270
    }

    /** Returns `(tierHeight, tierWidth)` in portrait and `(tierWidth, tierHeight)` in landscape. */
    fun orientedDimensions(tierWidth: Int, tierHeight: Int, portrait: Boolean): Pair<Int, Int> =
        if (portrait) tierHeight to tierWidth else tierWidth to tierHeight

    fun orientedDimensions(tierWidth: Int, tierHeight: Int, rotation: Int): Pair<Int, Int> =
        orientedDimensions(tierWidth, tierHeight, isPortraitRotation(rotation))
}
