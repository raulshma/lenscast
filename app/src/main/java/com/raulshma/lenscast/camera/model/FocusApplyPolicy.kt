package com.raulshma.lenscast.camera.model

/**
 * Whether applying [next] camera settings must re-fire the center AF/AE
 * metering. Settings applies run on every slider tick — twice, via the
 * responsive editor apply plus the Settings Applier — and re-firing
 * unconditionally would silently cancel a deliberate tap-to-focus. Only the
 * focus-relevant fields (focus mode, focus distance) count; zoom, exposure,
 * ISO, white balance, and frame rate changes must leave the metering alone.
 * Explicit tap-to-focus calls are user-initiated triggers and never pass
 * through here.
 */
object FocusApplyPolicy {

    /** First apply (null previous) establishes the metering; so does any focus-relevant change. */
    fun needsReapply(previous: CameraSettings?, next: CameraSettings): Boolean =
        previous == null ||
            previous.focusMode != next.focusMode ||
            previous.focusDistance != next.focusDistance
}
