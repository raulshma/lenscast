package com.raulshma.lenscast.capture

import android.util.Log
import com.raulshma.lenscast.capture.ml.AnalysisFrame
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.capture.ml.ObjectDetectionEngine
import com.raulshma.lenscast.capture.model.DetectionClassPolicy
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.DetectionAlert
import com.raulshma.lenscast.core.EventKind
import com.raulshma.lenscast.core.JpegDownscaler
import com.raulshma.lenscast.core.SirenAutoStop
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.WebhookNotifier
import com.raulshma.lenscast.core.mqtt.MqttAlertPublisher
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.StreamingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
 * Motion events additionally pass through the ML object-detection gate when
 * `mlDetectionEnabled` is on: the triggering frame is classified off-path
 * (single worker, throttled) and an empty allowed-class verdict suppresses
 * the event entirely — no alert, no recording, no log entry. Surviving
 * events carry the detected class labels on the alert and the log entry.
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
    /** The capture history, consulted when a bounded motion recording finalizes so the event can link its clip. */
    private val captureHistoryStore: CaptureHistoryStore? = null,
    /**
     * The on-demand model store behind the ML gate: its gate auto-fetches the
     * model on the first gated motion event and resolves the file per init
     * attempt.
     */
    private val detectionModelStore: DetectionModelStore,
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

    // ── ML object-detection gate ──
    // When `mlDetectionEnabled` is on, a motion verdict no longer dispatches
    // directly: the triggering frame is classified first (EfficientDet-Lite0
    // through [ObjectDetectionEngine]) and only an allowed class at/above the
    // confidence floor ([DetectionClassPolicy]) lets the event through. The
    // gate is fail-open by design — a skipped, throttled, or failed
    // classification never suppresses an alert, only a positive "no allowed
    // class" verdict does.

    /**
     * The engine is built on first gated motion event; the model file (not
     * bundled in the APK) is resolved through [DetectionModelStore] per init
     * attempt, so a model downloaded later is picked up on the next event.
     */
    private val mlEngine by lazy {
        ObjectDetectionEngine(modelFileProvider = { detectionModelStore.resolveModelFile() })
    }

    /** One inference at a time, off the frame path — the frame listener never blocks on the model. */
    private val mlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MlDetection").apply { isDaemon = true }
    }

    private val mlLock = Any()
    private var mlLastSubmitMs = 0L
    private val mlInferring = AtomicBoolean(false)

    /** Motion event entry point (wired from StreamingManager's detector). */
    fun onMotion(delta: Double, zones: List<String> = emptyList()) {
        if (!settingsDataStore.mlDetectionEnabled.value || !motionArmedNow()) {
            // Gate off (byte-identical legacy behavior) or the arm schedule is
            // disarmed — onEvent will drop the dispatch anyway, so don't burn
            // inference on it.
            onEvent(EventKind.MOTION, delta, zones)
            return
        }
        // The model ships outside the APK: the first gated event after the
        // feature (or the download) was requested fetches it. Idempotent in
        // the store — no-op while Ready or already downloading — and the gate
        // fail-opens until the model is in place.
        detectionModelStore.requestDownload()
        submitMlClassification { suppressed, labels ->
            if (suppressed) {
                Log.d(TAG, "Motion event suppressed by ML gate (no allowed class at/above threshold)")
            } else {
                onEvent(EventKind.MOTION, delta, zones, labels = labels)
            }
        }
    }

    /** Serializes the decide-and-claim step of [runAutoDeterrence]. */
    private val deterrenceLock = Any()

    /** A cooldown claim in flight, kept so a nothing-fired dispatch can roll it back. */
    private class DeterrenceClaim(
        val verdict: DeterrenceAutomationPolicy.Verdict,
        val previousMs: Long,
        val claimedAtMs: Long,
    )

    /** Sound event entry point (wired from the audio reader's detector). */
    fun onSound(rmsPercent: Double) = onEvent(EventKind.SOUND, rmsPercent)

    /**
     * The motion arm-schedule verdict, consulted before the ML gate so a
     * schedule-disarmed window costs no inference. [onEvent] re-derives it
     * for the dispatch itself.
     */
    private fun motionArmedNow(): Boolean = MotionArmingPolicy.isArmed(
        detectionEnabled = true,
        scheduleEnabled = settingsDataStore.motionArmScheduleEnabled.value,
        startMinute = settingsDataStore.motionArmStartMinute.value,
        endMinute = settingsDataStore.motionArmEndMinute.value,
        minuteOfDay = currentMinuteOfDay(),
    )

    /**
     * Runs the ML gate for one motion verdict: at most one classification per
     * [ML_MIN_INTERVAL_MS] and one in flight at a time — a burst event that
     * arrives busy or throttled passes through unfiltered (fail-open), it is
     * never suppressed without a verdict. The frame is copied here, on the
     * motion call stack, before the bytes can be recycled; inference and the
     * [onVerdict] callback run on the single ML worker. The callback receives
     * `suppressed = true` only for a real classification whose allowed-class
     * filter came back empty.
     */
    private fun submitMlClassification(onVerdict: (suppressed: Boolean, labels: List<String>) -> Unit) {
        val snapshot: AnalysisFrame? = synchronized(mlLock) {
            val now = nowMs()
            val busy = mlInferring.get()
            val throttled = now - mlLastSubmitMs < ML_MIN_INTERVAL_MS
            val frame = if (busy || throttled) null else streamingManager()?.latestAnalysisFrame()
            if (frame == null) {
                null
            } else {
                mlLastSubmitMs = now
                mlInferring.set(true)
                // Defensive copy: the camera recycles the buffer as soon as
                // the frame path returns.
                AnalysisFrame(frame.nv21.copyOf(), frame.width, frame.height)
            }
        }
        if (snapshot == null) {
            onVerdict(false, emptyList())
            return
        }
        mlExecutor.execute {
            try {
                val verdict = mlEngine.classify(snapshot)
                when (verdict) {
                    is ObjectDetectionEngine.Classification.Success -> {
                        val labels = DetectionClassPolicy.filter(
                            verdict.detections,
                            settingsDataStore.mlMinScorePercent.value,
                        )
                        onVerdict(labels.isEmpty(), labels)
                    }
                    ObjectDetectionEngine.Classification.Unavailable -> onVerdict(false, emptyList())
                }
            } finally {
                mlInferring.set(false)
            }
        }
    }

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
        /** ML class labels the object-detection gate attached (motion-gated events only). */
        labels: List<String> = emptyList(),
    ) {
        // One event-moment stamp and one id: the alert (webhook + MQTT
        // bodies), the persisted log entry, and the clip-linkage watcher all
        // read them, so every sink reports the same identity instead of each
        // minting its own.
        val eventTimeMs = nowMs()
        val eventId = UUID.randomUUID().toString()
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
            // Continuous-recording interplay: while the NVR-style loop holds
            // the recorder, `recordingActive` is true and the verdict is
            // KEEP_ROLLING — the motion-triggered bounded clip is skipped and
            // the event's clip link fields stay null. The event itself (and
            // its alert fan-out) still fires and is still logged.
            when (action) {
                DetectionEventPolicy.RecordingAction.START -> {
                    // Claimed only when the start command reached the
                    // controller without throwing.
                    val started = runCatching { startBoundedRecording() }
                        .onFailure { Log.w(TAG, "Bounded recording start failed: ${it.message}") }
                        .isSuccess
                    if (started) {
                        dispatchedActions.add(ACTION_RECORDING)
                        // The event is already on disk when the clip link
                        // lands: the watcher updates it once the recording
                        // finalizes (the same post-event-update path other
                        // sinks use), never blocks the dispatch.
                        watchMotionClip(eventId, eventTimeMs)
                    }
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
                    labels = labels,
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
                            id = eventId,
                            type = kind.wireName,
                            source = SOURCE,
                            timestampMs = eventTimeMs,
                            snapshotJpegBase64 = snapshot,
                            dispatchedActions = dispatchedActions.toList(),
                            zones = zones,
                            labels = labels,
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

    /**
     * The event→clip linkage: watches the Recording Controller's state for the
     * bounded recording this event just started, waits for it to finalize, and
     * links the resulting clip (MediaStore id + file name, resolved from the
     * capture history as the newest video recorded since the event) into the
     * persisted event via [DetectionEventStore.updateEvent]. Every wait is
     * bounded, so a start that never came live or a user stop costs one
     * expiring watcher, never a leaked job — and the event stays honest,
     * keeping null clip fields when no clip can be attributed.
     */
    private fun watchMotionClip(eventId: String, eventTimeMs: Long) {
        val history = captureHistoryStore ?: return
        scope.launch {
            runCatching {
                val recording = withTimeoutOrNull(CLIP_LINK_TIMEOUT_MS) {
                    recordingController.state.first { it is RecordingState.Recording }
                } as? RecordingState.Recording ?: return@launch
                withTimeoutOrNull(CLIP_LINK_TIMEOUT_MS) {
                    recordingController.state.first { it is RecordingState.Idle }
                } ?: return@launch
                // Upper bound: only clips recorded inside this bounded window
                // qualify — a recording a user starts later must never be
                // claimed as this event's clip.
                val windowEndMs = nowMs() + CLIP_TIMESTAMP_SLACK_MS
                history.history.value
                    .filter {
                        it.type == CaptureType.VIDEO &&
                            it.timestamp >= recording.startedAtMs - CLIP_TIMESTAMP_SLACK_MS &&
                            it.timestamp <= windowEndMs
                    }
                    .maxByOrNull { it.timestamp }
            }.onSuccess { entry ->
                val clip = entry ?: return@onSuccess
                eventStore.updateEvent(eventId) { event ->
                    event.copy(
                        clipMediaId = DetectionEvent.clipMediaIdFromContentUri(clip.filePath),
                        clipFileName = clip.fileName,
                    )
                }
            }.onFailure { Log.w(TAG, "Clip linkage watch failed: ${it.message}") }
        }
    }

    private fun currentMinuteOfDay(): Int {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMs() }
        return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    }

    /**
     * Trigger-time snapshot: the latest M-JPEG frame re-encoded at
     * [com.raulshma.lenscast.core.StreamDefaults.SNAPSHOT_TARGET_WIDTH_PX]
     * via the shared [JpegDownscaler] ladder — downscaled further until the
     * encoding fits the log policy's size cap — then base64-encoded. Null
     * when no frame was ever rendered or the frame cannot be decoded — the
     * event logs fine without it.
     */
    private fun prepareSnapshotBase64(frame: ByteArray?): String? {
        val bytes = JpegDownscaler.downscale(
            jpeg = frame,
            targetMaxPx = StreamDefaults.SNAPSHOT_TARGET_WIDTH_PX,
            quality = StreamDefaults.SNAPSHOT_JPEG_QUALITY,
            accepts = DetectionEventLogPolicy::acceptsSnapshot,
        ) ?: return null
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    companion object {
        private const val TAG = "DetectionCoordinator"
        private const val SOURCE = "lenscast"
        private const val MIN_CLIP_SECONDS = 5

        /** ML gate throttle: at most one classification per this interval. */
        private const val ML_MIN_INTERVAL_MS = 1_000L

        /** Bounded waits for the clip watcher: recording live, then finalizing. */
        private const val CLIP_LINK_TIMEOUT_MS = 10 * 60 * 1000L

        /** Clock-granularity slack when matching history entries to the recording's start stamp. */
        private const val CLIP_TIMESTAMP_SLACK_MS = 1_000L

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
