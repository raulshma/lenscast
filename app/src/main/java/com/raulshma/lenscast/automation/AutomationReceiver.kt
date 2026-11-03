package com.raulshma.lenscast.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.automation.AutomationIntentPolicy.AutomationAction
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
 * The shell is deliberately thin: onReceive resolves the broadcast through
 * [AutomationIntentPolicy] (the wire verdicts — the action routing, the
 * `enabled` toggle target, the clamped recording duration, the siren
 * auto-stop delay) and hands each verdict to [AutomationActionExecutor],
 * the one execution seam the coming in-app scheduler drives too. The
 * executor is constructed per broadcast here: it is stateless and cheap
 * (it only routes calls onto the app's shared seam graph), so MainApplication —
 * the composition root — gains no new member for it.
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
        // Per-broadcast executor: stateless and cheap, so it needs no scope
        // lifetime and the composition root gains nothing (see class KDoc).
        val executor = AutomationActionExecutor(app)
        scope.launch {
            // True when the async photo capture's callbacks own the finish
            // (the capture was queued): the coroutine body returning early
            // must not close the broadcast window under a live capture.
            var completionOwnedByCallbacks = false
            try {
                when (AutomationIntentPolicy.route(intent.action)) {
                    AutomationAction.START_STREAM -> executor.startStream()
                    AutomationAction.STOP_STREAM -> executor.stopStream()
                    AutomationAction.CAPTURE_PHOTO -> {
                        // False means the use case could not be acquired (or
                        // the capture never started) — no callback will ever
                        // fire, so the finally completes.
                        completionOwnedByCallbacks = executor.capturePhoto(
                            onSaved = { _, _ -> complete() },
                            onError = { exception ->
                                Log.w(TAG, "Automation capture failed: ${exception.message}")
                                complete()
                            },
                        )
                    }
                    AutomationAction.START_RECORDING -> executor.startRecording(
                        AutomationIntentPolicy.recordingDurationSeconds(
                            intent.getIntExtra(EXTRA_DURATION_SECONDS, 0),
                        ),
                    )
                    AutomationAction.STOP_RECORDING -> executor.stopRecording()
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
                        executor.setTorchLive(enable)
                    }
                    AutomationAction.SET_SIREN -> {
                        val siren = app.streamingManager.sirenController()
                        val enable = AutomationIntentPolicy.resolveEnable(
                            hasEnabledExtra = intent.hasExtra(EXTRA_ENABLED),
                            enabledExtraValue = intent.getBooleanExtra(EXTRA_ENABLED, false),
                            currentState = !siren.isRunning(),
                        )
                        // Only an explicit duration command touches the
                        // timer: absent extra = run until stopped (the
                        // executor leaves an already-armed stop alone), a
                        // 0-valued extra = explicit "no limit".
                        val autoStopDelayMs = AutomationIntentPolicy.sirenAutoStopDelayMs(
                            hasDurationExtra = intent.hasExtra(EXTRA_DURATION_SECONDS),
                            rawDurationSeconds = intent.getIntExtra(EXTRA_DURATION_SECONDS, 0),
                        )
                        executor.setSiren(enable, autoStopDelayMs)
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
