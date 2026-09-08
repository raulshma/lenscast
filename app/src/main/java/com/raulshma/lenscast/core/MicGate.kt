package com.raulshma.lenscast.core

import android.content.Context
import android.widget.Toast
import com.raulshma.lenscast.capture.model.RecordingConfig

/**
 * The one mic warn-and-degrade gate behind every audio-wanting feature
 * start: refresh the granted state, ask [MicAccess.startDecision], and
 * surface a degrade through the shared warning toast. One ladder,
 * parameterized by feature — no call site re-rolls the check, the decision,
 * or the toast text; the wording stays single-homed in
 * [MicAccess.degradedMessage].
 *
 * The refresh-then-consult discipline is the one behavior for every caller:
 * the granted state is freshly read at consult time (the camera screen
 * refreshes its exposed permission cache through [refreshGranted]; the
 * capture screen reads live through the default), and the freshly read
 * value is the one consulted.
 */
class MicGate(
    private val context: Context,
    private val refreshGranted: () -> Boolean = { MicAccess.isGranted(context) },
) {

    /**
     * Consults the mic for a feature start: Proceed, or Degrade with the
     * shared warning already surfaced through the sink. Never blocks —
     * warn-and-degrade is the mic policy; the caller starts either way.
     */
    fun consult(featureEnabled: Boolean, featureLabel: String): MicStartDecision {
        val granted = refreshGranted()
        return MicAccess.startDecision(
            featureEnabled = featureEnabled,
            granted = granted,
            featureLabel = featureLabel,
        ).also { decision ->
            if (decision is MicStartDecision.Degrade) {
                warn(decision.warning)
            }
        }
    }

    /**
     * The recording toggle's pre-start adapter — the shape both record
     * buttons hand to RecordingToggle.decide's `onBeforeStart` hook: consult
     * the mic with the draft's audio flag under [label], then return the
     * draft unchanged (warn-and-degrade never edits the config).
     */
    fun recordingConfigConsult(label: String): (RecordingConfig) -> RecordingConfig = { config ->
        consult(featureEnabled = config.includeAudio, featureLabel = label)
        config
    }

    /** The shared warning sink — the one toast every degrade surfaces through. */
    private fun warn(warning: String) {
        Toast.makeText(context, warning, Toast.LENGTH_SHORT).show()
    }
}
