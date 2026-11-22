package com.raulshma.lenscast.companion.console

import androidx.annotation.StringRes
import com.raulshma.lenscast.companion.playback.DecodedFrame
import com.raulshma.lenscast.companion.playback.PlaybackRung

/**
 * The remote console backend's per-refresh poll cadence, one injectable
 * record (the [com.raulshma.lenscast.companion.session.SessionTimings] /
 * `LiveTimings` precedent: this package's tests run on real MockWebServer
 * threads, so a shrunken record is the determinism seam — never a virtual
 * clock):
 *
 * | Field               | Default  | Web origin                                    |
 * |---------------------|----------|-----------------------------------------------|
 * | [recordingPollMs]   | 5 000 ms | the dashboard's recording elapsed readout     |
 * | [capturePollMs]     | 5 000 ms | the dashboard's capture-page status readouts  |
 * | [automationPollMs]  | 30 000 ms| the automation engine's own poll cadence      |
 * | [settingsPollMs]    | 5 000 ms | the settings screens' document/status beat    |
 */
data class ConsoleTimings(
    val recordingPollMs: Long = 5_000L,
    /** The capture screen's interval-status beat (GET /api/capture/interval/status). */
    val capturePollMs: Long = 5_000L,
    /** The automation screen's state-document beat (GET /api/automation). */
    val automationPollMs: Long = com.raulshma.lenscast.core.StreamDefaults.AUTOMATION_POLL_SECONDS * 1_000L,
    /** The settings source's `GET /api/settings` (+ `/api/status` ranges) beat. */
    val settingsPollMs: Long = 5_000L,
)

/**
 * One-shot events the remote console emits outside its state flows. The
 * [com.raulshma.lenscast.camera.CameraConsole] interface is deliberately
 * event-free (it is the screen's render contract), so the notices a control
 * failure produces ride THIS channel on the concrete [DeviceConsoleViewModel];
 * the dashboard host (P3) collects it and renders a snackbar.
 */
sealed interface ConsoleEvent {

    /**
     * A transient message. [resId] names the string — resolved at the render
     * site so the state stays configuration-agnostic. The console reuses the
     * companion pairing/live lane's copy (forbidden / re-pair /
     * control-failed) so the wording stays single-homed.
     */
    data class Notice(@StringRes val resId: Int) : ConsoleEvent
}

/**
 * The active playback rung's phase — the preview stage's status overlay
 * verdict (re-homed from the retired companion live tab's model vocabulary,
 * whose video stage proved the states).
 */
enum class RungPhase { CONNECTING, LIVE, FAILED }

/**
 * The remote preview stage's playback snapshot: which ladder rung is bound,
 * that rung's phase, why the last rung failed (the overlay's error copy —
 * null while nothing has failed at the current rung), and the newest MJPEG
 * frame (only while the MJPEG rung is the live one). One immutable value per
 * write — the ViewModel only ever publishes atomic copies, and the stage
 * composable renders purely from it.
 */
data class ConsolePlayback(
    /** The ladder rung playback should be on right now. */
    val rung: PlaybackRung = PlaybackRung.WHEP,
    /** The active rung's own verdict (the stage's status overlay). */
    val phase: RungPhase = RungPhase.CONNECTING,
    /**
     * The failed rung's reason string (the transport's own message), kept
     * until the current rung reaches LIVE — the "retrying" overlay's why.
     */
    val failureReason: String? = null,
    /** The newest decoded MJPEG frame (null off the MJPEG rung). */
    val mjpegFrame: DecodedFrame? = null,
)
