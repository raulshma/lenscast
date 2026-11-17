package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.camera.model.CameraErrorPolicy
import com.raulshma.lenscast.camera.model.CameraResetResult
import com.raulshma.lenscast.core.SoftRestartLadder
import com.raulshma.lenscast.streaming.model.CrashRelaunchTelemetryDto
import com.raulshma.lenscast.streaming.model.HeartbeatTelemetryDto
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wave 4's GET /api/system extensions and the new write:
 *
 *  - the deferred item-7 telemetry (blueprint P0 item 7's crash/heartbeat
 *    half): `crashRelaunch` and `heartbeat` ride the snapshot when wired and
 *    stay OFF the wire when the seams are unwired (the torchStrengthMaxLevel
 *    precedent) — nulls are omitted, so the legacy consumers see no change;
 *  - POST /api/system/reset-camera (P1 item 10): the bounded camera rebind
 *    lever, fully typed — REBIND on a landed rebind, and every
 *    policy-driven non-rebind answer (REBOOT_REQUIRED, WAIT_CAMERA_ADDED,
 *    CAMERAX_HANDLES, a denied bind) surfaced with its machine-readable
 *    reason instead of a bare 200.
 */
class SystemWebHandlerSystemTelemetryTest {

    /** The PowerManager facts get() reads, stubbed concretely (the battery flow must carry real Ints). */
    private val powerManager: com.raulshma.lenscast.core.PowerManager =
        mockk<com.raulshma.lenscast.core.PowerManager>().also { pm ->
            every { pm.batteryLevel } returns MutableStateFlow(50)
            every { pm.isChargingNow() } returns false
            every { pm.isIgnoringBatteryOptimizations() } returns true
            every { pm.isDeviceInDozeMode() } returns false
        }

    private fun handler(
        crashRelaunch: () -> CrashRelaunchTelemetryDto? = { null },
        heartbeat: () -> HeartbeatTelemetryDto? = { null },
        resetLever: suspend () -> CameraResetResult = {
            CameraResetResult(CameraErrorPolicy.Verdict.REBIND, bound = true)
        },
    ) = SystemWebHandler(
        // Relaxed: get() reads the PowerManager's battery facts and the
        // context's hardware reads (all runCatching-degrading); only the
        // telemetry shape is under test here. The battery flow is stubbed
        // concretely — a relaxed StateFlow's value would not be an Int.
        context = mockk(relaxed = true),
        powerManager = powerManager,
        captureHistoryStore = mockk(relaxed = true),
        crashRelaunchTelemetry = crashRelaunch,
        heartbeatTelemetry = heartbeat,
        resetCameraLever = resetLever,
    )

    // ── the deferred item-7 telemetry ──

    @Test
    fun `unwired telemetry seams keep the fields off the wire`() {
        // The torchStrengthMaxLevel precedent: an absent fact is omitted, so
        // legacy consumers and the contract fixture see no new keys.
        val body = handler().get()
        assertEquals(false, body.contains("crashRelaunch"))
        assertEquals(false, body.contains("heartbeat"))
    }

    @Test
    fun `wired telemetry seams carry the crash guard and heartbeat state`() {
        val body = handler(
            crashRelaunch = { CrashRelaunchTelemetryDto(crashCount = 2, state = "BACKOFF") },
            heartbeat = { HeartbeatTelemetryDto(armed = true, misses = 1, tier = "SOFT") },
        ).get()
        assertEquals(true, body.contains(""""crashRelaunch":{"crashCount":2,"state":"BACKOFF"}"""))
        assertEquals(true, body.contains(""""heartbeat":{"armed":true,"misses":1,"tier":"SOFT"}"""))
    }

    @Test
    fun `a heartbeat without a verdict omits the tier`() {
        val body = handler(heartbeat = { HeartbeatTelemetryDto(armed = true, misses = 0) }).get()
        assertEquals(true, body.contains(""""heartbeat":{"armed":true,"misses":0}"""))
    }

    @Test
    fun `the crash state wire name follows the policy ladder`() {
        assertEquals("ARM", SystemWebHandler.crashRelaunchStateName(0))
        assertEquals("ARM", SystemWebHandler.crashRelaunchStateName(1))
        assertEquals("BACKOFF", SystemWebHandler.crashRelaunchStateName(2))
        assertEquals("BACKOFF", SystemWebHandler.crashRelaunchStateName(3))
        assertEquals("GIVE_UP", SystemWebHandler.crashRelaunchStateName(4))
    }

    // ── POST /api/system/reset-camera ──

    @Test
    fun `a landed rebind answers success with the REBIND verdict`() = runBlocking {
        val body = handler().resetCamera()
        assertEquals("""{"success":true,"verdict":"REBIND"}""", body)
    }

    @Test
    fun `a denied bind answers typed failure without pretending success`() = runBlocking {
        val body = handler(
            resetLever = { CameraResetResult(CameraErrorPolicy.Verdict.REBIND, bound = false) },
        ).resetCamera()
        assertEquals(true, body.contains(""""success":false"""))
        assertEquals(true, body.contains(""""verdict":"REBIND""""))
        assertEquals(true, body.contains(""""reason":"rebind_denied""""))
    }

    @Test
    fun `REBOOT_REQUIRED surfaces verbatim when the taxonomy says so`() = runBlocking {
        val body = handler(
            resetLever = { CameraResetResult(CameraErrorPolicy.Verdict.REBOOT_REQUIRED) },
        ).resetCamera()
        assertEquals(true, body.contains(""""success":false"""))
        assertEquals(true, body.contains(""""verdict":"REBOOT_REQUIRED""""))
        assertEquals(true, body.contains(""""reason":"reboot_required""""))
    }

    @Test
    fun `WAIT_CAMERA_ADDED surfaces the removed-camera verdict`() = runBlocking {
        val body = handler(
            resetLever = { CameraResetResult(CameraErrorPolicy.Verdict.WAIT_CAMERA_ADDED) },
        ).resetCamera()
        assertEquals(true, body.contains(""""verdict":"WAIT_CAMERA_ADDED""""))
        assertEquals(true, body.contains(""""reason":"camera_removed""""))
    }

    @Test
    fun `a spent budget answers CAMERAX_HANDLES instead of looping`() = runBlocking {
        val body = handler(
            resetLever = { CameraResetResult(CameraErrorPolicy.Verdict.CAMERAX_HANDLES) },
        ).resetCamera()
        assertEquals(true, body.contains(""""success":false"""))
        assertEquals(true, body.contains(""""verdict":"CAMERAX_HANDLES""""))
        assertEquals(true, body.contains(""""reason":"rebind_budget_spent""""))
    }

    // ── the ladder step names the wire carries (shared with ?mode=soft) ──

    @Test
    fun `the soft ladder step names stay stable for the wire`() {
        assertEquals("rebindCamera", SoftRestartLadder.Step.REBIND_CAMERA)
        assertEquals("startServer", SoftRestartLadder.Step.START_SERVER)
    }
}
