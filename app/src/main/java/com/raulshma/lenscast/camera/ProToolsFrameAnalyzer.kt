package com.raulshma.lenscast.camera

import android.util.Log
import com.raulshma.lenscast.camera.model.ProToolsPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pro-tools frame consumer: receives the camera's NV21 analysis frames
 * (the same flow motion detection rides), throttles to a display-sane
 * cadence, and hands each frame to the pure [ProToolsPolicy] pass that
 * computes the histogram, zebra cells, and peaking cells. The overlay
 * composables render the latest [ProToolsPolicy.ProToolsFrame]; all
 * thresholds and verdicts live in the policy.
 *
 * Not thread-safe by contract beyond [onFrame]: [onFrame] is invoked on the
 * camera analysis thread, everything else from the main thread.
 */
class ProToolsFrameAnalyzer(
    /** Reads the current tool flags — a cheap volatile-ish settings snapshot. */
    private val flags: () -> ProToolsPolicy.Flags,
) {

    /** The most recent analysis result; null until the first analyzed frame. */
    private val _frame = MutableStateFlow<ProToolsPolicy.ProToolsFrame?>(null)
    val frame: StateFlow<ProToolsPolicy.ProToolsFrame?> = _frame.asStateFlow()

    private var lastAnalysisMs = 0L

    /**
     * One frame in: throttled to [ANALYSIS_INTERVAL_MS] and skipped entirely
     * while no tool is enabled — the common case costs one flag read.
     */
    fun onFrame(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int, nowMs: Long) {
        val currentFlags = flags()
        if (!currentFlags.any) return
        if (nowMs - lastAnalysisMs < ANALYSIS_INTERVAL_MS) return
        lastAnalysisMs = nowMs
        try {
            _frame.value = ProToolsPolicy.analyze(nv21, width, height, rotationDegrees, currentFlags)
        } catch (e: Exception) {
            // One dropped overlay frame is invisible; never break the frame path.
            Log.w(TAG, "Pro-tools analysis failed", e)
        }
    }

    companion object {
        private const val TAG = "ProToolsAnalyzer"

        /** The overlay's compute rate — plenty for a heat display, cheap for the analysis thread. */
        const val ANALYSIS_INTERVAL_MS = 100L
    }
}
