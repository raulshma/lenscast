package com.raulshma.lenscast.capture

import android.Manifest
import android.app.Application
import android.content.Context
import com.raulshma.lenscast.capture.ml.AnalysisFrame
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.capture.ml.ObjectClassifier
import com.raulshma.lenscast.capture.ml.ObjectDetectionEngine
import com.raulshma.lenscast.capture.ml.SoundClassifier
import com.raulshma.lenscast.capture.model.DetectionClassPolicy
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.DetectionAlert
import com.raulshma.lenscast.core.EventKind
import com.raulshma.lenscast.core.LocalWallClock
import com.raulshma.lenscast.core.SirenAutoStop
import com.raulshma.lenscast.core.WebhookNotifier
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.data.SettingsDataStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * First Robolectric pass over the REAL [DetectionCoordinator] dispatch — the
 * coordinator itself under test, with its collaborators either real
 * (SettingsDataStore, DetectionEventStore, RecordingController), inert by
 * construction (the webhook notifier configured to never dispatch, null
 * provider lambdas), or faked across the seams this refactor opened (the
 * [DetectionSink] fan-out list, the [ObjectClassifier]/[SoundClassifier]
 * classifier seams, the analysis-frame provider).
 *
 * Every test pins one CURRENT behavior discovered by reading the dispatch:
 * - the ML gate ladder: suppress-only-on-empty-verdict, throttle between
 *   frames, single-flight (a burst event passes through unlabeled, never
 *   waits on inference), fail-open on a crashed or unavailable engine;
 * - the sink fan-out: historical sink order, one alert shape for every
 *   sink, the claimed actions riding the log with the exact strings the
 *   feed has always persisted, and — pinned, not fixed — a sink that
 *   THROWS aborts the later sinks AND the event-log write (today's
 *   semantics: the real sinks are fail-open by contract, and the
 *   coordinator adds no try/catch of its own);
 * - quiet hours: the notification sink is held, the webhook claim and the
 *   log entry are not (quiet hours silence the phone, not the integrations);
 * - the arm schedule: a disarmed motion dispatches nothing and logs
 *   nothing, while tamper bypasses the schedule and never records or
 *   auto-photos;
 * - the sound-classification seam feed gating.
 *
 * The coordinator's dispatch scope is injected as `Dispatchers.Unconfined`,
 * so the whole fan-out runs synchronously on the caller up to the first
 * real suspension — the ML-gate tests only await because their verdicts
 * land on the coordinator's single ML worker thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetectionCoordinatorTest {

    private lateinit var context: Context
    private lateinit var store: SettingsDataStore
    private lateinit var eventStore: DetectionEventStore
    private lateinit var recordingController: RecordingController

    /** The mutable clock the coordinator reads; tests advance it to drive the throttle. */
    private var nowMsValue = BASE_NOW_MS

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // One SettingsDataStore for the whole class run: the underlying
        // DataStore is already a classloader-wide singleton (see the reset
        // note below), and a fresh instance per test would spin up ~a hundred
        // new Eagerly-shared flows per method against the same store.
        // shareInScope is Unconfined on purpose: the default Default-pool
        // scope makes every setFlag/awaitFlag hostage to worker scheduling,
        // which under a loaded JVM (this box runs several builds at once)
        // stalled a write's emission past the 30 s budget. Unconfined
        // propagates each re-emission on the writing thread, so settings
        // land independent of pool load.
        if (!::store.isInitialized) {
            store = SettingsDataStore(
                context,
                shareInScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            )
        }
        eventStore = DetectionEventStore.get(context)
        eventStore.clear()
        recordingController = RecordingController(context)
        nowMsValue = BASE_NOW_MS
        resetDetectionSettings()
    }

    /**
     * The `preferencesDataStore` delegate is a classloader singleton, so the
     * store — and its persisted file — spans every test in this class run: a
     * flag one test saves leaks into the next (a leftover disarmed arm
     * schedule silently drops later sound events). Every test therefore
     * starts from the persisted defaults its dispatch assumes.
     */
    private fun resetDetectionSettings() {
        setFlag(store.motionArmScheduleEnabled, expected = false) { store.saveMotionArmScheduleEnabled(false) }
        setFlag(store.alertQuietHoursEnabled, expected = false) { store.saveAlertQuietHoursEnabled(false) }
        setFlag(store.mlDetectionEnabled, expected = false) { store.saveMlDetectionEnabled(false) }
        setFlag(store.motionRecordingEnabled, expected = false) { store.saveMotionRecordingEnabled(false) }
        setFlag(store.soundDetectionEnabled, expected = false) { store.saveSoundDetectionEnabled(false) }
        setFlag(store.soundClassificationEnabled, expected = false) { store.saveSoundClassificationEnabled(false) }
        setFlag(store.soundRecordingEnabled, expected = false) { store.saveSoundRecordingEnabled(false) }
    }

    // ── the ML gate ladder, on a fake classifier ──

    @Test
    fun `an empty allowed-class verdict suppresses the motion event entirely`() {
        val classifier = FakeObjectClassifier { ObjectDetectionEngine.Classification.Success(emptyList()) }
        val webhook = FakeSink(DetectionEventAction.WEBHOOK)
        val coordinator = coordinator(
            sinks = listOf(webhook),
            objectClassifier = classifier,
            analysisFrame = testFrame(),
        )
        enableMlGate()
        setFlag(store.motionRecordingEnabled) { store.saveMotionRecordingEnabled(true) }

        coordinator.onMotion(42.0)
        awaitUntil { classifier.classifyCalls.get() == 1 }
        // The suppressed verdict runs on the ML worker right after classify
        // returns — settle (suppression is a silent non-dispatch, there is
        // nothing observable to await), then pin the silence: no sinks, no
        // log entry.
        Thread.sleep(500)

        assertEquals(0, eventStore.count())
        assertTrue(webhook.dispatched.isEmpty())
    }

    @Test
    fun `an allowed-class verdict lets the event through with its labels, clip hint, and one alert identity`() {
        val classifier = FakeObjectClassifier {
            ObjectDetectionEngine.Classification.Success(
                listOf(
                    DetectionClassPolicy.Detection("person", 0.9f),
                    DetectionClassPolicy.Detection("car", 0.2f), // below the 60% floor
                ),
            )
        }
        val webhook = FakeSink(DetectionEventAction.WEBHOOK)
        val mqtt = FakeSink(DetectionEventAction.MQTT)
        val push = FakeSink(DetectionEventAction.PUSH)
        val notify = FakeSink(DetectionEventAction.NOTIFY)
        val coordinator = coordinator(
            sinks = listOf(webhook, mqtt, push, notify),
            objectClassifier = classifier,
            analysisFrame = testFrame(),
        )
        enableMlGate()
        setFlag(store.motionRecordingEnabled) { store.saveMotionRecordingEnabled(true) }

        coordinator.onMotion(42.0)
        awaitUntil { eventStore.count() == 1 }

        val entry = eventStore.events().single()
        assertEquals("motion", entry.type)
        assertEquals(listOf("person"), entry.labels)
        // Recording claimed first, then the four sinks in the historical order.
        assertEquals(
            listOf("recording", "webhook", "mqtt", "push", "notify"),
            entry.dispatchedActions,
        )
        // One alert shape feeds every sink, carrying the gate's labels.
        val alerts = listOf(webhook, mqtt, push, notify).map { sink -> sink.dispatched.single().alert }
        assertTrue(alerts.all { it === alerts[0] }) // literally one instance
        assertEquals(EventKind.MOTION, alerts[0].kind)
        assertEquals(listOf("person"), alerts[0].labels)
        assertNull(alerts[0].snapshotJpegBase64) // no streaming manager in this test
        // The push sink carries the event id and the clip hint: the bounded
        // recording was claimed by this same dispatch.
        assertEquals(entry.id, push.dispatched.single().eventId)
        assertTrue(push.dispatched.single().clipAvailable)
    }

    @Test
    fun `a burst inside the throttle window passes through unlabeled without a second classify`() {
        val classifier = FakeObjectClassifier {
            ObjectDetectionEngine.Classification.Success(listOf(DetectionClassPolicy.Detection("person", 0.9f)))
        }
        val coordinator = coordinator(
            sinks = emptyList(),
            objectClassifier = classifier,
            analysisFrame = testFrame(),
        )
        enableMlGate()
        setFlag(store.motionRecordingEnabled) { store.saveMotionRecordingEnabled(true) }

        coordinator.onMotion(1.0)
        awaitUntil { eventStore.count() == 1 } // classified event, labeled
        assertEquals(1, classifier.classifyCalls.get())

        nowMsValue += 500 // < the 1s ML throttle window
        coordinator.onMotion(2.0)
        awaitUntil { eventStore.count() == 2 }

        val second = eventStore.events().first() // newest first
        assertTrue(second.labels.isEmpty()) // fail-open: shipped unfiltered
        assertEquals(1, classifier.classifyCalls.get()) // the burst never reached the engine
    }

    @Test
    fun `a burst arriving mid-classification passes through unlabeled instead of waiting (single-flight)`() {
        val classifier = FakeObjectClassifier {
            ObjectDetectionEngine.Classification.Success(listOf(DetectionClassPolicy.Detection("person", 0.9f)))
        }
        classifier.block = CountDownLatch(1)
        val coordinator = coordinator(
            sinks = emptyList(),
            objectClassifier = classifier,
            analysisFrame = testFrame(),
        )
        enableMlGate()
        setFlag(store.motionRecordingEnabled) { store.saveMotionRecordingEnabled(true) }

        coordinator.onMotion(1.0) // occupies the single ML worker
        coordinator.onMotion(2.0) // busy → fail-open, dispatched synchronously on this thread
        awaitUntil { eventStore.count() == 1 }
        assertTrue(eventStore.events().single().labels.isEmpty())

        classifier.block?.countDown() // let the first verdict land
        awaitUntil { eventStore.count() == 2 }
        assertEquals(listOf("person"), eventStore.events().first { it.labels.isNotEmpty() }.labels)
        assertEquals(1, classifier.classifyCalls.get()) // one in flight at a time
    }

    @Test
    fun `a crashed classification fails open and ships the event unlabeled`() {
        val classifier = FakeObjectClassifier { throw IllegalStateException("inference boom") }
        val coordinator = coordinator(
            sinks = emptyList(),
            objectClassifier = classifier,
            analysisFrame = testFrame(),
        )
        enableMlGate()
        setFlag(store.motionRecordingEnabled) { store.saveMotionRecordingEnabled(true) }

        coordinator.onMotion(7.0)
        awaitUntil { eventStore.count() == 1 }

        val entry = eventStore.events().single()
        assertTrue(entry.labels.isEmpty())
        assertEquals(1, classifier.classifyCalls.get())
    }

    @Test
    fun `an unavailable verdict is not a suppression - the event passes through ungated`() {
        val classifier = FakeObjectClassifier { ObjectDetectionEngine.Classification.Unavailable }
        val coordinator = coordinator(
            sinks = emptyList(),
            objectClassifier = classifier,
            analysisFrame = testFrame(),
        )
        enableMlGate()
        setFlag(store.motionRecordingEnabled) { store.saveMotionRecordingEnabled(true) }

        coordinator.onMotion(7.0)
        awaitUntil { eventStore.count() == 1 }

        assertTrue(eventStore.events().single().labels.isEmpty())
    }

    // ── the sink fan-out ──

    @Test
    fun `sinks fan out in the historical order and the log lists the claimed actions as wire names`() {
        val webhook = FakeSink(DetectionEventAction.WEBHOOK)
        val mqtt = FakeSink(DetectionEventAction.MQTT)
        val push = FakeSink(DetectionEventAction.PUSH)
        val notify = FakeSink(DetectionEventAction.NOTIFY)
        val uncaught = mutableListOf<Throwable>()
        val coordinator = coordinator(sinks = listOf(webhook, mqtt, push, notify), uncaught = uncaught)

        coordinator.onSound(55.0) // sound: no recording, no auto-photo, fully synchronous

        // Every sink dispatched exactly once, in list order — and the
        // dispatch coroutine itself never faulted. The sinks dispatch inside
        // the fan-out's launch (yielding at the suspending log write), so
        // await the row the same way its assertions below do.
        awaitUntil { eventStore.count() == 1 }
        assertTrue("fan-out faulted: $uncaught", uncaught.isEmpty())
        listOf(webhook, mqtt, push, notify).forEach { sink ->
            assertEquals(1, sink.dispatched.size)
        }
        val alert = webhook.dispatched.single().alert
        assertEquals(EventKind.SOUND, alert.kind)
        assertEquals(55.0, alert.value, 0.0)
        // One alert instance for the whole fan-out.
        assertTrue(notify.dispatched.single().alert === alert)
        // Sound recording is off → the recording was not claimed → no clip hint.
        assertTrue(!push.dispatched.single().clipAvailable)
        assertEquals(
            listOf("webhook", "mqtt", "push", "notify"),
            eventStore.events().single().dispatchedActions,
        )
    }

    @Test
    fun `a sink dispatch returns false claims nothing - the no-go sink is absent from the log`() {
        val webhook = FakeSink(DetectionEventAction.WEBHOOK, verdict = false)
        val mqtt = FakeSink(DetectionEventAction.MQTT)
        val coordinator = coordinator(sinks = listOf(webhook, mqtt))

        coordinator.onSound(10.0)

        // The fan-out launches (yielding at the suspending log write): await
        // the row whose dispatchedActions pins the no-go sink's absence.
        awaitUntil { eventStore.count() == 1 }
        assertEquals(1, webhook.dispatched.size) // called, answered no
        assertEquals(listOf("mqtt"), eventStore.events().single().dispatchedActions)
    }

    @Test
    fun `a throwing sink aborts the later sinks AND the log write (pinned, not fixed)`() {
        val webhook = FakeSink(DetectionEventAction.WEBHOOK).apply {
            throwable = IllegalStateException("sink blew up")
        }
        val mqtt = FakeSink(DetectionEventAction.MQTT)
        val uncaught = mutableListOf<Throwable>()
        val coordinator = coordinator(sinks = listOf(webhook, mqtt), uncaught = uncaught)

        coordinator.onSound(10.0)
        awaitUntil { uncaught.isNotEmpty() }

        assertTrue(mqtt.dispatched.isEmpty()) // fan-out stopped at the thrower
        assertEquals(0, eventStore.count()) // the entry (and deterrence) never ran
        assertEquals("sink blew up", uncaught.single().message)
    }

    // ── quiet hours: silence the phone, not the integrations ──

    @Test
    fun `quiet hours hold the notification sink but not the webhook dispatch or the log entry`() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val webhook = FakeSink(DetectionEventAction.WEBHOOK)
        val notifierSink = NotificationDetectionSink(
            notifier = { DetectionNotifier(context) },
            store = store,
            minuteOfDay = { minuteOfDayNow() },
        )
        val uncaught = mutableListOf<Throwable>()
        val coordinator = coordinator(sinks = listOf(webhook, notifierSink), uncaught = uncaught)

        val minute = minuteOfDayNow()
        setFlag(store.alertQuietHoursEnabled) { store.saveAlertQuietHoursEnabled(true) }
        setInt(store.alertQuietHoursStartMinute, minute) { store.saveAlertQuietHoursStartMinute(it) }
        setInt(store.alertQuietHoursEndMinute, (minute + 30) % 1440) { store.saveAlertQuietHoursEndMinute(it) }

        coordinator.onSound(50.0)
        // The log write is the fan-out's suspending tail — under Unconfined
        // the launch yields at the DataStore persist, so the row lands after
        // onSound returns. Await it like every other event-row assertion.
        awaitUntil("the held event never logged") { eventStore.events().isNotEmpty() }
        assertTrue("fan-out faulted: $uncaught", uncaught.isEmpty())
        assertEquals(
            listOf("webhook"),
            eventStore.events().single().dispatchedActions,
        )

        // The window opens: the same choreography now claims the notification.
        setFlag(store.alertQuietHoursEnabled, expected = false) { store.saveAlertQuietHoursEnabled(false) }
        coordinator.onSound(51.0)
        awaitUntil("the claimed event never logged") {
            eventStore.events().any { it.dispatchedActions.contains("notify") }
        }
        assertEquals(
            listOf("webhook", "notify"),
            eventStore.events().first { it.dispatchedActions.contains("notify") }.dispatchedActions,
        )
    }

    // ── the arm schedule ──

    @Test
    fun `a schedule-disarmed motion dispatches nothing and writes no log entry`() {
        val webhook = FakeSink(DetectionEventAction.WEBHOOK)
        val coordinator = coordinator(sinks = listOf(webhook))
        disarmScheduleForNow()

        coordinator.onMotion(20.0) // ML gate off: straight to onEvent, dropped by the arm verdict

        assertTrue(webhook.dispatched.isEmpty())
        assertEquals(0, eventStore.count())
    }

    @Test
    fun `tamper bypasses the arm schedule and never claims recording or photo`() {
        val webhook = FakeSink(DetectionEventAction.WEBHOOK)
        val mqtt = FakeSink(DetectionEventAction.MQTT)
        val coordinator = coordinator(sinks = listOf(webhook, mqtt))
        disarmScheduleForNow() // the motion schedule is disarmed right now
        setFlag(store.motionRecordingEnabled) { store.saveMotionRecordingEnabled(true) }

        coordinator.onTamper(85)

        // The fan-out launches (yielding at the suspending log write): await
        // the tamper row before pinning its claimed actions.
        awaitUntil { eventStore.count() == 1 }
        val entry = eventStore.events().single()
        assertEquals("tamper", entry.type) // alerted despite the disarmed window
        assertEquals(
            listOf("webhook", "mqtt"), // no recording, no photo — tamper records nothing
            entry.dispatchedActions,
        )
        assertEquals(85.0, webhook.dispatched.single().alert.value, 0.0)
    }

    // ── the action vocabulary on the wire ──

    @Test
    fun `the event-log action vocabulary byte-matches the historical string constants`() {
        assertEquals(
            listOf("recording", "photo", "webhook", "mqtt", "push", "notify", "siren", "torch"),
            DetectionEventAction.entries.map { it.wireName },
        )
    }

    // ── the test alert stays outside the fan-out seam ──

    @Test
    fun `the dashboard test alert dispatches through the raw deps and never writes the log`() = runBlocking {
        val coordinator = coordinator(sinks = emptyList())

        // Every dependency here is inert (no webhook URL, null providers),
        // so nothing claims — and the test alert never touches the log.
        assertEquals(emptyList<String>(), coordinator.dispatchTestAlert())
        assertEquals(0, eventStore.count())
    }

    // ── the sound-classification seam ──

    @Test
    fun `sound classification feeds only reach the classifier when both sound gates are on`() {
        val soundClassifier = FakeSoundClassifier()
        val coordinator = coordinator(sinks = emptyList(), soundClassifier = soundClassifier)
        val chunk = ByteArray(64) { it.toByte() }

        coordinator.feedSoundClassification(chunk, 48_000, 1) // gates off (the defaults)
        assertTrue(soundClassifier.feeds.isEmpty())

        setFlag(store.soundDetectionEnabled) { store.saveSoundDetectionEnabled(true) }
        setFlag(store.soundClassificationEnabled) { store.saveSoundClassificationEnabled(true) }
        coordinator.feedSoundClassification(chunk, 48_000, 1)

        assertEquals(1, soundClassifier.feeds.size)
        assertTrue(soundClassifier.feeds.single().first.contentEquals(chunk))
        assertEquals(48_000, soundClassifier.feeds.single().second)
        assertEquals(1, soundClassifier.feeds.single().third)
    }

    // ── harness ──

    private fun coordinator(
        sinks: List<DetectionSink>,
        objectClassifier: ObjectClassifier? = null,
        soundClassifier: SoundClassifier? = null,
        analysisFrame: AnalysisFrame? = null,
        uncaught: MutableList<Throwable>? = null,
    ): DetectionCoordinator {
        val handler = CoroutineExceptionHandler { _, throwable -> uncaught?.add(throwable) }
        // The real stores with an offline connection factory: the gated
        // paths' model auto-requests fail cleanly into the stores' own
        // Failed state instead of touching a network.
        val offline = { _: String -> throw IOException("offline test") }
        return DetectionCoordinator(
            settingsDataStore = store,
            recordingController = recordingController,
            photoCaptureManager = PhotoCaptureManager(
                context,
                CameraService(context),
                CaptureHistoryStore(context),
            ),
            // Inert: willDispatch answers no, so the notifier never queues
            // a real POST behind the fake-sink fan-outs.
            webhookNotifier = WebhookNotifier({ false to "" }),
            eventStore = eventStore,
            appContext = context,
            detectionModelStore = DetectionModelStore(
                modelsDir = File(context.filesDir, "test-detection-models"),
                connectionFactory = offline,
            ),
            audioModelStore = AudioModelStore(
                modelsDir = File(context.filesDir, "test-audio-models"),
                connectionFactory = offline,
            ),
            streamingManager = { null },
            cameraService = { null },
            batteryPercent = { null },
            sirenAutoStop = SirenAutoStop(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)),
            nowMs = { nowMsValue },
            objectClassifier = objectClassifier,
            soundClassifier = soundClassifier,
            analysisFrameProvider = { analysisFrame },
            sinks = sinks,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler),
        )
    }

    private fun enableMlGate() {
        setFlag(store.mlDetectionEnabled) { store.saveMlDetectionEnabled(true) }
    }

    /** A motion window that excludes "now" whatever the sandbox time zone: [m+10, m+20] never covers m. */
    private fun disarmScheduleForNow() {
        val minute = minuteOfDayNow()
        setFlag(store.motionArmScheduleEnabled) { store.saveMotionArmScheduleEnabled(true) }
        setInt(store.motionArmStartMinute, (minute + 10) % 1440) { store.saveMotionArmStartMinute(it) }
        setInt(store.motionArmEndMinute, (minute + 20) % 1440) { store.saveMotionArmEndMinute(it) }
    }

    /** The same minute-of-day derivation the coordinator's quiet/arm verdicts read. */
    private fun minuteOfDayNow(): Int =
        LocalWallClock.minuteOfDay(Calendar.getInstance().apply { timeInMillis = nowMsValue })

    private fun testFrame() = AnalysisFrame(ByteArray(32), 8, 4)

    /**
     * Saves through DataStore until the shared StateFlow reflects the value.
     * One re-save per miss: under parallel-build load a write's emission to
     * the derived flows can lag, and a fresh write always re-triggers one.
     */
    private fun setFlag(flow: StateFlow<Boolean>, expected: Boolean = true, save: suspend () -> Unit) {
        runBlocking {
            var attempts = 0
            while (flow.value != expected) {
                check(++attempts <= 5) { "setting never propagated to $expected (still ${flow.value})" }
                save()
                awaitFlag(flow, expected)
            }
        }
    }

    private fun setInt(flow: StateFlow<Int>, value: Int, save: suspend (Int) -> Unit) {
        runBlocking {
            var attempts = 0
            while (flow.value != value) {
                check(++attempts <= 5) { "setting never propagated to $value (still ${flow.value})" }
                save(value)
                awaitUntil("setting never propagated (still ${flow.value})") { flow.value == value }
            }
        }
    }

    private fun awaitFlag(flow: StateFlow<Boolean>, expected: Boolean) {
        awaitUntil("setting never propagated (still ${flow.value}, want $expected)") {
            flow.value == expected
        }
    }

    private fun awaitUntil(message: String = "condition never became true", condition: () -> Boolean) {
        // Generous budget: the box runs several Gradle builds in parallel and
        // DataStore's writes serialize through one actor, so flow propagation
        // and the ML worker can lag for many seconds under load.
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { message }
            Thread.sleep(10)
        }
    }

    /** A sink that records its dispatches and answers [verdict] (or throws [throwable]). */
    private class FakeSink(
        override val action: DetectionEventAction,
        private val verdict: Boolean = true,
    ) : DetectionSink {

        class Dispatch(val alert: DetectionAlert, val eventId: String, val clipAvailable: Boolean)

        val dispatched = mutableListOf<Dispatch>()
        var throwable: Throwable? = null

        override suspend fun dispatch(
            alert: DetectionAlert,
            eventId: String,
            clipAvailable: Boolean,
        ): Boolean {
            synchronized(dispatched) { dispatched += Dispatch(alert, eventId, clipAvailable) }
            throwable?.let { throw it }
            return verdict
        }
    }

    /**
     * The classifier seam fake: [outcome] answers each classify. [block],
     * when armed, holds the ML worker inside classify so a test can pin the
     * single-flight half of the ladder.
     */
    private class FakeObjectClassifier(
        private val outcome: (AnalysisFrame) -> ObjectDetectionEngine.Classification,
    ) : ObjectClassifier {

        val classifyCalls = AtomicInteger(0)
        @Volatile var block: CountDownLatch? = null

        override fun classify(frame: AnalysisFrame): ObjectDetectionEngine.Classification {
            classifyCalls.incrementAndGet()
            block?.await()
            return outcome(frame)
        }
    }

    /** The sound seam fake: records every chunk that reaches the classifier. */
    private class FakeSoundClassifier : SoundClassifier {

        val feeds = mutableListOf<Triple<ByteArray, Int, Int>>()

        override fun feed(pcm16: ByteArray, sampleRateHz: Int, channelCount: Int) {
            synchronized(feeds) { feeds += Triple(pcm16, sampleRateHz, channelCount) }
        }
    }

    private companion object {
        /** A fixed wall-clock stamp; tests only ever advance it, never read the real clock. */
        const val BASE_NOW_MS = 1_700_000_000_000L
    }
}
