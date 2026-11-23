package com.raulshma.lenscast.camera.model

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the read-back comparison and the exact log lines: the on-device
 * verification of issue #25 greps logcat for `Camera controls readback:`, so
 * the prefix, the shape of the ok line and the two-sided mismatch line are
 * part of the contract, not cosmetics.
 */
class CaptureReadbackPolicyTest {

    private val manualNight = CaptureReadbackPolicy.Expected(
        aeMode = CaptureRequest.CONTROL_AE_MODE_OFF,
        sensorSensitivity = 3200,
        sensorExposureTimeNs = 100_000_000L,
        sceneMode = CaptureRequest.CONTROL_SCENE_MODE_NIGHT,
    )

    private fun reported(
        aeMode: Int? = CaptureRequest.CONTROL_AE_MODE_OFF,
        sensorSensitivity: Int? = 3200,
        sensorExposureTimeNs: Long? = 100_000_000L,
        sceneMode: Int? = CaptureRequest.CONTROL_SCENE_MODE_NIGHT,
    ) = CaptureReadbackPolicy.Reported(aeMode, sensorSensitivity, sensorExposureTimeNs, sceneMode)

    @Test
    fun `a result reporting the plan's values is a match`() {
        assertEquals(CaptureReadbackPolicy.Verdict.Match, CaptureReadbackPolicy.compare(manualNight, reported()))
    }

    @Test
    fun `a different AE mode is a mismatch`() {
        assertEquals(
            CaptureReadbackPolicy.Verdict.Mismatch,
            CaptureReadbackPolicy.compare(manualNight, reported(aeMode = CaptureRequest.CONTROL_AE_MODE_ON)),
        )
    }

    @Test
    fun `a different scene mode is a mismatch`() {
        assertEquals(
            CaptureReadbackPolicy.Verdict.Mismatch,
            CaptureReadbackPolicy.compare(manualNight, reported(sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED)),
        )
    }

    @Test
    fun `a different sensor value is a mismatch under a manual plan`() {
        assertEquals(
            CaptureReadbackPolicy.Verdict.Mismatch,
            CaptureReadbackPolicy.compare(manualNight, reported(sensorSensitivity = 1600)),
        )
        assertEquals(
            CaptureReadbackPolicy.Verdict.Mismatch,
            CaptureReadbackPolicy.compare(manualNight, reported(sensorExposureTimeNs = 41_666_666L)),
        )
    }

    @Test
    fun `a missing value is unknown`() {
        // Null means the device did not report the key: neither a match nor
        // a mismatch may be claimed from it.
        assertEquals(CaptureReadbackPolicy.Verdict.Unknown, CaptureReadbackPolicy.compare(manualNight, reported(aeMode = null)))
        assertEquals(CaptureReadbackPolicy.Verdict.Unknown, CaptureReadbackPolicy.compare(manualNight, reported(sceneMode = null)))
        assertEquals(CaptureReadbackPolicy.Verdict.Unknown, CaptureReadbackPolicy.compare(manualNight, reported(sensorSensitivity = null)))
        assertEquals(
            CaptureReadbackPolicy.Verdict.Unknown,
            CaptureReadbackPolicy.compare(manualNight, reported(sensorExposureTimeNs = null)),
        )
    }

    @Test
    fun `AE on skips the sensor compare`() {
        // Under automatic exposure the device varies ISO and shutter by
        // design, so only the AE mode and the scene mode are compared — a
        // sensor pair the plan never asked for must not fail the read-back,
        // whether the device reports one or nothing at all.
        val automatic = CaptureReadbackPolicy.Expected(
            aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
            sensorSensitivity = null,
            sensorExposureTimeNs = null,
            sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
        )

        assertEquals(
            CaptureReadbackPolicy.Verdict.Match,
            CaptureReadbackPolicy.compare(
                automatic,
                reported(
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
                    sensorSensitivity = 100,
                    sensorExposureTimeNs = 41_666_666L,
                    sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
                ),
            ),
        )
        assertEquals(
            CaptureReadbackPolicy.Verdict.Match,
            CaptureReadbackPolicy.compare(
                automatic,
                reported(aeMode = CaptureRequest.CONTROL_AE_MODE_ON, sensorSensitivity = null, sensorExposureTimeNs = null, sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED),
            ),
        )
        assertEquals(
            CaptureReadbackPolicy.Verdict.Mismatch,
            CaptureReadbackPolicy.compare(automatic, reported(aeMode = CaptureRequest.CONTROL_AE_MODE_ON, sceneMode = CaptureRequest.CONTROL_SCENE_MODE_NIGHT)),
        )
    }

    @Test
    fun `the flash AE modes are compared by mode only`() {
        // Night-vision AUTO asks for ON_AUTO_FLASH: still automatic exposure.
        val autoFlash = manualNight.copy(aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH, sensorSensitivity = null, sensorExposureTimeNs = null)

        assertEquals(
            CaptureReadbackPolicy.Verdict.Match,
            CaptureReadbackPolicy.compare(autoFlash, reported(aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH, sensorSensitivity = null, sensorExposureTimeNs = null)),
        )
    }

    @Test
    fun `the ok line is exactly the documented oracle`() {
        assertEquals(
            CaptureReadbackPolicy.Line(
                CaptureReadbackPolicy.Level.Info,
                "Camera controls readback: ok (AE=OFF ISO=3200 exp=100000000ns scene=NIGHT)",
            ),
            CaptureReadbackPolicy.ok(manualNight),
        )
    }

    @Test
    fun `the ok line omits the sensor pair under AE on`() {
        assertEquals(
            "Camera controls readback: ok (AE=ON scene=DISABLED)",
            CaptureReadbackPolicy.ok(
                CaptureReadbackPolicy.Expected(
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
                    sensorSensitivity = null,
                    sensorExposureTimeNs = null,
                    sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
                ),
            ).message,
        )
    }

    @Test
    fun `the mismatch line shows what was requested and what the device runs`() {
        assertEquals(
            CaptureReadbackPolicy.Line(
                CaptureReadbackPolicy.Level.Warning,
                "Camera controls readback: MISMATCH requested AE=OFF ISO=3200 exp=100000000ns scene=NIGHT " +
                    "| device AE=ON ISO=100 exp=41666666ns scene=DISABLED",
            ),
            CaptureReadbackPolicy.mismatch(
                manualNight,
                reported(
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
                    sensorSensitivity = 100,
                    sensorExposureTimeNs = 41_666_666L,
                    sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
                ),
            ),
        )
    }

    @Test
    fun `the unknown line shows a missing value as null`() {
        assertEquals(
            "Camera controls readback: UNKNOWN requested AE=OFF ISO=3200 exp=100000000ns scene=NIGHT " +
                "| device AE=OFF ISO=null exp=nullns scene=null",
            CaptureReadbackPolicy.unknown(manualNight, reported(sensorSensitivity = null, sensorExposureTimeNs = null, sceneMode = null)).message,
        )
    }

    @Test
    fun `the unknown line without any capture result says so`() {
        val line = CaptureReadbackPolicy.unknown(manualNight, null)

        assertEquals(CaptureReadbackPolicy.Level.Warning, line.level)
        assertEquals(
            "Camera controls readback: UNKNOWN requested AE=OFF ISO=3200 exp=100000000ns scene=NIGHT " +
                "| device reported no capture result within 10000ms",
            line.message,
        )
    }

    @Test
    fun `every conclusion carries the exact logcat prefix`() {
        val lines = listOf(
            CaptureReadbackPolicy.ok(manualNight),
            CaptureReadbackPolicy.mismatch(manualNight, reported(aeMode = CaptureRequest.CONTROL_AE_MODE_ON)),
            CaptureReadbackPolicy.unknown(manualNight, reported(aeMode = null)),
            CaptureReadbackPolicy.unknown(manualNight, null),
        )

        lines.forEach { line ->
            assertEquals(CaptureReadbackPolicy.LOG_PREFIX, line.message.take(CaptureReadbackPolicy.LOG_PREFIX.length))
        }
        // An ok is good news at info; anything that leaves the verification
        // without a confirmation is a warning.
        assertEquals(CaptureReadbackPolicy.Level.Info, lines[0].level)
        assertEquals(
            List(3) { CaptureReadbackPolicy.Level.Warning },
            lines.drop(1).map { it.level },
        )
    }

    @Test
    fun `modes are named after their camera2 constants`() {
        fun okLine(ae: Int, scene: Int) =
            CaptureReadbackPolicy.ok(CaptureReadbackPolicy.Expected(ae, null, null, scene)).message

        assertEquals(
            "Camera controls readback: ok (AE=ON_AUTO_FLASH scene=HDR)",
            okLine(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH, CaptureRequest.CONTROL_SCENE_MODE_HDR),
        )
        assertEquals(
            "Camera controls readback: ok (AE=ON scene=NIGHT_PORTRAIT)",
            okLine(CaptureRequest.CONTROL_AE_MODE_ON, CaptureRequest.CONTROL_SCENE_MODE_NIGHT_PORTRAIT),
        )
    }

    @Test
    fun `a mode without a name is shown as its number`() {
        // settings.sceneMode accepts any camera2 scene mode by number, and
        // vendor-private values exist past the named ones.
        assertEquals(
            "Camera controls readback: ok (AE=ON scene=1000)",
            CaptureReadbackPolicy.ok(
                CaptureReadbackPolicy.Expected(CaptureRequest.CONTROL_AE_MODE_ON, null, null, 1000),
            ).message,
        )
    }

    @Test
    fun `the expected values are the plan's after its capability fold`() {
        // The read-back verifies what was SUBMITTED: a night request the plan
        // retired to DISABLED must be checked against DISABLED, or every
        // lens without the NIGHT scene mode would read as a mismatch.
        val plan = CameraControlPlan.from(
            CameraSettings(nightVisionMode = NightVisionMode.ON, iso = 3200, exposureTime = 100_000_000L),
            100..3200,
            1L..1_000_000_000L,
            CaptureSupport(sceneModes = setOf(CaptureRequest.CONTROL_SCENE_MODE_DISABLED)),
        )

        assertEquals(
            CaptureReadbackPolicy.Expected(
                aeMode = CaptureRequest.CONTROL_AE_MODE_OFF,
                sensorSensitivity = 3200,
                sensorExposureTimeNs = 100_000_000L,
                sceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED,
            ),
            CaptureReadbackPolicy.expectedFrom(plan),
        )
    }
}
