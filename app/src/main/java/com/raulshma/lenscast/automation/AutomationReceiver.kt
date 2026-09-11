package com.raulshma.lenscast.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.automation.AutomationIntentPolicy.AutomationAction
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import com.raulshma.lenscast.streaming.BootResumePolicy
import com.raulshma.lenscast.streaming.StreamWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The external automation surface (Tasker, MacroDroid, adb, ADB-driven
 * scripts): an exported broadcast receiver exposing the same operations the
 * in-app surfaces use — stream start/stop, photo capture, recording
 * start/stop, torch, siren. Actions are package-scoped
 * (`com.raulshma.lenscast.action.*`); extras are optional and documented in
 * README. Every action routes through the one shared seam (Stream Toggle,
 * Recording Controller, Photo Capture Manager, CameraService, SirenPlayer) —
 * the receiver is a front-end, never a second implementation.
 *
 * Exported but permission-guarded (`com.raulshma.lenscast.permission.AUTOMATION`,
 * declared in the manifest) — an open receiver would let any installed app
 * drive the camera, the recorder, or the siren. Every path guards against a
 * cold-started process where the composition root is not the expected
 * application.
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MainApplication ?: return
        val pendingResult = goAsync()
        // The broadcast window stays open until the action (including the
        // async photo capture's callbacks) really finishes; finish exactly
        // once, from whichever path completes.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun complete() {
            if (finished.compareAndSet(false, true)) pendingResult.finish()
        }
        scope.launch {
            // True when the async photo capture's callbacks own the finish
            // (the capture was queued): the coroutine body returning early
            // must not close the broadcast window under a live capture.
            var completionOwnedByCallbacks = false
            try {
                when (AutomationIntentPolicy.route(intent.action)) {
                    AutomationAction.START_STREAM -> BootResumePolicy.execute(
                        app.streamToggle,
                        BootResumePolicy.tileStart(app.streamStateJournal.load()),
                    )
                    AutomationAction.STOP_STREAM -> app.streamToggle.stopServer()
                    AutomationAction.CAPTURE_PHOTO -> {
                        // Null means the use case could not be acquired — no
                        // callback will ever fire, so the finally completes.
                        completionOwnedByCallbacks = runCatching {
                            app.photoCaptureManager.captureToGallery(
                                onSaved = { _, _ -> complete() },
                                onError = { exception ->
                                    Log.w(TAG, "Automation capture failed: ${exception.message}")
                                    complete()
                                },
                            )
                        }.getOrNull() != null
                    }
                    AutomationAction.START_RECORDING -> app.recordingController.start(
                        RecordingConfig(
                            durationSeconds = AutomationIntentPolicy.recordingDurationSeconds(
                                intent.getIntExtra(EXTRA_DURATION_SECONDS, 0),
                            ),
                            repeatIntervalSeconds = 0,
                            quality = RecordingQuality.HIGH,
                            includeAudio = app.settingsDataStore.recordingAudioEnabled.value,
                        ),
                    )
                    AutomationAction.STOP_RECORDING -> app.recordingController.stop()
                    AutomationAction.SET_TORCH -> {
                        // Toggle semantics read the live torch, not the
                        // persisted setting: deterrence (ACTION_TORCH) drives
                        // the torch without persisting, so the setting can
                        // disagree with reality and a toggle would compute
                        // the wrong target.
                        val enable = AutomationIntentPolicy.resolveEnable(
                            hasEnabledExtra = intent.hasExtra(EXTRA_ENABLED),
                            enabledExtraValue = intent.getBooleanExtra(EXTRA_ENABLED, false),
                            currentState = !app.cameraService.isTorchOn(),
                        )
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            runCatching { app.cameraService.setTorchEnabled(enable) }
                                .onFailure { Log.w(TAG, "Automation torch failed: ${it.message}") }
                        }
                    }
                    AutomationAction.SET_SIREN -> {
                        val siren = app.streamingManager.sirenController()
                        val enable = AutomationIntentPolicy.resolveEnable(
                            hasEnabledExtra = intent.hasExtra(EXTRA_ENABLED),
                            enabledExtraValue = intent.getBooleanExtra(EXTRA_ENABLED, false),
                            currentState = !siren.isRunning(),
                        )
                        if (enable) {
                            siren.start()
                            // Only an explicit duration command touches the
                            // timer: a re-arm can extend a running siren's
                            // stop, but a bare toggle-on must not cancel an
                            // already-armed stop. Absent extra = run until
                            // stopped; a 0-valued extra = explicit "no limit".
                            val autoStopDelayMs = AutomationIntentPolicy.sirenAutoStopDelayMs(
                                hasDurationExtra = intent.hasExtra(EXTRA_DURATION_SECONDS),
                                rawDurationSeconds = intent.getIntExtra(EXTRA_DURATION_SECONDS, 0),
                            )
                            if (autoStopDelayMs != null) {
                                app.sirenAutoStop.armAfterStart(autoStopDelayMs) { siren.stop() }
                            }
                        } else {
                            app.sirenAutoStop.cancel()
                            siren.stop()
                        }
                    }
                    // Unlisted actions touch nothing — the policy's route is
                    // the in-code allow-list behind the manifest permission
                    // gate. The widget refresh below still runs, exactly as
                    // the old fall-through `when` did.
                    null -> Unit
                }
                StreamWidgetProvider.refresh(app)
            } catch (e: Exception) {
                Log.w(TAG, "Automation action ${intent.action} failed: ${e.message}")
            } finally {
                if (!completionOwnedByCallbacks) complete()
            }
        }
    }

    companion object {
        private const val TAG = "AutomationReceiver"

        // Receiver instances are recreated per broadcast, so the goAsync
        // scope lives at class level. The siren auto-stop timer is the app's
        // one instance (app.sirenAutoStop): a SET_SIREN broadcast and the
        // detection coordinator's deterrence must share it, or a stale
        // timer could cut the other's siren short.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        const val ACTION_START_STREAM = "com.raulshma.lenscast.action.START_STREAM"
        const val ACTION_STOP_STREAM = "com.raulshma.lenscast.action.STOP_STREAM"
        const val ACTION_CAPTURE_PHOTO = "com.raulshma.lenscast.action.CAPTURE_PHOTO"
        const val ACTION_START_RECORDING = "com.raulshma.lenscast.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.raulshma.lenscast.action.STOP_RECORDING"
        const val ACTION_SET_TORCH = "com.raulshma.lenscast.action.SET_TORCH"
        const val ACTION_SET_SIREN = "com.raulshma.lenscast.action.SET_SIREN"

        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_DURATION_SECONDS = "durationSeconds"

        /** The explicit-intent broadcast every action surface (widget included) sends. */
        fun intent(context: Context, action: String): Intent =
            Intent(context, AutomationReceiver::class.java).setAction(action)
    }
}
