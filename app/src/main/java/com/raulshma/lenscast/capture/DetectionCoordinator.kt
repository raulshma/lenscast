package com.raulshma.lenscast.capture

import android.content.Context
import android.util.Log
import com.raulshma.lenscast.capture.ml.AnalysisFrame
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.capture.ml.ObjectClassifier
import com.raulshma.lenscast.capture.ml.ObjectDetectionEngine
import com.raulshma.lenscast.capture.ml.SoundClassifier
import com.raulshma.lenscast.capture.ml.SoundClassificationEngine
import com.raulshma.lenscast.capture.model.DetectionClassPolicy
import com.raulshma.lenscast.capture.model.SoundClassPolicy
import com.raulshma.lenscast.capture.model.SoundLabelTracker
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.capture.model.DetectionEventDeepLink
import com.raulshma.lenscast.core.DetectionAlert
import com.raulshma.lenscast.core.EventKind
import com.raulshma.lenscast.core.JpegDownscaler
import com.raulshma.lenscast.core.LocalWallClock
import com.raulshma.lenscast.core.SirenAutoStop
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.WebhookNotifier
import com.raulshma.lenscast.core.mqtt.MqttAlertPublisher
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import com.raulshma.lenscast.capture.model.RecordingTrigger
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
 * is off, the webhook notification (custom headers + snapshot), the Web Push
 * notification to subscribed browsers, the persisted event-log entry, and
 * the optional auto-siren/auto-torch deterrence. The verdicts are
 * [DetectionEventPolicy]'s and [DeterrenceAutomationPolicy]'s; this module
 * keeps the store reads, the recording controller handle, the cooldown clock,
 * and the dispatch. Events arrive off the frame/audio paths, so the dispatch
 * work runs on this coordinator's own scope.
 *
 * Motion events additionally pass through the ML object-detection gate when
 * `mlDetectionEnabled` is on: the triggering frame is classified off-path
 * (single worker, throttled) and an empty allowed-class verdict suppresses
 * the event entirely — no alert, no recording, no log entry. Surviving
 * events carry the detected class labels on the alert and the log entry.
 *
 * Sound events ride the same `labels` field through YAMNet classification
 * when `soundClassificationEnabled` is on — annotate-only by design: the
 * same PCM the RMS detector consumes feeds the classifier off-path, its
 * window labels pass the pure SoundClassPolicy gate, and a missing model,
 * API < 24, error, or empty verdict never suppresses or delays the RMS
 * event; it just ships unlabeled.
 *
 * The deterrence runtime (siren handle, torch control, latest web frame) is
 * not touched at composition — first touch is a detection event — so the
 * collaborators arrive as lazy provider lambdas wired by the composition
 * root, resolving on first event with no construction-order cycle. The
 * alert fan-out rides the [DetectionSink] list (constructor default: the
 * four adapters in `DetectionSinks.kt` over this class's own notifier/
 * publisher/sender/notification dependencies), so a new sink is a list
 * entry, not an edit to the dispatch — each sink answers its own go/no-go
 * and the claimed [DetectionEventAction]s feed the event log with the exact
 * strings the feed has always persisted.
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
     * Application context for the ML engine — MediaPipe's task factory is
     * context-based (unlike the old TFLite task library's file-based one).
     */
    private val appContext: Context,
    /**
     * The on-demand model store behind the ML gate: its gate auto-fetches the
     * model on the first gated motion event and resolves the file per init
     * attempt.
     */
    private val detectionModelStore: DetectionModelStore,
    /**
     * The on-demand home of the YAMNet classifier behind sound
     * classification: same auto-fetch/resolve contract as the ML gate's
     * store, one store per model.
     */
    private val audioModelStore: AudioModelStore,
    private val streamingManager: () -> StreamingManager?,
    private val cameraService: () -> CameraService?,
    private val mqttPublisher: () -> MqttAlertPublisher? = { null },
    private val webPushSender: () -> com.raulshma.lenscast.core.push.WebPushSender? = { null },
    private val detectionNotifier: () -> DetectionNotifier? = { null },
    private val batteryPercent: () -> Int? = { null },
    private val sirenAutoStop: SirenAutoStop,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * The ML object-detection gate's classifier seam
     * ([ObjectClassifier]): the MediaPipe-backed engine by default —
     * resolved lazily, exactly like the old `mlEngine by lazy` — and a
     * fake for JVM tests pinning the submit ladder.
     */
    private val objectClassifier: ObjectClassifier? = null,
    /**
     * The sound-classification seam ([SoundClassifier]): the YAMNet-backed
     * engine by default — resolved lazily on first fed chunk, exactly like
     * the old `soundClassifier by lazy` — and a fake for JVM tests.
     */
    private val soundClassifier: SoundClassifier? = null,
    /**
     * The analysis-frame source the ML gate classifies (a defensive copy is
     * taken on the motion call stack). Defaults to the streaming manager's
     * retained frame; tests inject a static frame to keep StreamingManager
     * out of the ladder pins.
     */
    private val analysisFrameProvider: () -> AnalysisFrame? = { streamingManager()?.latestAnalysisFrame() },
    /**
     * The alert fan-out, in dispatch order: webhook, MQTT, Web Push, local
     * notification. The default builds this class's four adapters over the
     * dependencies above — the same sequence the god-method used to spell
     * out inline — so a fifth sink is a list entry here, not an edit to the
     * dispatch body.
     */
    private val sinks: List<DetectionSink> = listOf(
        WebhookDetectionSink(webhookNotifier) { settingsDataStore.webhookHeaders.value },
        MqttDetectionSink(mqttPublisher),
        WebPushDetectionSink(webPushSender),
        NotificationDetectionSink(
            notifier = detectionNotifier,
            store = settingsDataStore,
            minuteOfDay = { minuteOfDayFrom(nowMs()) },
        ),
    ),
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
     * The injected [objectClassifier] seam short-circuits the construction
     * (tests pin the submit ladder on fakes; the real engine stays unbuilt).
     */
    private val mlEngine: ObjectClassifier by lazy {
        objectClassifier ?: ObjectDetectionEngine(
            context = appContext,
            modelFileProvider = { detectionModelStore.resolveModelFile() },
        )
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

    // ── YAMNet sound classification (annotate-only) ──
    // When `soundClassificationEnabled` is on (and sound detection itself
    // armed — classification only annotates the events the RMS detector
    // fires), the same PCM the detector consumes also feeds the YAMNet
    // engine: every 0.96 s window's top-1 passes the pure SoundClassPolicy
    // gate (confidence + allow-list) into the SoundLabelTracker, and the
    // tracker's winning labels ride the sound event's payload — the same
    // `labels` field the ML gate's class names ride. Classification NEVER
    // suppresses an RMS event: any error, missing model, API < 24, or busy
    // worker simply ships the event unlabeled (fail-open, annotate-only).

    /**
     * Built on first fed audio chunk while enabled; the YAMNet file (not
     * bundled) resolves through [AudioModelStore] per init attempt, so a
     * download landing later is picked up by the next window. The injected
     * [soundClassifier] seam short-circuits the construction (tests pin the
     * feed gating on fakes; the real engine stays unbuilt). The member keeps
     * the old `soundClassifier by lazy` timing — built on first use, so the
     * window listener can reference this instance.
     */
    private val soundEngine: SoundClassifier by lazy {
        soundClassifier ?: SoundClassificationEngine(
            context = appContext,
            modelFileProvider = { audioModelStore.resolveModelFile() },
        ) { label, scorePercent -> onSoundWindow(label, scorePercent) }
    }

    /** The label-stability state behind [onSound]'s payload labels. */
    private val soundLabelTracker = SoundLabelTracker()

    /** Serializes the audio-model auto-request throttle's decide-and-claim. */
    private val audioModelRequestLock = Any()
    private var lastAudioModelRequestMs = 0L

    /** The class-trigger path's last-fire stamp; its cooldown compares against it. */
    @Volatile
    private var lastSoundClassTriggerMs = 0L

    /**
     * Audio entry point for classification (wired from StreamingManager's
     * chunk tap, audio reader thread). Cheap when the feature is off — one
     * flow read — and fail-open by contract: nothing here may throw onto the
     * audio path or delay it.
     */
    fun feedSoundClassification(pcm16: ByteArray, sampleRateHz: Int, channelCount: Int) {
        // No sound detector, no sound events — and without events,
        // classification is pure CPU burn.
        if (!settingsDataStore.soundClassificationEnabled.value ||
            !settingsDataStore.soundDetectionEnabled.value
        ) {
            return
        }
        requestAudioModelDownloadIfNeeded()
        try {
            soundEngine.feed(pcm16, sampleRateHz, channelCount)
        } catch (_: Exception) {
            // Never onto the audio path.
        }
    }

    /**
     * One completed YAMNet window, on the classifier worker: the raw top-1
     * through the policy gate, then into the tracker. Reads the settings
     * live, like the ML gate does per verdict.
     *
     * The second decision path — the opt-in class trigger — rides the same
     * window: a label in the chosen trigger classes at/above the confidence
     * floor fires a detection event of its own (recording/alert/log, exactly
     * the choreography an RMS event gets) *independent of the RMS threshold*
     * and behind the persisted sound cooldown. Additive only: nothing here
     * can suppress or delay an RMS event — the two paths never meet.
     */
    private fun onSoundWindow(label: String?, scorePercent: Float) {
        try {
            val store = settingsDataStore
            val qualified = SoundClassPolicy.windowLabel(
                topLabel = label,
                scorePercent = scorePercent,
                minConfidencePercent = store.soundClassificationConfidencePercent.value,
                allowedClasses = SoundClassPolicy.normalizeAllowed(
                    store.soundClassificationAllowedClasses.value,
                ),
            )
            soundLabelTracker.onWindow(qualified, nowMs())
            if (store.soundTriggerEnabled.value) {
                val now = nowMs()
                if (SoundClassPolicy.shouldTriggerOnClass(
                        topLabel = label,
                        scorePercent = scorePercent,
                        minConfidencePercent = store.soundClassificationConfidencePercent.value,
                        triggerClasses = SoundClassPolicy.normalizeTriggerClasses(
                            store.soundTriggerClasses.value,
                        ),
                        nowMs = now,
                        lastTriggerMs = lastSoundClassTriggerMs,
                        cooldownMs = store.soundCooldownSeconds.value * 1_000L,
                    )
                ) {
                    lastSoundClassTriggerMs = now
                    // The metric column carries the classifier's confidence
                    // percent; the label rides the same `labels` field every
                    // sound event carries.
                    onEvent(EventKind.SOUND, scorePercent.toDouble(), labels = listOfNotNull(label))
                }
            }
        } catch (_: Exception) {
            // Fail-open: a dropped window never blocks anything.
        }
    }

    /**
     * The store's auto-fetch, throttled: the ML gate asks per gated *event*,
     * but audio chunks arrive ~50×/s and an unthrottled ask on a persistent
     * network failure would retry-storm. One attempt per interval at most;
     * manual retries (settings row, web route) bypass this entirely.
     */
    private fun requestAudioModelDownloadIfNeeded() {
        synchronized(audioModelRequestLock) {
            val now = nowMs()
            if (now - lastAudioModelRequestMs < AUDIO_MODEL_REQUEST_INTERVAL_MS) return
            lastAudioModelRequestMs = now
        }
        audioModelStore.requestDownload()
    }

    /**
     * Sound event entry point (wired from the audio reader's detector). When
     * classification is enabled, the tracker's winning labels ride the same
     * `labels` field ML class names ride — never suppressing the RMS event:
     * an empty verdict (feature off, model missing, nothing qualified) is the
     * plain unlabeled sound event.
     */
    fun onSound(rmsPercent: Double) {
        val labels = if (settingsDataStore.soundClassificationEnabled.value) {
            try {
                soundLabelTracker.winningLabels(nowMs())
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        onEvent(EventKind.SOUND, rmsPercent, labels = labels)
    }

    /**
     * The motion arm-schedule verdict, consulted before the ML gate so a
     * schedule-disarmed window costs no inference, and re-derived by
     * [onEvent] for the dispatch itself (the settings can flip between the
     * two reads, so the dispatch trusts its own fresh verdict). The one
     * home of the seven-argument [MotionArmingPolicy.isArmed] call.
     */
    private fun motionArmedNow(): Boolean = MotionArmingPolicy.isArmed(
        detectionEnabled = true,
        scheduleEnabled = settingsDataStore.motionArmScheduleEnabled.value,
        startMinute = settingsDataStore.motionArmStartMinute.value,
        endMinute = settingsDataStore.motionArmEndMinute.value,
        minuteOfDay = currentMinuteOfDay(),
        daysMask = settingsDataStore.motionArmDaysMask.value,
        isoDayIndex = currentIsoDayIndex(),
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
            val frame = if (busy || throttled) null else analysisFrameProvider()
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
                            allowedClasses = DetectionClassPolicy.allowList(
                                includePerson = settingsDataStore.mlIncludePerson.value,
                                includePets = settingsDataStore.mlIncludePets.value,
                                includeVehicles = settingsDataStore.mlIncludeVehicles.value,
                            ),
                        )
                        onVerdict(labels.isEmpty(), labels)
                    }
                    ObjectDetectionEngine.Classification.Unavailable -> onVerdict(false, emptyList())
                }
            } catch (e: Exception) {
                // Fail open, same as Unavailable: a crashed classification
                // must never eat the motion event on its way through.
                Log.w(TAG, "ML classification failed; failing open", e)
                onVerdict(false, emptyList())
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

    /**
     * The dashboard's "send test alert" dispatch: one synthetic [EventKind.TEST]
     * alert through the alert sinks — webhook, MQTT, Web Push, local
     * notification — so a new integration can be verified end to end without
     * waiting for a real event. Never recorded to the event log, never gated
     * by the arm schedule or the ML gate, never fires the deterrence
     * automation or a recording — and deliberately NOT routed through
     * [sinks]: the test alert exists to prove the sinks fire, so unlike the
     * real fan-out it posts even inside a quiet window. Returns the sinks
     * that actually dispatched (the same action wire names the event log
     * uses), read from each sink's own go/no-go verdict.
     */
    suspend fun dispatchTestAlert(): List<String> {
        val store = settingsDataStore
        val dispatched = mutableListOf<String>()
        val alert = DetectionAlert(
            kind = EventKind.TEST,
            value = 0.0,
            timestampMs = nowMs(),
            batteryPercent = batteryPercent(),
            snapshotJpegBase64 = runCatching {
                prepareSnapshotBase64(streamingManager()?.latestSnapshotJpeg())
            }.getOrNull(),
        )
        val webhookDispatched = webhookNotifier.notifyEvent(
            alert,
            headers = WebhookNotifier.parseHeaders(store.webhookHeaders.value),
            deepLink = DetectionEventDeepLink.EVENTS,
        )
        if (webhookDispatched) dispatched.add(DetectionEventAction.WEBHOOK.wireName)
        if (mqttPublisher()?.notifyEvent(alert, deepLink = DetectionEventDeepLink.EVENTS) == true) {
            dispatched.add(DetectionEventAction.MQTT.wireName)
        }
        // Same claim contract: push fires only when a gate is on and at
        // least one target on that transport is subscribed (the verdict is
        // the sender's own).
        val pushed = webPushSender()?.notifyEvent(
            alert,
            eventId = null,
            clipAvailable = false,
            deepLink = DetectionEventDeepLink.EVENTS,
        ) == true
        if (pushed) dispatched.add(DetectionEventAction.PUSH.wireName)
        val notified = store.detectionNotificationsEnabled.value &&
            detectionNotifier()?.notify(EventKind.TEST, emptyList(), alert.snapshotJpegBase64) == true
        if (notified) dispatched.add(DetectionEventAction.NOTIFY.wireName)
        Log.d(TAG, "Test alert dispatched: $dispatched")
        return dispatched
    }

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
        val armed = !respectSchedule || motionArmedNow()
        Log.d(TAG, "Detection event (${kind.wireName}=${String.format(java.util.Locale.US, "%.1f", value)}, zones=$zones, armed=$armed)")

        val dispatchedActions = mutableListOf<DetectionEventAction>()
        if (kind == EventKind.MOTION || kind == EventKind.SOUND) {
            val isSound = kind == EventKind.SOUND
            val action = DetectionEventPolicy.recordingAction(
                motionRecordingEnabled = store.motionRecordingEnabled.value,
                armed = armed,
                recordingActive = recordingController.isRecording.value,
                soundRecordingEnabled = store.soundRecordingEnabled.value,
                isSound = isSound,
            )
            // Continuous-recording interplay: while the NVR-style loop holds
            // the recorder, `recordingActive` is true and the verdict is
            // KEEP_ROLLING — the event-triggered bounded clip is skipped and
            // the event's clip link fields stay null. The event itself (and
            // its alert fan-out) still fires and is still logged.
            when (action) {
                DetectionEventPolicy.RecordingAction.START -> {
                    // Claimed only when the start command reached the
                    // controller without throwing.
                    val started = runCatching { startBoundedRecording(isSound = isSound) }
                        .onFailure { Log.w(TAG, "Bounded recording start failed: ${it.message}") }
                        .isSuccess
                    if (started) {
                        dispatchedActions.add(DetectionEventAction.RECORDING)
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
                    isSound = isSound,
                )
            ) {
                runCatching { photoCaptureManager.captureToGallery() }
                    .onSuccess { dispatchedActions.add(DetectionEventAction.PHOTO) }
                    .onFailure { Log.w(TAG, "Auto-photo after detection failed: ${it.message}") }
            }
        }

        if (armed) {
            scope.launch {
                val snapshot = runCatching {
                    prepareSnapshotBase64(
                        streamingManager()?.latestSnapshotJpeg(),
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
                // The fan-out: every sink gets the same alert and answers its
                // own go/no-go at dispatch time (the settings can flip while
                // the snapshot encodes, and the log must follow the verdict
                // each sink actually acted on, not a pre-encode one). The
                // list order is the historical sequence — webhook, MQTT,
                // Web Push, local notification — and a sink that returns
                // false claims nothing. The quiet-hours hold lives inside
                // the notification sink: it silences the phone only — the
                // remote sinks above already dispatched, and the event
                // still logs.
                val clipAvailable = dispatchedActions.contains(DetectionEventAction.RECORDING)
                sinks.forEach { sink ->
                    if (sink.dispatch(alert, eventId, clipAvailable)) {
                        dispatchedActions.add(sink.action)
                    }
                }
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
                            // The persisted protocol stays the string list it
                            // always was: the enum's wire names ARE the old
                            // ACTION_* constants, byte for byte.
                            dispatchedActions = dispatchedActions.map { it.wireName },
                            zones = zones,
                            labels = labels,
                        ),
                    )
                }.onFailure { Log.w(TAG, "Event log write failed: ${it.message}") }
            }
        }
    }

    /** The auto-siren/auto-torch half of the dispatch, behind the cooldown. */
    private suspend fun runAutoDeterrence(dispatchedActions: MutableList<DetectionEventAction>) {
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
                        dispatchedActions.add(DetectionEventAction.SIREN)
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
                    dispatchedActions.add(DetectionEventAction.TORCH)
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
    private fun startBoundedRecording(isSound: Boolean = false) {
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
                // Clip provenance: the finalized capture's history entry (and
                // with it the timeline) attributes to the event kind that
                // started it.
                trigger = if (isSound) RecordingTrigger.SOUND else RecordingTrigger.MOTION,
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

    private fun currentMinuteOfDay(): Int = minuteOfDayFrom(nowMs())

    /**
     * The ISO day index the arm-schedule mask bits reference (0 = Monday …
     * 6 = Sunday) — the shared [LocalWallClock] derivation, the same numbers
     * the automation scheduler reads.
     */
    private fun currentIsoDayIndex(): Int = isoDayIndexFrom(nowMs())

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

        /** Audio-model auto-request throttle; manual retries bypass it. */
        private const val AUDIO_MODEL_REQUEST_INTERVAL_MS = 60_000L

        /** Bounded waits for the clip watcher: recording live, then finalizing. */
        private const val CLIP_LINK_TIMEOUT_MS = 10 * 60 * 1000L

        /** Clock-granularity slack when matching history entries to the recording's start stamp. */
        private const val CLIP_TIMESTAMP_SLACK_MS = 1_000L

        /**
         * Minute-of-day over the caller's clock — the shared [LocalWallClock]
         * derivation, companion-level so the [sinks] constructor default's
         * quiet-hours lambda can reach it (constructor defaults cannot touch
         * instance members).
         */
        private fun minuteOfDayFrom(nowMsValue: Long): Int =
            LocalWallClock.minuteOfDay(java.util.Calendar.getInstance().apply { timeInMillis = nowMsValue })

        /** The companion twin of [currentIsoDayIndex], same constructor-default rationale. */
        private fun isoDayIndexFrom(nowMsValue: Long): Int =
            LocalWallClock.isoDayIndex(java.util.Calendar.getInstance().apply { timeInMillis = nowMsValue })
    }
}
