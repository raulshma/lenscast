package com.raulshma.lenscast.automation

import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.data.SettingsDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * The executor only persists — the Settings Applier stays the single
 * runtime apply path (no direct cameraService.applySettings).
 */
class AutomationActionExecutorTest {

    private val app: MainApplication = mockk()
    private val settingsDataStore: SettingsDataStore = mockk()
    private val cameraService: CameraService = mockk()

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

        every { settingsDataStore.settings } returns settings
        coEvery { settingsDataStore.settingsFromDisk() } answers { disk }
        // The real store feeds the shared flow ASYNC on the share-in hop; the
        // executor's next read can land inside that window. Modeling the hop
        // as "not landed yet" is the deterministic worst case the bug rides.
        coEvery { settingsDataStore.saveSettings(any()) } answers {
            disk = firstArg()
            savedDocuments.add(firstArg())
        }

        // The torch convergence reads the live fact on every camera merge.
        every { cameraService.isTorchOn() } returns false
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

    // ── ownership: the executor persists; the applier stays the only runtime path ──

    @Test
    fun `camera actions persist through the store and never drive the camera directly`() = runBlocking {
        disk = CameraSettings()
        settings.value = CameraSettings()

        executor().runActions(nightRuleActions)

        // One save per camera action, and NO second application path.
        coVerify(exactly = 2) { settingsDataStore.saveSettings(any()) }
        coVerify(exactly = 0) { cameraService.applySettings(any()) }
        // The last document exists and is the accumulated one.
        assertNotNull(savedDocuments.lastOrNull())
        assertEquals(15, savedDocuments.last().frameRate)
    }
}
