package com.raulshma.lenscast.automation

import android.util.Log
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.automation.schedule.AutomationActionRunner
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationSchedulePolicy
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import com.raulshma.lenscast.streaming.BootResumePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one execution seam for automation commands. [AutomationReceiver] is a
 * thin front-end (goAsync plumbing, the [AutomationIntentPolicy] wire
 * verdicts, the finish-once protocol), and the in-app
 * [com.raulshma.lenscast.automation.schedule.AutomationScheduler] is the
 * second front-end over the same operations — this class is the shared
 * executor both drive, so an automation command and a scheduler command can
 * never drift apart. Every operation routes through the app's shared seams —
 * StreamToggle (via [BootResumePolicy]), RecordingController,
 * PhotoCaptureManager, CameraService, SirenPlayer with the app's one
 * SirenAutoStop timer — never a second implementation.
 *
 * Deliberately stateless and cheap to construct: a front-end builds an
 * instance per command against [MainApplication]'s lazy seam graph. Policy
 * stays next to the wire: the receiver resolves the extras through
 * [AutomationIntentPolicy] and hands in already-decided verdicts (the
 * `enabled` toggle target, the clamped recording duration, the siren
 * auto-stop delay or null for "run until stopped"). This class executes; it
 * does not re-decide.
 */
class AutomationActionExecutor(private val app: MainApplication) : AutomationActionRunner {

    /**
     * The scheduler's entry point: [AutomationActionRunner] is implemented by
     * delegation so the receiver-facing methods stay the primary surface.
     */
    override suspend fun run(fired: List<AutomationSchedulePolicy.FiredRule>) = runFired(fired)

    /**
     * Executes the actions of one or more fired rules, in order, through the
     * app's shared seams. Every action id resolves through
     * [AutomationActionCatalog.typeFor]; an unknown id skips silently (the
     * schedule policy's catch-up filter already drops them — this is the
     * last gate). The rule's log line is one-per-firing, so the automation
     * log reads as a fire history.
     */
    suspend fun runFired(fired: List<AutomationSchedulePolicy.FiredRule>) {
        for (rule in fired) {
            Log.i(TAG, "Automation rule '${rule.rule.name}' (${rule.rule.id}) fired: " +
                rule.actions.joinToString(prefix = "[", postfix = "]") { it.type })
            for (action in rule.actions) {
                execute(action)
            }
        }
    }

    /**
     * Runs one action list directly — the Automation Test's arm and restore
     * both drive this (issue #18 item 3), so a test fire takes exactly the
     * execution path a scheduled fire takes, with no second implementation to
     * drift. Never journaled: the fire journal and cooldowns stay the
     * scheduler's, a test leaves no `lastFiredAt` trace.
     */
    suspend fun runActions(actions: List<AutomationActionSpec>) {
        for (action in actions) {
            execute(action)
        }
    }

    /**
     * One catalog action to its seam. Camera-valued actions
     * (camera_exposure/ev/zoom/fps, torch and night_vision) merge into the
     * PERSISTED [com.raulshma.lenscast.camera.model.CameraSettings] — the
     * Settings Applier applies them in place with no stream interruption.
     * That torch
     * mode is deliberately different from [setTorchLive]'s: the receiver's
     * SET_TORCH is LIVE deterrence (drive the camera, persist nothing — the
     * setting may disagree with reality so a toggle can compute its target),
     * while a scheduler `torch` action converges the persisted state, so a
     * device restart or a settings sync restores what the rule asked for.
     */
    private suspend fun execute(action: AutomationActionSpec) {
        when (AutomationActionCatalog.typeFor(action.type)?.id ?: return) {
            "camera_exposure", "camera_ev", "camera_zoom", "camera_fps", "torch", "night_vision" ->
                applyCameraSetting(action)
            "camera_lens" -> switchLens(action)
            // Sanitized BOOL params are always the canonical "true"/"false"
            // and required params always present; reading `!= "false"` keeps
            // a hand-built spec on the documented default (enabled).
            "stream" -> if (action.params["enabled"] != "false") startStream() else stopStream()
            "capture_photo" -> capturePhoto(
                onSaved = { _, _ -> },
                onError = { Log.w(TAG, "Automation capture failed: ${it.message}") },
            )
            "recording" -> if (action.params["enabled"] != "false") {
                startRecording(
                    AutomationIntentPolicy.recordingDurationSeconds(
                        action.params["duration_seconds"]?.toIntOrNull() ?: 0,
                    ),
                )
            } else {
                stopRecording()
            }
            "siren" -> if (action.params["enabled"] != "false") {
                // Absent duration = run until stopped (null delay — an
                // already-armed stop stays armed); present = the raw seconds
                // in milliseconds, unclamped like the receiver's verdict.
                setSiren(
                    true,
                    if ("duration_seconds" in action.params) {
                        (action.params["duration_seconds"]?.toIntOrNull() ?: 0) * 1_000L
                    } else {
                        null
                    },
                )
            } else {
                setSiren(false, null)
            }
            "detection" -> {
                action.params["motion_enabled"]?.let {
                    app.settingsDataStore.saveMotionDetectionEnabled(it == "true")
                }
                action.params["sound_enabled"]?.let {
                    app.settingsDataStore.saveSoundDetectionEnabled(it == "true")
                }
            }
        }
    }

    /**
     * Merges one camera action into the persisted settings; the applier does
     * the rest. The action type is the torch-touch verdict: a `torch` action
     * is an explicit torch write and passes through verbatim, every other
     * camera action leaves the pair untouched — and this save's applier
     * re-assert must carry the live fact, not the stale stored intent
     * ([CameraSettings.convergeTorch]), or a camera-only rule fire darkened
     * the deterrence coordinator's live-only lit LED.
     *
     * The merge base is the DISK truth (`settingsFromDisk`), never the
     * shared flow's `.value`: that flow lags in the two windows a camera
     * rule actually fires in — a fresh process answers the descriptor
     * default until DataStore's first read lands (the boot catch-up /
     * app_started fire), and a save's share-in hop has no completion edge,
     * so the NEXT action of the same rule run can still read the PREVIOUS
     * action's base. Either read produced a wholesale save built on the
     * stale base: the rule's own earlier camera writes and every untouched
     * field reverted to defaults, the applier faithfully applied both saves
     * (configured values briefly, then the reverted document), and every
     * later camera reset or restart kept re-applying the reverted store
     * (issue #18, 0.2.2). DataStore answers read-your-write within the
     * process, so sequential actions accumulate on disk; the same seam the
     * Automation Test's snapshot already uses.
     */
    private suspend fun applyCameraSetting(action: AutomationActionSpec) {
        val current = app.settingsDataStore.settingsFromDisk()
        val merged = AutomationSchedulePolicy.mergeCameraSettings(current, action)
        app.settingsDataStore.saveSettings(
            CameraSettings.convergeTorch(
                merged,
                touchedTorch = AutomationActionCatalog.typeFor(action.type)?.id == "torch",
                liveTorchOn = app.cameraService.isTorchOn(),
            ),
        )
    }

    /**
     * Lens switch on Main (the camera seam's dispatcher contract), clamped
     * into the live lens inventory: empty inventory and a missing index skip
     * silently rather than guessing, and a failure logs instead of throwing
     * into the rule loop.
     */
    private suspend fun switchLens(action: AutomationActionSpec) {
        withContext(Dispatchers.Main) {
            runCatching {
                val size = app.cameraService.availableLenses.value.size
                if (size == 0) return@runCatching
                val index = action.params["index"]?.toIntOrNull() ?: return@runCatching
                app.cameraService.selectLens(index.coerceIn(0, size - 1))
            }.onFailure { Log.w(TAG, "Automation lens switch failed: ${it.message}") }
        }
    }


    /** Starts streaming through the boot-resume seam (same verdict as the QS tile). */
    suspend fun startStream() {
        BootResumePolicy.execute(
            app.streamToggle,
            BootResumePolicy.tileStart(app.streamStateJournal.load()),
        )
    }

    suspend fun stopStream() {
        app.streamToggle.stopServer()
    }

    /**
     * Fire-and-forget photo capture. Returns false when the use case could
     * not be acquired — no callback will ever fire (a throw inside the
     * capture plumbing folds into the same verdict: a capture that never
     * started can never call back) — so the caller may close its window;
     * true when the callbacks own completion and the caller must stay open
     * until [onSaved] or [onError] fires.
     */
    fun capturePhoto(
        onSaved: (String?, Long?) -> Unit,
        onError: (Exception) -> Unit,
    ): Boolean = runCatching {
        app.photoCaptureManager.captureToGallery(
            onSaved = onSaved,
            onError = onError,
        )
    }.getOrNull() != null

    suspend fun startRecording(durationSeconds: Long) {
        app.recordingController.start(
            RecordingConfig(
                durationSeconds = durationSeconds,
                repeatIntervalSeconds = 0,
                quality = RecordingQuality.HIGH,
                includeAudio = app.settingsDataStore.recordingAudioEnabled.value,
            ),
        )
    }

    suspend fun stopRecording() {
        app.recordingController.stop()
    }

    /**
     * LIVE torch: drives the live camera torch without persisting
     * (deterrence semantics — the persisted setting can disagree with
     * reality, so the toggle target is computed off the live
     * `CameraService.isTorchOn()` read by the caller, never off the
     * setting). Main-dispatcher guarded: the camera work runs on Main, and a
     * failure is logged, never thrown into the caller's control flow.
     */
    suspend fun setTorchLive(enable: Boolean) {
        withContext(Dispatchers.Main) {
            runCatching { app.cameraService.setTorchEnabled(enable) }
                .onFailure { Log.w(TAG, "Automation torch failed: ${it.message}") }
        }
    }

    /**
     * Siren through the app's one SirenPlayer, with the shared SirenAutoStop
     * timer — the detection coordinator's deterrence and an automation
     * command must share one timer, or a stale timer could cut the other's
     * siren short. [autoStopDelayMs] is the already-decided verdict: null =
     * run until stopped (a bare toggle-on must not touch an already-armed
     * stop), non-null = arm the shared timer to stop the siren that long
     * after this start. Disable always cancels the timer before stopping.
     */
    fun setSiren(enable: Boolean, autoStopDelayMs: Long?) {
        val siren = app.streamingManager.sirenController()
        if (enable) {
            siren.start()
            // Only an explicit duration verdict touches the timer; a null
            // one leaves an already-armed stop exactly where it was.
            if (autoStopDelayMs != null) {
                app.sirenAutoStop.armAfterStart(autoStopDelayMs) { siren.stop() }
            }
        } else {
            app.sirenAutoStop.cancel()
            siren.stop()
        }
    }

    private companion object {
        const val TAG = "AutomationActionExecutor"
    }
}
