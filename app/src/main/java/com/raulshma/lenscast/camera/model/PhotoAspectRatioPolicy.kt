package com.raulshma.lenscast.camera.model

import android.util.Size

/**
 * The photo aspect ratio as a persisted setting: 16:9 (the historical
 * behavior — the photo use case rode the same 16:9 resolution selector as
 * the video stream) or 4:3 (the full-sensor-ish width most cameras natively
 * produce, wider than the 16:9 crop).
 */
enum class PhotoAspectRatio {
    R16_9, R4_3
}

/**
 * The pure mapping from the persisted photo-aspect setting plus the video
 * resolution onto the ImageCapture's `ResolutionStrategy` bound size.
 *
 * The math is pure over width/height ints ([captureBound]) so it is
 * JVM-testable without android.util.Size (whose stubbed equals returns
 * defaults under a plain-JVM test runner); [captureTargetSize] is the thin
 * Size adapter the camera service consumes.
 *
 * 16:9 keeps the historical behavior verbatim: the video resolution itself
 * is the photo bound. 4:3 re-uses the same resolution's *height* and widens
 * the frame to the 4:3 width (h × 4 / 3, rounded to the even number
 * encoders want) — the fallback ladder is CameraX's
 * `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER` over that bound, exactly the
 * 16:9 path's, so a device without the exact 4:3 size still lands the
 * closest sensor crop instead of failing the bind.
 */
object PhotoAspectRatioPolicy {

    /** The aspect's width-over-height ratio, as a Float for size math. */
    fun ratioOf(aspect: PhotoAspectRatio): Float = when (aspect) {
        PhotoAspectRatio.R16_9 -> 16f / 9f
        PhotoAspectRatio.R4_3 -> 4f / 3f
    }

    /**
     * The ImageCapture bound (width to height) for [aspect] at the video
     * resolution's tier, pure over ints.
     */
    fun captureBound(aspect: PhotoAspectRatio, videoWidth: Int, videoHeight: Int): Pair<Int, Int> =
        when (aspect) {
            PhotoAspectRatio.R16_9 -> videoWidth to videoHeight
            PhotoAspectRatio.R4_3 -> evenWidth(videoHeight * 4 / 3) to videoHeight
        }

    /** The ImageCapture bound size for [aspect] at the video resolution's tier. */
    fun captureTargetSize(aspect: PhotoAspectRatio, videoSize: Size): Size {
        val (width, height) = captureBound(aspect, videoSize.width, videoSize.height)
        return Size(width, height)
    }

    /** Even (encoder-friendly) width; an odd 4:3 target rounds down to the even neighbor. */
    internal fun evenWidth(width: Int): Int = if (width % 2 == 0) width else width - 1
}
