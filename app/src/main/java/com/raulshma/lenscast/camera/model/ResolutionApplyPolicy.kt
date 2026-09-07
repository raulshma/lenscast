package com.raulshma.lenscast.camera.model

/**
 * The pure rebind-now-vs-defer verdict for resolution changes. Changing the
 * capture resolution requires rebinding the camera use cases, which is only
 * possible while a camera demand is active (preview or headless streaming)
 * and no exclusive session (recording) holds the camera — a verdict that was
 * re-derived inline at three decision sites (settings apply, pending
 * resolution flush, activity resume). All three consult this one table now;
 * [com.raulshma.lenscast.camera.CameraService] keeps only the pending field,
 * the rebind call, and the resume hook.
 */
object ResolutionApplyPolicy {

    /** What to do with a resolution change (or a restore) at a decision site. */
    sealed interface ResolutionDecision {
        /**
         * Rebind the use cases now. [withResolutionChange] — true when a
         * resolution change rides along with the rebind (the apply path, or
         * the resume hook flushing a parked change); false when the rebind
         * merely restores the current resolution (resume without a parked
         * change).
         */
        data class RebindNow(val withResolutionChange: Boolean) : ResolutionDecision

        /** Park the change — no non-exclusive camera demand to rebind into. */
        data object Defer : ResolutionDecision
    }

    /**
     * The verdict: the camera is free exactly when a demand is active and no
     * exclusive session holds it. [resolutionChanged] does not alter that
     * freedom — it only tells the caller which rebind flavor applies.
     */
    fun decide(
        demandActive: Boolean,
        exclusiveActive: Boolean,
        resolutionChanged: Boolean,
    ): ResolutionDecision =
        if (demandActive && !exclusiveActive) {
            ResolutionDecision.RebindNow(withResolutionChange = resolutionChanged)
        } else {
            ResolutionDecision.Defer
        }
}
