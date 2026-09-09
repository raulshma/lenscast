package com.raulshma.lenscast.capture

import android.util.Log
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.DetectionAlert
import com.raulshma.lenscast.core.EventKind
import com.raulshma.lenscast.core.SirenAutoStop
import com.raulshma.lenscast.core.WebhookNotifier
import com.raulshma.lenscast.core.mqtt.MqttAlertPublisher
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.StreamingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Single owner of the detection-event choreography: a motion or sound event
 * is armed against the schedule, then dispatched — a bounded motion recording
 * (started only from Idle), the legacy auto-photo when recording-trigger mode
 * is off, the webhook notification (custom headers + snapshot), the
 * persisted event-log entry, and the optional auto-siren/auto-torch
 * deterrence. The verdicts are [DetectionEventPolicy]'s and
 * [DeterrenceAutomationPolicy]'s; this module keeps the store reads, the
 * recording controller handle, the cooldown clock, and the dispatch. Events
 * arrive off the frame/audio paths, so the dispatch work runs on this
 * coordinator's own scope.
 *
 * The deterrence runtime (siren handle, torch control, latest web frame) is
 * not touched at composition — first touch is a detection event — so the
 * collaborators arrive as lazy provider lambdas wired by the composition
 * root, resolving on first event with no construction-order cycle.
 */
class DetectionCoordinator(
    private val settingsDataStore: SettingsDataStore,
    private val recordingController: RecordingController,
    private val photoCaptureManager: PhotoCaptureManager,
    private val webhookNotifier: WebhookNotifier,
    private val eventStore: DetectionEventStore,
    private val streamingManager: () -> StreamingManager?,
    private val cameraService: () -> CameraService?,
    private val mqttPublisher: () -> MqttAlertPublisher? = { null },
    private val detectionNotifier: () -> DetectionNotifier? = { null },
    private val batteryPercent: () -> Int? = { null },
    private val sirenAutoStop: SirenAutoStop,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** Time the auto-deterrence last fired; the cooldown compares against it. */
    @Volatile
    private var lastAutoDeterrenceMs = 0L

    /** Serializes the decide-and-claim step of [runAutoDeterrence]. */
    private val deterrenceLock = Any()

    /** A cooldown claim in flight, kept so a nothing-fired dispatch can roll it back. */
    private class DeterrenceClaim(
        val verdict: DeterrenceAutomationPolicy.Verdict,
        val previousMs: Long,
        val claimedAtMs: Long,
    )

    /** Motion event entry point (wired from StreamingManager's detector). */
    fun onMotion(delta: Double, zones: List<String> = emptyList()) =
        onEvent(EventKind.MOTION, delta, zones)

    /** Sound event entry point (wired from the audio reader's detector). */
    fun onSound(rmsPercent: Double) = onEvent(EventKind.SOUND, rmsPercent)

    /**
     * Tamper event entry point (wired from [TamperMonitor]): a power cut on a
     * live, charging camera. Tamper bypasses the arm schedule — that schedule
     * is a motion concept, and yanked power is never "outside the window" —
     * and triggers no motion recording or auto-photo; it still alerts
     * everywhere and can fire the deterrence automation.
     */
    fun onTamper(batteryPercentValue: Int?) = onEvent(
        EventKind.TAMPER,
        // The metric column is non-null; an unknown level reads 0 there while
        // the payload's batteryPercent field carries the honest omission.
        batteryPercentValue?.toDouble() ?: 0.0,
        zones = emptyList(),
        respectSchedule = false,
    )

    private fun onEvent(
        kind: EventKind,
        value: Double,
        zones: List<String> = emptyList(),
        respectSchedule: Boolean = true,
    ) {
        // One event-moment stamp: the alert (webhook + MQTT bodies) and the
        // persisted log entry all read it, so every sink reports the same
        // timestamp instead of each re-reading a clock at its own queue time.
        val eventTimeMs = nowMs()
        val store = settingsDataStore
        val armed = !respectSchedule || MotionArmingPolicy.isArmed(
            detectionEnabled = true,
            scheduleEnabled = store.motionArmScheduleEnabled.value,
            startMinute = store.motionArmStartMinute.value,
            endMinute = store.motionArmEndMinute.value,
            minuteOfDay = currentMinuteOfDay(),
        )
        Log.d(TAG, "Detection event (${kind.wireName}=${String.format(java.util.Locale.US, "%.1f", value)}, zones=$zones, armed=$armed)")

        val dispatchedActions = mutableListOf<String>()
        if (kind == EventKind.MOTION) {
            val action = DetectionEventPolicy.recordingAction(
                motionRecordingEnabled = store.motionRecordingEnabled.value,
                armed = armed,
                recordingActive = recordingController.isRecording.value,
            )
            when (action) {
                DetectionEventPolicy.RecordingAction.START -> {
                    // Claimed only when the start command reached the
                    // controller without throwing.
                    val started = runCatching { startBoundedRecording() }
                        .onFailure { Log.w(TAG, "Bounded recording start failed: ${it.message}") }
                        .isSuccess
                    if (started) dispatchedActions.add(ACTION_RECORDING)
                }
                DetectionEventPolicy.RecordingAction.KEEP_ROLLING, DetectionEventPolicy.RecordingAction.NONE -> Unit
            }
            if (DetectionEventPolicy.shouldAutoPhoto(
                    motionRecordingEnabled = store.motionRecordingEnabled.value,
                    armed = armed,
                )
            ) {
                runCatching { photoCaptureManager.captureToGallery() }
                    .onSuccess { dispatchedActions.add(ACTION_PHOTO) }
                    .onFailure { Log.w(TAG, "Auto-photo after detection failed: ${it.message}") }
            }
        }

        if (armed) {
            scope.launch {
                val snapshot = runCatching {
                    prepareSnapshotBase64(
                        streamingManager()?.latestWebFrame(),
                    )
                }.getOrNull()
                // One alert shape feeds every sink — webhook, MQTT, local
                // notification, and the persisted log entry all read the
                // same event.
                val alert = DetectionAlert(
                    kind = kind,
                    value = value,
                    zones = zones,
                    batteryPercent = batteryPercent(),
                    snapshotJpegBase64 = snapshot,
                    timestampMs = eventTimeMs,
                )
                // "Webhook" is claimed from the notifier's own go/no-go at
                // dispatch time: the settings can flip while the snapshot
                // encodes, and the log must follow the verdict the notifier
                // actually acted on, not a pre-encode one.
                val webhookDispatched = webhookNotifier.notifyEvent(
                    alert,
                    headers = WebhookNotifier.parseHeaders(store.webhookHeaders.value),
                )
                if (webhookDispatched) dispatchedActions.add(ACTION_WEBHOOK)
                // Same claim contract as the webhook: the MQTT verdict is the
                // publisher's own would-publish decision.
                val mqttPublished = mqttPublisher()?.notifyEvent(alert) == true
                if (mqttPublished) dispatchedActions.add(ACTION_MQTT)
                // The local alert claims only when the platform accepted the
                // post (the runtime permission gates it on API 33+).
                val notified = store.detectionNotificationsEnabled.value &&
                    detectionNotifier()?.notify(kind, zones, snapshot) == true
                if (notified) dispatchedActions.add(ACTION_NOTIFY)
                // The deterrence verdict lands before the log write so the
                // recorded entry lists every action the event dispatched.
                runAutoDeterrence(dispatchedActions)
                runCatching {
                    eventStore.record(
                        DetectionEvent(
                            id = UUID.randomUUID().toString(),
                            type = kind.wireName,
                            source = SOURCE,
                            timestampMs = eventTimeMs,
                            snapshotJpegBase64 = snapshot,
                            dispatchedActions = dispatchedActions.toList(),
                            zones = zones,
                        ),
                    )
                }.onFailure { Log.w(TAG, "Event log write failed: ${it.message}") }
            }
        }
    }

    /** The auto-siren/auto-torch half of the dispatch, behind the cooldown. */
    private suspend fun runAutoDeterrence(dispatchedActions: MutableList<String>) {
        val store = settingsDataStore
        // Decide and claim under one lock: two near-simultaneous events must
        // not both pass the cooldown check and double-fire.
        val claim = synchronized(deterrenceLock) {
            val verdict = DeterrenceAutomationPolicy.decide(
                autoSiren = store.autoSiren.value,
                autoTorch = store.autoTorch.value,
                sirenDurationSeconds = store.sirenDurationSeconds.value,
                cooldownRemainingMs = lastAutoDeterrenceMs + store.autoDeterrenceCooldownSeconds.value * 1_000L - nowMs(),
            )
            if (verdict.isNoop) {
                null
            } else {
                val previous = lastAutoDeterrenceMs
                val claimedAt = nowMs()
                lastAutoDeterrenceMs = claimedAt
                DeterrenceClaim(verdict, previous, claimedAt)
            }
        } ?: return
        var deterred = false
        if (claim.verdict.startSiren) {
            val siren = runCatching { streamingManager()?.sirenController() }.getOrNull()
            if (siren != null) {
                runCatching { siren.start() }
                    .onSuccess {
                        dispatchedActions.add(ACTION_SIREN)
                        deterred = true
                        // The timer replaces (never stacks) per run: the siren
                        // duration can outlive the cooldown, so an uncancelled
                        // stop from an older dispatch would cut the newer
                        // siren short. Concurrent dispatches are safe — the
                        // timer serializes its own cancel/replace.
                        sirenAutoStop.armAfterStart(claim.verdict.sirenDurationMs) { siren.stop() }
                    }
                    .onFailure { Log.w(TAG, "Auto-siren failed: ${it.message}") }
            }
        }
        if (claim.verdict.triggerTorch) {
            // Claimed only when the flip actually landed — the resolution runs
            // on Main before the log write, so the recorded entry lists only
            // actions that really ran (the one torch control path, shared
            // with LensWebHandler's route).
            val service = cameraService()
            if (service != null) {
                val flipped = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    runCatching { service.setTorchEnabled(true) }
                        .onFailure { Log.w(TAG, "Auto-torch failed: ${it.message}") }
                        .isSuccess
                }
                if (flipped) {
                    dispatchedActions.add(ACTION_TORCH)
                    deterred = true
                }
            }
        }
        // A rolled-back claim (nothing fired) leaves the next event free to
        // try — rolled back only while it is still the latest claim, so a
        // newer event's cooldown is never shortened by this dispatch.
        if (!deterred) {
            synchronized(deterrenceLock) {
                if (lastAutoDeterrenceMs == claim.claimedAtMs) {
                    lastAutoDeterrenceMs = claim.previousMs
                }
            }
        }
    }

    /**
     * Issues the bounded-recording start on the caller's thread: the
     * controller's start is itself a non-blocking intent dispatch, and the
     * dispatch verdict must be known here so the event log claims the action
     * only when it ran.
     */
    private fun startBoundedRecording() {
        val store = settingsDataStore
        // The store clamps the persisted range (0..120); a clip still needs a
        // positive length, so zero post-roll floors at the minimum clip.
        val postRoll = store.motionPostRollSeconds.value
        val duration = (if (postRoll < MIN_CLIP_SECONDS) MIN_CLIP_SECONDS else postRoll).toLong()
        recordingController.start(
            RecordingConfig(
                durationSeconds = duration,
                repeatIntervalSeconds = 0,
                quality = RecordingQuality.HIGH,
                includeAudio = store.recordingAudioEnabled.value,
            ),
        )
    }

    private fun currentMinuteOfDay(): Int {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMs() }
        return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    }

    /**
     * Trigger-time snapshot: the latest M-JPEG frame re-encoded at the
     * thumbnail size ([com.raulshma.lenscast.core.StreamDefaults.SNAPSHOT_TARGET_WIDTH_PX]
     * wide, [com.raulshma.lenscast.core.StreamDefaults.SNAPSHOT_JPEG_QUALITY]),
     * downscaled further until the encoding fits the log policy's size cap,
     * then base64-encoded. Null when no frame was ever rendered or the frame
     * cannot be decoded — the event logs fine without it.
     */
    private fun prepareSnapshotBase64(frame: ByteArray?): String? {
        val bytes = downscaleJpeg(frame) ?: return null
        if (!DetectionEventLogPolicy.acceptsSnapshot(bytes)) return null
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * The thumbnail ladder: decode at the largest power-of-two sample
     * that stays at or under the target width, encode, and — while the
     * encoding still misses [DetectionEventLogPolicy]'s size gate —
     * halve the size and retry. The gate decides the final size, so a
     * busy scene ships a smaller thumbnail instead of no snapshot.
     */
    private fun downscaleJpeg(frame: ByteArray?): ByteArray? {
        if (frame == null || frame.isEmpty()) return null
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(frame, 0, frame.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >=
                com.raulshma.lenscast.core.StreamDefaults.SNAPSHOT_TARGET_WIDTH_PX
            ) {
                sample *= 2
            }
            var bytes: ByteArray? = null
            ladder@ while (sample <= MAX_DOWNSCALE_SAMPLE) {
                val decoded = android.graphics.BitmapFactory.decodeByteArray(
                    frame, 0, frame.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: break
                val output = java.io.ByteArrayOutputStream()
                decoded.compress(
                    android.graphics.Bitmap.CompressFormat.JPEG,
                    com.raulshma.lenscast.core.StreamDefaults.SNAPSHOT_JPEG_QUALITY,
                    output,
                )
                decoded.recycle()
                bytes = output.toByteArray()
                if (DetectionEventLogPolicy.acceptsSnapshot(bytes)) break@ladder
                sample *= 2
            }
            bytes
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "DetectionCoordinator"
        private const val SOURCE = "lenscast"
        private const val MIN_CLIP_SECONDS = 5

        /** The thumbnail ladder's floor: at or under a 1/32-width decode the gate always passes, so stop. */
        private const val MAX_DOWNSCALE_SAMPLE = 32

        /** Event-log names for the dispatched actions, surfaced in the web feed. */
        const val ACTION_RECORDING = "recording"
        const val ACTION_PHOTO = "photo"
        const val ACTION_WEBHOOK = "webhook"
        const val ACTION_MQTT = "mqtt"
        const val ACTION_NOTIFY = "notify"
        const val ACTION_SIREN = "siren"
        const val ACTION_TORCH = "torch"
    }
}
