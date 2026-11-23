package com.raulshma.lenscast.camera.model

/**
 * The values the bound camera ADVERTISES for the capture-request keys
 * [CameraControlPlan] sets — the camera2 `*_AVAILABLE_*` capability lists as
 * plain sets of Camera2 constants. The service reads them from the
 * `CameraCharacteristics` of the camera it just bound and hands them to
 * [CameraControlPlan.from], which neutralises a requested value the device
 * does not advertise *before* it reaches the capture request.
 *
 * This is the isolation 1794645's per-group capture applies were after: one
 * unsupported key must not cost every other control. That split reached the
 * goal by submitting group by group, and each submit replaced the previous
 * group's options (Camera2CameraControl's set semantics), so the app
 * silently lost AE, manual exposure and scene mode. Isolation belongs on the
 * *values* — validate against what the device advertises, then submit one
 * bundle — never on the submit.
 *
 * Null = unknown = UNRESTRICTED: the key keeps being sent exactly as it was
 * before this type existed. An absent capability list must never read as
 * "nothing supported" — that would neutralise a control that works.
 */
data class CaptureSupport(
    /** `CONTROL_AVAILABLE_SCENE_MODES`; `CONTROL_SCENE_MODE_DISABLED` is always valid, listed or not. */
    val sceneModes: Set<Int>? = null,

    /** `CONTROL_AE_AVAILABLE_MODES`. */
    val aeModes: Set<Int>? = null,

    /** `CONTROL_AWB_AVAILABLE_MODES`. */
    val awbModes: Set<Int>? = null,

    /** `CONTROL_AF_AVAILABLE_MODES`. */
    val afModes: Set<Int>? = null,

    /** `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`. */
    val opticalStabilizationModes: Set<Int>? = null,

    /** `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`. */
    val videoStabilizationModes: Set<Int>? = null,

    /** Whether `REQUEST_AVAILABLE_CAPABILITIES` lists `MANUAL_POST_PROCESSING`. */
    val manualPostProcessing: Boolean? = null,
) {
    /**
     * The values both sides advertise — what the logical camera and a
     * selected physical lens can each honour. A null side is unrestricted,
     * so the other side's list decides; both null stays unknown. Two known
     * lists simply intersect: a logical camera can advertise what only one
     * of its physical lenses has (optical stabilization on the zoom lens,
     * none on the wide one), so only the intersection is a value the
     * selected lens can honour.
     */
    fun intersect(other: CaptureSupport): CaptureSupport = CaptureSupport(
        sceneModes = sceneModes.intersectKnown(other.sceneModes),
        aeModes = aeModes.intersectKnown(other.aeModes),
        awbModes = awbModes.intersectKnown(other.awbModes),
        afModes = afModes.intersectKnown(other.afModes),
        opticalStabilizationModes = opticalStabilizationModes.intersectKnown(other.opticalStabilizationModes),
        videoStabilizationModes = videoStabilizationModes.intersectKnown(other.videoStabilizationModes),
        manualPostProcessing = when {
            manualPostProcessing == null -> other.manualPostProcessing
            other.manualPostProcessing == null -> manualPostProcessing
            else -> manualPostProcessing && other.manualPostProcessing
        },
    )

    companion object {
        /** Every list unknown: the plan is built exactly as it was before capability awareness. */
        val UNRESTRICTED = CaptureSupport()
    }
}

private fun <T> Set<T>?.intersectKnown(other: Set<T>?): Set<T>? = when {
    this == null -> other
    other == null -> this
    else -> this intersect other
}
