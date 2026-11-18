package com.raulshma.lenscast.automation

import androidx.camera.core.CameraSelector
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.capture.RecordingState
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.SelectedLens
import com.raulshma.lenscast.capture.RecordingController
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.settings.SettingsApplier
import com.raulshma.lenscast.streaming.StreamingManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Automation Test controller's restore-vs-apply ordering, JVM-tested
 * against a FAKE applier: the POCO F1 on-device failure class — restore
 * rewound the persisted store but the live camera kept the fired state —
 * happened because restore never joined the applier's in-flight camera apply
 * and never drove the live session itself. These tests pin the new contract:
 * restore (1) joins the pending arm-side apply BEFORE its own live apply,
 * (2) applies the snapshot's touched axes (auto where the snapshot had auto,
 * torch off where it was off), (3) is idempotent — nothing armed or a second
 * restore is a no-op — and (4) snapshots from DISK at arm time, so the
 * shared flow's lagging `.value` can never become the "pre-test" state (the
 * store-left-unreset cycle).
 */
class AutomationTestControllerTest {

    private val app: MainApplication = mockk()
    private val settingsDataStore: SettingsDataStore = mockk()
    private val cameraService: CameraService = mockk()
    private val streamingManager: StreamingManager = mockk()
    private val recordingController: RecordingController = mockk()
    private val applier: SettingsApplier = mockk()

    /** The store's shared flow — deliberately stale-able, like the real share-in. */
    private val settings = MutableStateFlow(CameraSettings())

    /** The disk truth `settingsFromDisk()` answers (read-your-write). */
    private var diskWorld = CameraSettings()

    /** The rule under test: fire manual ISO + torch on; restore rewinds both. */
    private val rule = AutomationRule(
        id = "r1",
        name = "deterrence",
        actions = listOf(
            AutomationActionSpec("camera_exposure", mapOf("iso" to "1600")),
            AutomationActionSpec("torch", mapOf("enabled" to "true")),
        ),
    )

    @Before
    fun setUp() {
        every { app.settingsDataStore } returns settingsDataStore
        every { app.cameraService } returns cameraService
        every { app.streamingManager } returns streamingManager
        every { app.recordingController } returns recordingController
        every { app.settingsApplier } returns applier

        every { settingsDataStore.settings } returns settings
        coEvery { settingsDataStore.automationRulesFromDisk() } returns listOf(rule)
        // The real store serves a save read-your-write on disk immediately
        // and delivers it to the shared flow on the async share-in hop; the
        // default stub models both (the hop lands inside these tests).
        coEvery { settingsDataStore.saveSettings(any()) } answers {
            diskWorld = firstArg()
            settings.value = firstArg()
        }
        // Default disk world: auto ISO/exposure, torch dark.
        coEvery { settingsDataStore.settingsFromDisk() } answers { diskWorld }
        coEvery { settingsDataStore.motionDetectionEnabledFromDisk() } returns false
        coEvery { settingsDataStore.soundDetectionEnabledFromDisk() } returns true

        every { cameraService.selectedLens } returns MutableStateFlow(
            SelectedLens(CameraSelector.DEFAULT_BACK_CAMERA, isFront = false, index = 0),
        )
        // The torch convergence reads the live fact on every camera merge;
        // the default world here is a dark LED.
        every { cameraService.isTorchOn() } returns false
        every { streamingManager.isServerRunning } returns MutableStateFlow(false)
        every { recordingController.state } returns MutableStateFlow(RecordingState.Idle)

        coEvery { applier.flushCameraApply() } returns Unit
        coEvery { applier.applyCameraSettingsNow(any()) } returns Unit
    }

    private fun controller() = AutomationTestController(app)

    // ── restore: join the pending apply, then drive the live camera ──

    @Test
    fun `restore joins the pending applier apply before driving the restored camera live`() = runBlocking {
        val controller = controller()
        assertTrue(controller.arm("r1"))
        // The arm's persisted write lands in the shared flow (what the real
        // store's async share-in delivers).
        settings.value = CameraSettings(iso = 1600, torchEnabled = true)

        controller.restore()

        // Join FIRST, direct apply SECOND — the ordering that keeps an
        // in-flight arm-side apply from landing after the restore's.
        coVerifyOrder {
            applier.flushCameraApply()
            applier.applyCameraSettingsNow(any())
        }
        val applied = slot<CameraSettings>()
        coVerify { applier.applyCameraSettingsNow(capture(applied)) }
        // Snapshot (disk) held auto ISO and torch dark: the live payload
        // must fold both back to auto/off, not carry the fired manual state.
        assertNull(applied.captured.iso)
        assertFalse(applied.captured.torchEnabled)
        // And the persisted rewind ran through the same executor save.
        coVerify { settingsDataStore.saveSettings(match { it.iso == 1600 && it.torchEnabled }) }
        coVerify { settingsDataStore.saveSettings(match { it.iso == null && !it.torchEnabled }) }
    }

    // ── snapshot freshness: disk, not the lagging shared flow ──

    @Test
    fun `arm snapshots from disk so a stale shared flow cannot become the pre-test state`() = runBlocking {
        val controller = controller()
        // The shared flow still carries the previous cycle's FIRED values
        // while disk holds the restored auto world (the real share-in hop).
        settings.value = CameraSettings(iso = 1600, torchEnabled = true)

        assertTrue(controller.arm("r1"))
        controller.restore()

        val applied = slot<CameraSettings>()
        coVerify { applier.applyCameraSettingsNow(capture(applied)) }
        // A `.value` snapshot would have restored iso=1600/torch on — the
        // store-left-unreset cycle. The disk snapshot restores auto/off.
        assertNull(applied.captured.iso)
        assertFalse(applied.captured.torchEnabled)
    }

    // ── idempotence ──

    @Test
    fun `restore with nothing armed is a no-op and a second restore does not re-apply`() = runBlocking {
        val controller = controller()

        controller.restore()
        coVerify(exactly = 0) { applier.flushCameraApply() }
        coVerify(exactly = 0) { applier.applyCameraSettingsNow(any()) }

        assertTrue(controller.arm("r1"))
        settings.value = CameraSettings(iso = 1600, torchEnabled = true)
        controller.restore()
        controller.restore()

        coVerify(exactly = 1) { applier.flushCameraApply() }
        coVerify(exactly = 1) { applier.applyCameraSettingsNow(any()) }
        assertNull(controller.armedRuleId.value)
    }

    @Test
    fun `arm of an unknown rule id touches nothing`() = runBlocking {
        val controller = controller()
        assertFalse(controller.arm("nope"))
        coVerify(exactly = 0) { settingsDataStore.saveSettings(any()) }
        coVerify(exactly = 0) { applier.applyCameraSettingsNow(any()) }
        assertNull(controller.armedRuleId.value)
    }

    // ── torch convergence: a camera-only rule's fire and restore must not ──
    // ── re-assert stale stored intent over a live-only lit LED (issue #16) ──

    @Test
    fun `a camera-only rule fire and restore keep a live lit torch`() = runBlocking {
        // The deterrence coordinator lit the LED live-only; stored intent says off.
        every { cameraService.isTorchOn() } returns true
        val cameraOnlyRule = AutomationRule(
            id = "r2",
            name = "night iso",
            actions = listOf(AutomationActionSpec("camera_exposure", mapOf("iso" to "1600"))),
        )
        coEvery { settingsDataStore.automationRulesFromDisk() } returns listOf(rule, cameraOnlyRule)

        val controller = controller()
        assertTrue(controller.arm("r2"))

        // The fire's persisted write carries the lit LED, not the stale
        // stored off — the applier's re-assert lands as a no-op on the flash.
        coVerify { settingsDataStore.saveSettings(match { it.iso == 1600 && it.torchEnabled }) }

        controller.restore()

        // The restore touches only the axes the rule moved (ISO) — the torch
        // stays converged to the live fact on the way back too.
        coVerify { settingsDataStore.saveSettings(match { it.iso == null && it.torchEnabled }) }
        val applied = slot<CameraSettings>()
        coVerify { applier.applyCameraSettingsNow(capture(applied)) }
        assertTrue(applied.captured.torchEnabled)
    }

    // ── torch snapshot divergence: the arm-time snapshot must answer the ──
    // ── LIVE LED fact, not the stored intent live-only writers leave stale ──

    @Test
    fun `a torch rule restore answers from the live dark fact, not stale stored lit intent`() = runBlocking {
        // The camera-section save converged the store to a lit LED, then a
        // live-only writer (web torch route / deterrence) darkened it: stored
        // intent says on, the LED is off. The 0.2.2 on-device repro — a
        // snapshot answering from the store re-lit the LED on restore.
        diskWorld = CameraSettings(torchEnabled = true)
        every { cameraService.isTorchOn() } returns false

        val controller = controller()
        assertTrue(controller.arm("r1"))
        controller.restore()

        // The rewind must land the pre-test DARK live fact on both surfaces —
        // the persisted store and the live session — not the stale stored lit.
        coVerify { settingsDataStore.saveSettings(match { !it.torchEnabled }) }
        val applied = slot<CameraSettings>()
        coVerify { applier.applyCameraSettingsNow(capture(applied)) }
        assertFalse(applied.captured.torchEnabled)
    }

    @Test
    fun `a torch rule restore keeps a live-only lit LED lit across the rewind`() = runBlocking {
        // The inverse divergence: stored intent says off, but the deterrence
        // coordinator lit the LED live-only. A store-answering snapshot would
        // darken it on the way back.
        every { cameraService.isTorchOn() } returns true

        val controller = controller()
        assertTrue(controller.arm("r1"))
        controller.restore()

        // The pre-test LIT live fact survives the restore on both surfaces.
        coVerify { settingsDataStore.saveSettings(match { it.torchEnabled }) }
        val applied = slot<CameraSettings>()
        coVerify { applier.applyCameraSettingsNow(capture(applied)) }
        assertTrue(applied.captured.torchEnabled)
    }

    // ── night_vision: the mode is its own snapshot axis, rewind touches it only ──

    @Test
    fun `a night_vision rule restores the pre-test mode alongside its camera actions`() = runBlocking {
        // The reporter's night recipe as a rule: exposure up AND night mode
        // on. Pre-test world on disk: night mode off, auto ISO, torch dark.
        diskWorld = CameraSettings(nightVisionMode = NightVisionMode.OFF)
        val nightRule = AutomationRule(
            id = "r3",
            name = "night recipe",
            actions = listOf(
                AutomationActionSpec("camera_exposure", mapOf("iso" to "3200")),
                AutomationActionSpec("night_vision", mapOf("mode" to "on")),
            ),
        )
        coEvery { settingsDataStore.automationRulesFromDisk() } returns listOf(rule, nightRule)

        val controller = controller()
        assertTrue(controller.arm("r3"))

        // The fire persisted BOTH configured values onto one accumulated
        // document (the executor's disk-truth merge, composed).
        coVerify { settingsDataStore.saveSettings(match { it.iso == 3200 && it.nightVisionMode == NightVisionMode.ON }) }

        controller.restore()

        // The rewind lands the snapshot's pre-test mode (OFF) on the live
        // payload — the touched mode axis, with the auto ISO it snapshot too.
        val applied = slot<CameraSettings>()
        coVerify { applier.applyCameraSettingsNow(capture(applied)) }
        assertEquals(NightVisionMode.OFF, applied.captured.nightVisionMode)
        assertNull(applied.captured.iso)
        coVerify { settingsDataStore.saveSettings(match { it.nightVisionMode == NightVisionMode.OFF && it.iso == null }) }
        assertNull(controller.armedRuleId.value)
    }

    // ── the restored LIVE payload answers from disk, not the lagging flow ──

    @Test
    fun `restore drives the disk truth onto the live session, not the stale shared flow`() = runBlocking {
        // The restore's own saves have persisted (the disk now holds the
        // rewound world: auto ISO, the frame rate a daytime rule left), but
        // the shared flow still answers the FIRED world — the same share-in
        // lag the executor regression rides. The store stub below keeps the
        // share-in undelivered for this test, the window the bug rides. A
        // `.value` base would drive the fired values back onto the live
        // camera the restore just rewound (issue #18: configured values
        // briefly apply, then revert — the inverse half: reverted values
        // briefly apply, then stick).
        diskWorld = CameraSettings(frameRate = 15)
        settings.value = CameraSettings(iso = 1600, frameRate = 24)
        coEvery { settingsDataStore.saveSettings(any()) } answers { diskWorld = firstArg() }

        val controller = controller()
        assertTrue(controller.arm("r1"))
        controller.restore()

        val applied = slot<CameraSettings>()
        coVerify { applier.applyCameraSettingsNow(capture(applied)) }
        // Touched axis from the snapshot (auto ISO), untouched axis from the
        // DISK truth — the persisted value the applier will re-assert.
        assertNull(applied.captured.iso)
        assertEquals(15, applied.captured.frameRate)
    }
}
