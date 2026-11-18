package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.NightVisionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Automation Test's restore verdicts (issue #18 item 3): the rule's
 * actions + the pre-test snapshot → the action list that winds the touched
 * axes back. The controller runs the returned specs through the same
 * executor a scheduled fire uses, so these verdicts ARE the restore.
 */
class AutomationRestorePolicyTest {

    private val snapshot = AutomationStateSnapshot(
        iso = 3200,
        exposureTimeMs = 100.0,
        exposureCompensation = -2,
        zoomRatio = 1.5f,
        frameRate = 15,
        lensIndex = 2,
        torchEnabled = true,
        torchStrength = 3,
        nightVisionMode = NightVisionMode.AUTO,
        motionEnabled = false,
        soundEnabled = true,
        streamRunning = false,
        recordingIdle = true,
    )

    private fun restore(vararg actions: AutomationActionSpec) =
        AutomationRestorePolicy.restoreActions(actions.toList(), snapshot)

    @Test
    fun `camera exposure restores only the params the rule touched`() {
        val restored = restore(AutomationActionSpec("camera_exposure", mapOf("iso" to "400")))
        assertEquals(listOf(AutomationActionSpec("camera_exposure", mapOf("iso" to "3200"))), restored)

        // A rule that touched both axes restores both — auto snapshot values
        // restore as the catalog's autoAtZero encodings (0).
        val both = restore(
            AutomationActionSpec(
                "camera_exposure",
                mapOf("iso" to "400", "exposure_time_ms" to "10"),
            ),
        )
        assertEquals(
            listOf(
                AutomationActionSpec("camera_exposure", mapOf("iso" to "3200", "exposure_time_ms" to "100.0")),
            ),
            both,
        )

        val autoSnapshot = snapshot.copy(iso = null, exposureTimeMs = null)
        val autoRestored = AutomationRestorePolicy.restoreActions(
            listOf(AutomationActionSpec("camera_exposure", mapOf("iso" to "800", "exposure_time_ms" to "10"))),
            autoSnapshot,
        )
        assertEquals(
            listOf(AutomationActionSpec("camera_exposure", mapOf("iso" to "0", "exposure_time_ms" to "0"))),
            autoRestored,
        )
    }

    @Test
    fun `scalar camera axes restore from the snapshot`() {
        assertEquals(
            listOf(
                AutomationActionSpec("camera_ev", mapOf("index" to "-2")),
                AutomationActionSpec("camera_zoom", mapOf("ratio" to "1.5")),
                AutomationActionSpec("camera_fps", mapOf("fps" to "15")),
                AutomationActionSpec("camera_lens", mapOf("index" to "2")),
            ),
            restore(
                AutomationActionSpec("camera_ev", mapOf("index" to "0")),
                AutomationActionSpec("camera_zoom", mapOf("ratio" to "4")),
                AutomationActionSpec("camera_fps", mapOf("fps" to "30")),
                AutomationActionSpec("camera_lens", mapOf("index" to "0")),
            ),
        )
    }

    @Test
    fun `torch restores the persisted toggle and only a level the rule drove`() {
        assertEquals(
            listOf(AutomationActionSpec("torch", mapOf("enabled" to "true", "strength" to "3"))),
            restore(AutomationActionSpec("torch", mapOf("enabled" to "false", "strength" to "6"))),
        )
        // A snapshot without a stored level (device default) restores the
        // toggle only — the strength param's absence IS the device default.
        val noLevel = snapshot.copy(torchStrength = null)
        assertEquals(
            listOf(AutomationActionSpec("torch", mapOf("enabled" to "true"))),
            AutomationRestorePolicy.restoreActions(
                listOf(AutomationActionSpec("torch", mapOf("enabled" to "false", "strength" to "6"))),
                noLevel,
            ),
        )
    }

    @Test
    fun `night vision restores the snapshot mode as the catalog's canonical id`() {
        // The snapshot held AUTO: the rewind speaks the merge's own lowercase
        // vocabulary, not the enum name.
        assertEquals(
            listOf(AutomationActionSpec("night_vision", mapOf("mode" to "auto"))),
            restore(AutomationActionSpec("night_vision", mapOf("mode" to "on"))),
        )
        // Every enum value round-trips through its canonical id.
        for (mode in NightVisionMode.entries) {
            assertEquals(
                listOf(AutomationActionSpec("night_vision", mapOf("mode" to mode.name.lowercase()))),
                AutomationRestorePolicy.restoreActions(
                    listOf(AutomationActionSpec("night_vision", mapOf("mode" to "on"))),
                    snapshot.copy(nightVisionMode = mode),
                ),
            )
        }
    }

    @Test
    fun `stream and detection restore the snapshot's toggles per touched param`() {
        assertEquals(
            listOf(AutomationActionSpec("stream", mapOf("enabled" to "false"))),
            restore(AutomationActionSpec("stream", mapOf("enabled" to "true"))),
        )
        assertEquals(
            listOf(AutomationActionSpec("detection", mapOf("motion_enabled" to "false", "sound_enabled" to "true"))),
            restore(AutomationActionSpec("detection", mapOf("motion_enabled" to "true", "sound_enabled" to "false"))),
        )
        // A rule that touched only one detection axis restores only it.
        assertEquals(
            listOf(AutomationActionSpec("detection", mapOf("sound_enabled" to "true"))),
            restore(AutomationActionSpec("detection", mapOf("sound_enabled" to "false"))),
        )
    }

    @Test
    fun `siren restores as a stop and a stopping rule restores nothing`() {
        assertEquals(
            listOf(AutomationActionSpec("siren", mapOf("enabled" to "false"))),
            restore(AutomationActionSpec("siren", mapOf("enabled" to "true", "duration_seconds" to "10"))),
        )
        assertTrue(restore(AutomationActionSpec("siren", mapOf("enabled" to "false"))).isEmpty())
    }

    @Test
    fun `recording stops back only when the controller was idle at arm time`() {
        assertEquals(
            listOf(AutomationActionSpec("recording", mapOf("enabled" to "false"))),
            restore(AutomationActionSpec("recording", mapOf("enabled" to "true", "duration_seconds" to "60"))),
        )
        // A recording already live at arm time is the user's — the restore leaves it.
        val busySnapshot = snapshot.copy(recordingIdle = false)
        assertTrue(
            AutomationRestorePolicy.restoreActions(
                listOf(AutomationActionSpec("recording", mapOf("enabled" to "true"))),
                busySnapshot,
            ).isEmpty(),
        )
        assertTrue(restore(AutomationActionSpec("recording", mapOf("enabled" to "false"))).isEmpty())
    }

    @Test
    fun `capture_photo and unknown ids restore nothing`() {
        assertTrue(restore(AutomationActionSpec("capture_photo", emptyMap())).isEmpty())
        assertTrue(restore(AutomationActionSpec("not_a_real_action", mapOf("x" to "1"))).isEmpty())
    }

    // ── restoredCameraSettings: the live-apply payload of the restore ──

    @Test
    fun `restored camera settings fold the restore actions onto the persisted base`() {
        val base = CameraSettings(iso = 800, exposureTime = 50_000_000L, torchEnabled = true, zoomRatio = 2.0f)
        val restored = AutomationRestorePolicy.restoredCameraSettings(
            listOf(
                AutomationActionSpec("camera_exposure", mapOf("iso" to "3200", "exposure_time_ms" to "100.0")),
                AutomationActionSpec("torch", mapOf("enabled" to "false")),
            ),
            base,
        )
        // Touched axes carry the snapshot's values (exposure_time_ms in
        // nanoseconds, like the persisted field); untouched axes stay.
        assertEquals(3200, restored.iso)
        assertEquals(100_000_000L, restored.exposureTime)
        assertFalse(restored.torchEnabled)
        assertEquals(2.0f, restored.zoomRatio)
    }

    @Test
    fun `restored camera settings fold the auto sentinel to null`() {
        // The device bug's core: a snapshot with auto ISO/exposure and torch
        // off must land live as driver-auto and torch dark, not as a stale
        // manual carry-over from the fired state.
        val fired = CameraSettings(iso = 1600, exposureTime = 10_000_000L, torchEnabled = true, frameRate = 30)
        val restored = AutomationRestorePolicy.restoredCameraSettings(
            listOf(
                AutomationActionSpec("camera_exposure", mapOf("iso" to "0", "exposure_time_ms" to "0")),
                AutomationActionSpec("torch", mapOf("enabled" to "false")),
            ),
            fired,
        )
        assertNull(restored.iso)
        assertNull(restored.exposureTime)
        assertFalse(restored.torchEnabled)
        assertEquals(30, restored.frameRate)
    }

    @Test
    fun `restored camera settings ignore non-camera actions`() {
        val base = CameraSettings(iso = 100)
        val restored = AutomationRestorePolicy.restoredCameraSettings(
            listOf(
                AutomationActionSpec("siren", mapOf("enabled" to "false")),
                AutomationActionSpec("stream", mapOf("enabled" to "false")),
                AutomationActionSpec("recording", mapOf("enabled" to "false")),
            ),
            base,
        )
        assertEquals(base, restored)
    }
}
