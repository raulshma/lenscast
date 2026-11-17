package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.SoftRestartLadder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * POST /api/system/restart must fire the injected restart seam exactly once
 * and still answer OK. The seam used to be named `restart` like the member,
 * so the member's own `restart()` call resolved back into itself and
 * StackOverflowed the request thread before the OK flushed or the
 * AlarmManager relaunch armed — device-verified crash, not just a style risk.
 *
 * Blueprint P0-B item 8 (restart-reliability §5 option D) adds the typed
 * pre-check on top: with the exact-alarm grant definitively missing, the
 * handler must refuse (`success:false` + machine-readable reason) BEFORE the
 * seam runs — no quit, no alarm — where the old path threw out of
 * AppRelaunch.schedule into the router catch-all after the quit was queued.
 * The success shape stays exactly `{"success":true}` — the contract is
 * extended additively, never rewritten.
 *
 * Wave 4 (blueprint P1 item 9) adds `?mode=soft`: the in-process soft-restart
 * ladder answers AFTER the re-arm with `mode:"soft"` on success and the
 * failed ladder step in the typed-refusal shape on failure. Plain and
 * `?mode=process` stay byte-identical with the deep restart's legacy wire
 * contract.
 */
class SystemWebHandlerRestartTest {

    @Test
    fun `restart invokes the seam exactly once and answers OK`() {
        var seamCalls = 0
        val handler = SystemWebHandler(
            context = mockk(),
            powerManager = mockk(),
            captureHistoryStore = mockk(),
            restartApplication = { seamCalls++ },
            canScheduleExactAlarms = { true },
        )

        val body = runBlocking { handler.restart() }

        assertEquals(1, seamCalls)
        assertEquals("""{"success":true}""", body)
    }

    @Test
    fun `restart answers OK even with the default no-op seam`() {
        val handler = SystemWebHandler(
            context = mockk(),
            powerManager = mockk(),
            captureHistoryStore = mockk(),
            canScheduleExactAlarms = { true },
        )

        assertEquals("""{"success":true}""", runBlocking { handler.restart() })
    }

    @Test
    fun `restart refuses without invoking the seam when the exact-alarm grant is missing`() {
        var seamCalls = 0
        val handler = SystemWebHandler(
            context = mockk(),
            powerManager = mockk(),
            captureHistoryStore = mockk(),
            restartApplication = { seamCalls++ },
            canScheduleExactAlarms = { false },
        )

        val body = runBlocking { handler.restart() }

        // The refusal is the whole point: no quit, no alarm — the phone the
        // dashboard cannot bring back stays up with its camera running.
        assertEquals(0, seamCalls)
        assertEquals(
            """{"success":false,"reason":"exact_alarm_not_granted",""" +
                """"hint":"Grant LensCast the Alarms & reminders permission in the device settings, """ +
                """then retry the restart."}""",
            body,
        )
    }

    @Test
    fun `restart proceeds when the grant answer is null (below API 31, no permission exists)`() {
        var seamCalls = 0
        val handler = SystemWebHandler(
            context = mockk(),
            powerManager = mockk(),
            captureHistoryStore = mockk(),
            restartApplication = { seamCalls++ },
            canScheduleExactAlarms = { null },
        )

        assertEquals("""{"success":true}""", runBlocking { handler.restart() })
        assertEquals(1, seamCalls)
    }

    // ── wave 4: ?mode=soft, the in-process soft-restart ladder ──

    @Test
    fun `soft mode answers mode soft after the ladder re-arms`() = runBlocking {
        var ladderRuns = 0
        var deepSeamCalls = 0
        val handler = SystemWebHandler(
            context = mockk(),
            powerManager = mockk(),
            captureHistoryStore = mockk(),
            restartApplication = { deepSeamCalls++ },
            canScheduleExactAlarms = { false }, // the soft path needs no grant
            softRestart = {
                ladderRuns++
                SoftRestartLadder.Result.Restarted
            },
        )

        val body = handler.restart(mode = SystemWebHandler.RESTART_MODE_SOFT)

        assertEquals(1, ladderRuns)
        // The soft path must not touch the deep seam, whatever the grant says.
        assertEquals(0, deepSeamCalls)
        assertEquals("""{"success":true,"mode":"soft"}""", body)
    }

    @Test
    fun `soft mode reports the failed step in the typed refusal shape`() = runBlocking {
        val handler = SystemWebHandler(
            context = mockk(),
            powerManager = mockk(),
            captureHistoryStore = mockk(),
            softRestart = {
                SoftRestartLadder.Result.StepFailed(
                    SoftRestartLadder.Step.START_SERVER,
                    "Failed to start server",
                )
            },
        )

        val body = handler.restart(mode = SystemWebHandler.RESTART_MODE_SOFT)

        // Not a silent 200: the failed ladder step is machine-readable, the
        // wave-3 refusal shape (success:false + reason + hint) carries it.
        assertEquals(
            """{"success":false,"mode":"soft","reason":"soft_restart_failed",""" +
                """"step":"startServer","hint":"Soft restart failed at Failed to start server"}""",
            body,
        )
    }

    @Test
    fun `process mode keeps the deep restart semantics byte-for-byte`() = runBlocking {
        var seamCalls = 0
        val handler = SystemWebHandler(
            context = mockk(),
            powerManager = mockk(),
            captureHistoryStore = mockk(),
            restartApplication = { seamCalls++ },
            canScheduleExactAlarms = { true },
        )

        // The legacy contract: no mode field on the deep restart's wire shape.
        assertEquals("""{"success":true}""", handler.restart(mode = SystemWebHandler.RESTART_MODE_PROCESS))
        assertEquals(1, seamCalls)
    }

    @Test
    fun `an unknown soft restart result shape still answers the refusal contract`() = runBlocking {
        // The production graph always wires the real ladder; this pins that
        // the sealed result's other branch is the only failure shape on the
        // wire (the honest not-wired default uses the same shape).
        val handler = SystemWebHandler(
            context = mockk(),
            powerManager = mockk(),
            captureHistoryStore = mockk(),
            softRestart = {
                SoftRestartLadder.Result.StepFailed(
                    SoftRestartLadder.Step.REBIND_CAMERA,
                    "camera rebind did not bind (denied or timed out)",
                )
            },
        )

        val body = handler.restart(mode = SystemWebHandler.RESTART_MODE_SOFT)
        assertEquals(true, body.contains(""""step":"rebindCamera""""))
        assertEquals(true, body.contains(""""reason":"soft_restart_failed""""))
    }

    @Test
    fun `the unwired soft-restart default answers the honest failure, never a success`() = runBlocking {
        // Fix F: the default seam is the honest absence (the { null }
        // telemetry precedent in the ladder's failure shape) — a mis-wired
        // graph must report a typed failure naming the not-wired step, not
        // a Restarted nothing performed.
        val handler = SystemWebHandler(
            context = mockk(),
            powerManager = mockk(),
            captureHistoryStore = mockk(),
        )

        val body = handler.restart(mode = SystemWebHandler.RESTART_MODE_SOFT)
        assertEquals(true, body.contains("\"success\":false"))
        assertEquals(true, body.contains(""""step":"not_wired""""))
        assertEquals(true, body.contains(""""reason":"soft_restart_failed""""))
    }

    @Test
    fun `the unwired reset-camera default answers the honest absence, never a rebind success`() = runBlocking {
        // Fix F: the default lever reports CAMERAX_HANDLES (the vocabulary's
        // own "no app-side lever") with bound = null (CameraResetResult's
        // documented "no rebind was attempted") — a typed failure, not a
        // bound=true nothing performed.
        val handler = SystemWebHandler(
            context = mockk(),
            powerManager = mockk(),
            captureHistoryStore = mockk(),
        )

        val body = handler.resetCamera()
        assertEquals(true, body.contains("\"success\":false"))
        assertEquals(true, body.contains(""""verdict":"CAMERAX_HANDLES""""))
        assertEquals(true, body.contains(""""reason":"rebind_budget_spent""""))
    }
}
