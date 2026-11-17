package com.raulshma.lenscast.core

import com.raulshma.lenscast.camera.model.StreamKind
import com.raulshma.lenscast.camera.model.StreamStartOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The in-process soft-restart ladder's orchestration decisions (blueprint P1
 * item 9): best-effort stops, decisive rebind/re-arm, and the failure report
 * ranking — the re-arm outranks the rebind (a dark server is the worse
 * truth), and a tolerated stop never fails the ladder. The ladder itself is a
 * thin shell over four injected seams; this pins the shell's only logic.
 */
class SoftRestartLadderTest {

    private fun ladder(
        stopRecording: suspend () -> Unit = {},
        stopServer: suspend () -> Unit = {},
        rebindCamera: suspend () -> Boolean = { true },
        startServer: suspend () -> StreamStartOutcome = { StreamStartOutcome.Started },
    ) = SoftRestartLadder(stopRecording, stopServer, rebindCamera, startServer)

    @Test
    fun `the happy path restarts`() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ladder(
            stopRecording = { calls += "recording" },
            stopServer = { calls += "server" },
            rebindCamera = { calls += "rebind"; true },
            startServer = { calls += "start"; StreamStartOutcome.Started },
        ).run()

        assertEquals(SoftRestartLadder.Result.Restarted, result)
        // The documented step order: recording → server stop → rebind → re-arm.
        assertEquals(listOf("recording", "server", "rebind", "start"), calls)
    }

    @Test
    fun `a throwing stop never blocks the recovery attempt`() = runBlocking {
        val result = ladder(
            stopRecording = { throw IllegalStateException("recorder wedged") },
            stopServer = { throw IllegalStateException("server wedged") },
        ).run()
        assertEquals(SoftRestartLadder.Result.Restarted, result)
    }

    @Test
    fun `a failed start reports the startServer step`() = runBlocking {
        val result = ladder(
            startServer = { StreamStartOutcome.StartFailed(kind = null) },
        ).run()
        assertEquals(SoftRestartLadder.Result.StepFailed(SoftRestartLadder.Step.START_SERVER, "Failed to start server"), result)
    }

    @Test
    fun `a rolled-back session begin reports the startServer step`() = runBlocking {
        val result = ladder(
            startServer = {
                StreamStartOutcome.BeginFailedRolledBack(kind = null, cause = IllegalStateException("no FGS"))
            },
        ).run()
        assertEquals(
            SoftRestartLadder.Result.StepFailed(SoftRestartLadder.Step.START_SERVER, "Failed to start server"),
            result,
        )
    }

    @Test
    fun `a throwing start reports the startServer step`() = runBlocking {
        val result = ladder(
            startServer = { throw IllegalStateException("boom") },
        ).run()
        assertEquals(SoftRestartLadder.Result.StepFailed(SoftRestartLadder.Step.START_SERVER, "boom"), result)
    }

    @Test
    fun `an unbound rebind reports the rebindCamera step but the re-arm still ran`() = runBlocking {
        var started = false
        val result = ladder(
            rebindCamera = { false },
            startServer = { started = true; StreamStartOutcome.Started },
        ).run()
        assertEquals(true, started)
        assertEquals(true, result is SoftRestartLadder.Result.StepFailed)
        assertEquals(SoftRestartLadder.Step.REBIND_CAMERA, (result as SoftRestartLadder.Result.StepFailed).step)
    }

    @Test
    fun `a failed re-arm outranks a failed rebind in the report`() = runBlocking {
        val result = ladder(
            rebindCamera = { false },
            startServer = { StreamStartOutcome.StartFailed(kind = StreamKind.RTSP) },
        ).run()
        // A dark server is the worse truth — the report names the re-arm.
        assertEquals(SoftRestartLadder.Step.START_SERVER, (result as SoftRestartLadder.Result.StepFailed).step)
    }
}
