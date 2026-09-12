package com.raulshma.lenscast.camera.model

/**
 * The pure photo-capture build decisions: the persisted quality/capture-mode/
 * RAW request plus the device's live RAW capability fold into one immutable
 * [PhotoCaptureConfig], and the plan answers what [com.raulshma.lenscast.
 * camera.CameraService]'s `ImageCapture.Builder` calls should be and whether
 * a change needs a rebind. CameraService only translates the plan into
 * CameraX — the decisions are JVM-tested.
 */
object PhotoCapturePlan {

    /** Photo JPEG quality bounds and default (the Settings Store descriptor references these). */
    const val PHOTO_JPEG_QUALITY_MIN = 60
    const val PHOTO_JPEG_QUALITY_MAX = 100
    const val PHOTO_JPEG_QUALITY_DEFAULT = 90

    /** The persisted photo-capture request. */
    data class PhotoCaptureConfig(
        val jpegQuality: Int = PHOTO_JPEG_QUALITY_DEFAULT,
        val maximizeQuality: Boolean = false,
        val rawRequested: Boolean = false,
        /**
         * The photo frame's aspect (16:9 default — the historical behavior;
         * 4:3 re-binds the ImageCapture onto the wider full-sensor-ish crop
         * through [PhotoAspectRatioPolicy.captureTargetSize]). Part of the
         * config, so an aspect flip rebinds through the same needsRebind rule.
         */
        val aspect: PhotoAspectRatio = PhotoAspectRatio.R16_9,
    )

    /** The image files a shot produces. */
    enum class PhotoOutputFormat { JPEG, RAW_JPEG }

    /** Quality clamped to the persistence bounds. */
    fun clampedQuality(jpegQuality: Int): Int =
        jpegQuality.coerceIn(PHOTO_JPEG_QUALITY_MIN, PHOTO_JPEG_QUALITY_MAX)

    /**
     * The config the builder actually uses: an unsupported RAW request folds
     * back to plain JPEG, everything else passes through (quality clamped).
     */
    fun effective(config: PhotoCaptureConfig, rawCaptureSupported: Boolean): PhotoCaptureConfig =
        config.copy(
            jpegQuality = clampedQuality(config.jpegQuality),
            rawRequested = config.rawRequested && rawCaptureSupported,
        )

    /** The builder's output format for the effective config. */
    fun outputFormat(effectiveConfig: PhotoCaptureConfig): PhotoOutputFormat =
        if (effectiveConfig.rawRequested) PhotoOutputFormat.RAW_JPEG else PhotoOutputFormat.JPEG

    /** The builder's capture mode: quality over latency when maximizing. */
    fun captureMode(effectiveConfig: PhotoCaptureConfig): CaptureMode =
        if (effectiveConfig.maximizeQuality) CaptureMode.MAXIMIZE_QUALITY
        else CaptureMode.MINIMIZE_LATENCY

    /** The CameraX capture-mode names, kept as pure data for the plan's tests. */
    enum class CaptureMode { MAXIMIZE_QUALITY, MINIMIZE_LATENCY }

    /**
     * Whether applying [next] over [previous] (the last config actually
     * bound) changes what the ImageCapture builder would produce — i.e.
     * whether the apply seam must trigger a rebind. A null [previous] on an
     * unbound camera is not a rebind: the next natural bind picks the config
     * up.
     */
    fun needsRebind(previous: PhotoCaptureConfig?, next: PhotoCaptureConfig): Boolean {
        val bound = previous ?: return false
        return bound != next
    }
}
