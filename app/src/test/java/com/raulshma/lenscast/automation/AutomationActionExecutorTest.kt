package com.raulshma.lenscast.automation

import androidx.camera.core.CameraSelector
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.LensLabelKind
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.StreamToggle
import com.raulshma.lenscast.capture.PhotoCaptureManager
import com.raulshma.lenscast.capture.RecordingController
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.core.SirenAutoStop
import com.raulshma.lenscast.core.SirenPlayer
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.settings.SettingsApplier
import com.raulshma.lenscast.streaming.StreamStateJournal
import com.raulshma.lenscast.streaming.StreamingManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The automation camera-fire's persisted merge base, JVM-tested against a
 * FAKE store whose shared flow LAGS the disk the way the real
 * DataStore → StateFlow share-in does. Issue #18 (0.2.2, jkwanwoo-cloud):
 * a rule configured for ISO, exposure time and frame rate fired in the
 * evening and the ISO/exposure RESET — the configured values briefly
 * reached the camera and then reverted to defaults, and every later camera
 * reset or app restart kept re-applying the reverted store.
 *
 * The mechanism: [AutomationActionExecutor] merged each camera action onto
 * the shared flow's `.value` and saved the merge wholesale. That `.value`
 * lags in two real windows — a fresh process answers the descriptor default
 * until DataStore's first read lands, and a save's share-in hop has no
 * completion edge, so the NEXT action in the same rule run can still read
 * the PREVIOUS action's base. Each save therefore carried the stale base,
 * wiping the rule's own earlier camera writes and every untouched field
 * back to defaults; the Settings Applier faithfully applied both saves, so
 * the camera showed the configured values briefly and then reverted.
 *
 * The contract pinned here: every camera action merges onto the DISK truth
 * (`settingsFromDisk` — the read-your-write seam the store documents for
 * startup-critical reads), so a rule's camera actions accumulate and the
 * persisted world keeps its configured values across restarts and resets.
 * The executor never touches the camera directly — plain camera actions
 * only persist (the applier collector stays the single runtime apply path),
 * and a `torch` action additionally rides the applier's intent-honored
 * apply so its requested pair cannot be converged away when it already
 * equals the applied snapshot (issue #21).
 */
class AutomationActionExecutorTest {

    private val app: MainApplication = mockk()
    private val settingsDataStore: SettingsDataStore = mockk()
    private val cameraService: CameraService = mockk()
    private val settingsApplier: SettingsApplier = mockk()
    private val streamToggle: StreamToggle = mockk(relaxed = true)
    private val streamStateJournal: StreamStateJournal = mockk()
    private val photoCaptureManager: PhotoCaptureManager = mockk()
    private val recordingController: RecordingController = mockk(relaxed = true)
    private val streamingManager: StreamingManager = mockk()
    private val sirenPlayer: SirenPlayer = mockk(relaxed = true)
    private val sirenAutoStop: SirenAutoStop = mockk(relaxed = true)

    /** The store's shared flow — deliberately lagging, like the real share-in. */
    private val settings = MutableStateFlow(CameraSettings())

    /** The disk truth DataStore serves (read-your-write after each edit). */
    private var disk = CameraSettings()

    /** Every document the executor persisted, in order. */
    private val savedDocuments = ArrayList<CameraSettings>()

    /** The reporter's rule shape: ISO + exposure time + frame rate. */
    private val nightRuleActions = listOf(
        AutomationActionSpec("camera_exposure", mapOf("iso" to "3200", "exposure_time_ms" to "250")),
        AutomationActionSpec("camera_fps", mapOf("fps" to "15")),
    )

    @Before
    fun setUp() {
        every { app.settingsDataStore } returns settingsDataStore
        every { app.cameraService } returns cameraService
        every { app.settingsApplier } returns settingsApplier
        every { app.streamToggle } returns streamToggle
        every { app.streamStateJournal } returns streamStateJournal
        every { app.photoCaptureManager } returns photoCaptureManager
        every { app.recordingController } returns recordingController
        every { app.streamingManager } returns streamingManager
        every { app.sirenAutoStop } returns sirenAutoStop
        every { settingsDataStore.recordingAudioEnabled } returns MutableStateFlow(false)
        every { streamingManager.sirenController() } returns sirenPlayer
        coEvery { streamStateJournal.load() } returns StreamStateJournal.State()

        every { settingsDataStore.settings } returns settings
        coEvery { settingsDataStore.settingsFromDisk() } answers { disk }
        // The real store feeds the shared flow ASYNC on the share-in hop; the
        // executor's next read can land inside that window. Modeling the hop
        // as "not landed yet" is the deterministic worst case the bug rides.
        coEvery { settingsDataStore.saveSettings(any()) } answers {
            disk = firstArg()
            savedDocuments.add(firstArg())
        }
        // The real applier seam persists the intent-applied document; model
        // that read-your-write so later actions of a rule accumulate on it.
        coEvery { settingsApplier.applyCameraAction(any(), any()) } answers {
            disk = firstArg()
            savedDocuments.add(firstArg())
        }
        // NOTE: cameraService.isTorchOn is deliberately NOT stubbed — the
        // executor never consults the live LED fact (the torch-touch verdict
        // is CameraService.applySettings', and a declared torch intent rides
        // the applier seam), and the strict mock fails any test whose path
        // tried to read it.
    }

    private fun executor() = AutomationActionExecutor(app)

    // ── the reported failure: a multi-action rule fire reverts its own writes ──

    @Test
    fun `a rule's camera actions accumulate onto the disk truth, not the lagging shared flow`() = runBlocking {
        // Daytime world the store holds on disk (zoom set by hand mid-day);
        // the shared flow still answers the descriptor default — the fresh
        // process / unpropagated share-in state.
        disk = CameraSettings(zoomRatio = 2.5f)
        settings.value = CameraSettings()

        executor().runActions(nightRuleActions)

        // The LAST persisted document is the store's settled truth — the
        // value every later camera reset and app restart re-applies. It must
        // carry ALL THREE configured values AND the untouched daytime zoom:
        // the frame-rate save folded onto the exposure save, not onto the
        // stale default the shared flow was still answering.
        val last = savedDocuments.last()
        assertEquals(3200, last.iso)
        assertEquals(250_000_000L, last.exposureTime)
        assertEquals(15, last.frameRate)
        assertEquals(2.5f, last.zoomRatio)
    }

    @Test
    fun `a fresh process with an unloaded store still merges onto the persisted settings`() = runBlocking {
        // Restart window: disk holds the automation-configured world, the
        // shared flow has not received DataStore's first read yet.
        disk = CameraSettings(frameRate = 15, zoomRatio = 2.5f)
        settings.value = CameraSettings()

        executor().runActions(
            listOf(AutomationActionSpec("camera_exposure", mapOf("iso" to "3200"))),
        )

        val last = savedDocuments.single()
        assertEquals(3200, last.iso)
        // The untouched fields must survive the wholesale save — a merge onto
        // the descriptor default would have wiped them back to defaults.
        assertEquals(15, last.frameRate)
        assertEquals(2.5f, last.zoomRatio)
    }

    // ── sentinel folding rides the same disk base ──

    @Test
    fun `auto sentinels fold to null against the disk base and leave other fields untouched`() = runBlocking {
        // Evening flip back: iso=0/exposure_time_ms=0 are the auto sentinels.
        disk = CameraSettings(iso = 100, exposureTime = 125_000_000L, frameRate = 30, zoomRatio = 1.5f)
        settings.value = CameraSettings()

        executor().runActions(
            listOf(
                AutomationActionSpec("camera_exposure", mapOf("iso" to "0", "exposure_time_ms" to "0")),
            ),
        )

        val last = savedDocuments.single()
        assertNull(last.iso)
        assertNull(last.exposureTime)
        assertEquals(30, last.frameRate)
        assertEquals(1.5f, last.zoomRatio)
    }

    // ── night_vision: the same disk-truth merge, its own settings axis ──

    @Test
    fun `a night_vision action merges its mode onto the disk truth and leaves other fields untouched`() = runBlocking {
        // The daytime world holds manual ISO and a hand-set zoom; the rule
        // only asks for night mode on.
        disk = CameraSettings(iso = 3200, zoomRatio = 2.0f)
        settings.value = CameraSettings()

        executor().runActions(
            listOf(AutomationActionSpec("night_vision", mapOf("mode" to "on"))),
        )

        val last = savedDocuments.single()
        assertEquals(NightVisionMode.ON, last.nightVisionMode)
        // Untouched axes survive the wholesale save.
        assertEquals(3200, last.iso)
        assertEquals(2.0f, last.zoomRatio)
    }

    @Test
    fun `night_vision composes with camera_exposure in one fire, accumulating onto the disk base`() = runBlocking {
        // The reporter's night recipe in one rule: exposure up AND night
        // mode on. The lagging shared flow answers defaults; only the disk
        // base accumulates both writes.
        disk = CameraSettings()
        settings.value = CameraSettings()

        executor().runActions(
            listOf(
                AutomationActionSpec("camera_exposure", mapOf("iso" to "3200", "exposure_time_ms" to "250")),
                AutomationActionSpec("night_vision", mapOf("mode" to "on")),
            ),
        )

        val last = savedDocuments.last()
        assertEquals(3200, last.iso)
        assertEquals(250_000_000L, last.exposureTime)
        assertEquals(NightVisionMode.ON, last.nightVisionMode)
    }

    @Test
    fun `a night_vision restore rewinds only the mode axis`() = runBlocking {
        // Pre-test world: night mode on, manual exposure configured by the
        // user (NOT the rule's doing). The restore action list carries the
        // mode only — the exposure fields ride the disk base untouched.
        disk = CameraSettings(nightVisionMode = NightVisionMode.ON, iso = 1600)
        settings.value = CameraSettings()

        executor().runActions(
            listOf(AutomationActionSpec("night_vision", mapOf("mode" to "off"))),
        )

        val last = savedDocuments.single()
        assertEquals(NightVisionMode.OFF, last.nightVisionMode)
        assertEquals(1600, last.iso)
    }

    // ── ownership: torch rides the intent seam, everything else persists ──

    @Test
    fun `a torch action drives the requested pair through the applier's intent seam`() = runBlocking {
        // The 0.2.3-beta.1 on-device repro (issue #21): stored intent says
        // LIT — an earlier convergence wrote it there — while the live LED
        // sits dark. The requested pair already equals the previously
        // applied snapshot, so the verdict's pair diff reads "untouched"
        // and a plain persist would converge the request away toward the
        // live fact: rule reports success, LED never moves. The declared
        // intent must reach the applier's intent-honored apply instead.
        disk = CameraSettings(torchEnabled = true, torchStrength = 3)
        settings.value = CameraSettings()

        executor().runActions(listOf(AutomationActionSpec("torch", mapOf("enabled" to "true"))))

        val doc = slot<CameraSettings>()
        coVerify(exactly = 1) { settingsApplier.applyCameraAction(capture(doc), true) }
        // The merge still folds verbatim onto the disk base — intent only
        // changes HOW the document lands, never WHAT was merged.
        assertTrue(doc.captured.torchEnabled)
        assertEquals(3, doc.captured.torchStrength)
    }

    @Test
    fun `a torch-off action declares the false intent`() = runBlocking {
        // The mirror case: a live-only writer lit the LED while the stored
        // intent says dark; turning the torch off is a request, not a carry.
        disk = CameraSettings(torchEnabled = false)
        settings.value = CameraSettings()

        executor().runActions(listOf(AutomationActionSpec("torch", mapOf("enabled" to "false"))))

        val doc = slot<CameraSettings>()
        coVerify(exactly = 1) { settingsApplier.applyCameraAction(capture(doc), false) }
        assertTrue(!doc.captured.torchEnabled)
    }

    @Test
    fun `a torch spec without an enabled verdict stays on the plain persist path`() = runBlocking {
        // enabled is a required param (the sanitizer folds an absence to
        // "true"), so this is only a hand-built spec's shape — and the
        // merge's contract says an absent enabled leaves the torch alone:
        // no intent is declared, the save rides the store, and the verdict
        // stays the single owner of what the LED does.
        disk = CameraSettings(torchEnabled = true, torchStrength = 3)
        settings.value = CameraSettings()

        executor().runActions(listOf(AutomationActionSpec("torch", mapOf("strength" to "5"))))

        coVerify(exactly = 0) { settingsApplier.applyCameraAction(any(), any()) }
        val last = savedDocuments.single()
        assertTrue(last.torchEnabled)
        assertEquals(5, last.torchStrength)
    }

    @Test
    fun `camera actions persist through the store and never drive the camera directly`() = runBlocking {
        disk = CameraSettings()
        settings.value = CameraSettings()

        executor().runActions(nightRuleActions)

        // One save per camera action, and NO second application path — the
        // exposure/fps merges declare no torch intent, so they ride the
        // store and the applier collector stays the single runtime apply.
        coVerify(exactly = 2) { settingsDataStore.saveSettings(any()) }
        coVerify(exactly = 0) { settingsApplier.applyCameraAction(any(), any()) }
        coVerify(exactly = 0) { cameraService.applySettings(any(), any()) }
        // The last document exists and is the accumulated one.
        assertNotNull(savedDocuments.lastOrNull())
        assertEquals(15, savedDocuments.last().frameRate)
    }

    // ── the dispatch vocabulary: each AutomationEffect interprets at its seam ──
    // The old per-action `when` over ids is the catalog entry's declared
    // effects now; these tests pin the interpreter against each collaborator.

    @Test
    fun `unknown action ids skip silently`() = runBlocking {
        executor().runActions(listOf(AutomationActionSpec("not_a_real_action", mapOf("enabled" to "true"))))
        coVerify(exactly = 0) { settingsDataStore.saveSettings(any()) }
        coVerify(exactly = 0) { streamToggle.stopServer() }
    }

    @Test
    fun `stream action drives the boot-resume toggle start and stop verdicts`() = runBlocking {
        executor().runActions(
            listOf(
                AutomationActionSpec("stream", mapOf("enabled" to "true")),
                AutomationActionSpec("stream", mapOf("enabled" to "false")),
            ),
        )
        // tileStart over the never-lived journal defaults to the WEB output.
        coVerifyOrder {
            streamToggle.startWeb()
            streamToggle.stopServer()
        }
    }

    @Test
    fun `recording action starts with the clamped duration verdict and stops`() = runBlocking {
        val started = slot<RecordingConfig>()
        executor().runActions(
            listOf(
                AutomationActionSpec("recording", mapOf("enabled" to "true", "duration_seconds" to "7200")),
                AutomationActionSpec("recording", mapOf("enabled" to "false")),
            ),
        )
        verifyOrder {
            recordingController.start(capture(started))
            recordingController.stop()
        }
        // 7200 requested, the RecordingConfig ceiling (1 h) is the verdict.
        assertEquals(3_600L, started.captured.durationSeconds)
    }

    @Test
    fun `siren action arms the shared auto-stop only on an explicit duration`() = runBlocking {
        executor().runActions(
            listOf(
                // On with a duration: start + arm the shared timer.
                AutomationActionSpec("siren", mapOf("enabled" to "true", "duration_seconds" to "10")),
                // On without: run until stopped — an already-armed stop stays armed.
                AutomationActionSpec("siren", mapOf("enabled" to "true")),
                // Off: cancel the timer, then stop.
                AutomationActionSpec("siren", mapOf("enabled" to "false")),
            ),
        )
        // Both armed runs drive the siren start (run-until-stopped still
        // starts); only the explicit duration arms the shared timer.
        verify(exactly = 2) { sirenPlayer.start() }
        verify(exactly = 1) { sirenAutoStop.armAfterStart(10_000L, any()) }
        verifyOrder {
            sirenAutoStop.cancel()
            sirenPlayer.stop()
        }
    }

    @Test
    fun `capture_photo action captures fire-and-forget through the gallery seam`() = runBlocking {
        every { photoCaptureManager.captureToGallery(any(), any(), any()) } returns "photo.jpg"
        executor().runActions(listOf(AutomationActionSpec("capture_photo")))
        verify(exactly = 1) { photoCaptureManager.captureToGallery(any(), any(), any()) }
    }

    @Test
    fun `detection action saves only the toggles the params carry`() = runBlocking {
        coEvery { settingsDataStore.saveMotionDetectionEnabled(any()) } returns Unit
        coEvery { settingsDataStore.saveSoundDetectionEnabled(any()) } returns Unit
        executor().runActions(
            listOf(
                AutomationActionSpec("detection", mapOf("motion_enabled" to "false")),
                AutomationActionSpec("detection", mapOf("sound_enabled" to "true")),
            ),
        )
        coVerify(exactly = 1) { settingsDataStore.saveMotionDetectionEnabled(false) }
        coVerify(exactly = 1) { settingsDataStore.saveSoundDetectionEnabled(true) }
    }

    @Test
    fun `camera_lens action clamps into the live inventory and an empty inventory skips silently`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            // Empty inventory: skip rather than guess.
            every { cameraService.availableLenses } returns MutableStateFlow(emptyList())
            executor().runActions(listOf(AutomationActionSpec("camera_lens", mapOf("index" to "0"))))
            verify(exactly = 0) { cameraService.selectLens(any()) }

            // Out-of-range index clamps into the live enumeration.
            every { cameraService.availableLenses } returns MutableStateFlow(
                listOf(
                    CameraLensInfo("0", LensLabelKind.WIDE, CameraSelector.LENS_FACING_BACK, 26f, CameraSelector.DEFAULT_BACK_CAMERA),
                    CameraLensInfo("1", LensLabelKind.FRONT, CameraSelector.LENS_FACING_FRONT, 20f, CameraSelector.DEFAULT_FRONT_CAMERA),
                ),
            )
            executor().runActions(listOf(AutomationActionSpec("camera_lens", mapOf("index" to "5"))))
            verify(exactly = 1) { cameraService.selectLens(1) }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
