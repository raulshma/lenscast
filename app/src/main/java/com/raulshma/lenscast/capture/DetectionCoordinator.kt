package com.raulshma.lenscast.capture

import android.util.Log
import com.raulshma.lenscast.core.WebhookNotifier
import com.raulshma.lenscast.data.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Single owner of the detection-event choreography: a motion or sound event
 * is armed against the schedule, then dispatched — a bounded motion recording
 * (started only from Idle), the legacy auto-photo when recording-trigger mode
 * is off, and the webhook notification. The verdicts are [DetectionEventPolicy]'s;
 * this module keeps the store reads, the recording controller handle, and the
 * dispatch. Events arrive off the frame/audio paths, so every action here is
 * cheap and non-blocking.
 */
class DetectionCoordinator(
    private val settingsDataStore: SettingsDataStore,
    private val recordingController: RecordingController,
    private val photoCaptureManager: PhotoCaptureManager,
    private val webhookNotifier: WebhookNotifier,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** Motion event entry point (wired from StreamingManager's detector). */
    fun onMotion(delta: Double) = onEvent(EVENT_TYPE_MOTION, delta)

    /** Sound event entry point (wired from the audio reader's detector). */
    fun onSound(rmsPercent: Double) = onEvent(EVENT_TYPE_SOUND, rmsPercent)

    private fun onEvent(type: String, value: Double) {
        val store = settingsDataStore
        val armed = MotionArmingPolicy.isArmed(
            detectionEnabled = true,
            scheduleEnabled = store.motionArmScheduleEnabled.value,
            startMinute = store.motionArmStartMinute.value,
            endMinute = store.motionArmEndMinute.value,
            minuteOfDay = currentMinuteOfDay(),
        )
        Log.d(TAG, "Detection event ($type=${String.format(java.util.Locale.US, "%.1f", value)}, armed=$armed)")

        if (type == EVENT_TYPE_MOTION) {
            val action = DetectionEventPolicy.recordingAction(
                motionRecordingEnabled = store.motionRecordingEnabled.value,
                armed = armed,
                recordingActive = recordingController.isRecording.value,
            )
            when (action) {
                DetectionEventPolicy.RecordingAction.START -> startBoundedRecording()
                DetectionEventPolicy.RecordingAction.KEEP_ROLLING, DetectionEventPolicy.RecordingAction.NONE -> Unit
            }
            if (DetectionEventPolicy.shouldAutoPhoto(
                    motionRecordingEnabled = store.motionRecordingEnabled.value,
                    armed = armed,
                )
            ) {
                runCatching { photoCaptureManager.captureToGallery() }
                    .onFailure { Log.w(TAG, "Auto-photo after detection failed: ${it.message}") }
            }
        }

        if (armed) {
            webhookNotifier.notifyEvent(
                WebhookNotifier.EventPayload(type = type, rmsOrDelta = value),
            )
        }
    }

    private fun startBoundedRecording() {
        val store = settingsDataStore
        // The store clamps the persisted range (0..120); a clip still needs a
        // positive length, so zero post-roll floors at the minimum clip.
        val postRoll = store.motionPostRollSeconds.value
        val duration = (if (postRoll < MIN_CLIP_SECONDS) MIN_CLIP_SECONDS else postRoll).toLong()
        scope.launch {
            recordingController.start(
                com.raulshma.lenscast.capture.model.RecordingConfig(
                    durationSeconds = duration,
                    repeatIntervalSeconds = 0,
                    quality = com.raulshma.lenscast.capture.model.RecordingQuality.HIGH,
                    includeAudio = store.recordingAudioEnabled.value,
                ),
            )
        }
    }

    private fun currentMinuteOfDay(): Int {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMs() }
        return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    }

    companion object {
        private const val TAG = "DetectionCoordinator"
        const val EVENT_TYPE_MOTION = "motion"
        const val EVENT_TYPE_SOUND = "sound"
        private const val MIN_CLIP_SECONDS = 5
    }
}
