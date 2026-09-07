package com.raulshma.lenscast.camera.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamToggleTest {

    /** Scriptable fake: records every call so the ladder order is assertable. */
    private class FakeTransports(
        override var webEnabled: Boolean = true,
        override var rtspEnabled: Boolean = true,
        override var webActive: Boolean = false,
        override var rtspActive: Boolean = false,
        var startWebResult: Boolean = true,
        var startRtspResult: Boolean = true,
        var beginSessionError: Exception? = null,
    ) : StreamToggle.Transports {
        val calls = mutableListOf<String>()

        override fun startWeb(): Boolean {
            calls.add("startWeb")
            webActive = startWebResult
            return startWebResult
        }

        override fun stopWeb() {
            calls.add("stopWeb")
            webActive = false
        }

        override fun startRtsp(): Boolean {
            calls.add("startRtsp")
            rtspActive = startRtspResult
            return startRtspResult
        }

        override fun stopRtsp() {
            calls.add("stopRtsp")
            rtspActive = false
        }

        override fun stopServer() {
            calls.add("stopServer")
            webActive = false
            rtspActive = false
        }

        override suspend fun beginSession() {
            calls.add("beginSession")
            beginSessionError?.let { throw it }
        }

        override suspend fun endSession() {
            calls.add("endSession")
        }
    }

    // ── the four start outcomes ──

    @Test
    fun `a gated output reports Disabled without touching the transports`() = runBlocking {
        val transports = FakeTransports(webEnabled = false)
        val outcome = StreamToggle(transports).startWeb()

        assertEquals(StreamKind.WEB, (outcome as StreamStartOutcome.Disabled).kind)
        assertTrue(transports.calls.isEmpty())
    }

    @Test
    fun `a failed start reports StartFailed without a session begin`() = runBlocking {
        val transports = FakeTransports(startWebResult = false)
        val outcome = StreamToggle(transports).startWeb()

        assertEquals(StreamKind.WEB, (outcome as StreamStartOutcome.StartFailed).kind)
        assertEquals(listOf("startWeb"), transports.calls)
    }

    @Test
    fun `a successful start begins the session`() = runBlocking {
        val transports = FakeTransports()
        val outcome = StreamToggle(transports).startRtsp()

        assertEquals(StreamStartOutcome.Started, outcome)
        assertEquals(listOf("startRtsp", "beginSession"), transports.calls)
        assertTrue(transports.rtspActive)
    }

    @Test
    fun `a failed session begin rolls back the just-started stream`() = runBlocking {
        val failure = IllegalStateException("foreground service denied")
        val transports = FakeTransports(beginSessionError = failure)
        val outcome = StreamToggle(transports).startWeb()

        assertEquals(StreamKind.WEB, (outcome as StreamStartOutcome.BeginFailedRolledBack).kind)
        assertEquals(failure, outcome.cause)
        // The rollback ladder: start → begin throws → stop the stream; no
        // orphaned live stream and no end (nothing to tear down).
        assertEquals(listOf("startWeb", "beginSession", "stopWeb"), transports.calls)
        assertEquals(false, transports.webActive)
    }

    // ── toggle and stop paths ──

    @Test
    fun `toggle starts when inactive and stops with session end when active`() = runBlocking {
        val starting = FakeTransports()
        val toggle = StreamToggle(starting)
        assertEquals(StreamStartOutcome.Started, toggle.toggleWeb())

        starting.webActive = true
        assertEquals(StreamStartOutcome.Stopped, toggle.toggleWeb())
        assertEquals(listOf("startWeb", "beginSession", "stopWeb", "endSession"), starting.calls)
    }

    @Test
    fun `toggle rtsp consults the rtsp gate`() = runBlocking {
        val transports = FakeTransports(rtspEnabled = false)
        val outcome = StreamToggle(transports).toggleRtsp()

        assertEquals(StreamKind.RTSP, (outcome as StreamStartOutcome.Disabled).kind)
        assertTrue(transports.calls.isEmpty())
    }

    @Test
    fun `stopServer stops both outputs and ends the session`() = runBlocking {
        val transports = FakeTransports(webActive = true, rtspActive = true)
        val outcome = StreamToggle(transports).stopServer()

        assertEquals(StreamStartOutcome.Stopped, outcome)
        assertEquals(listOf("stopServer", "endSession"), transports.calls)
        assertEquals(false, transports.webActive)
        assertEquals(false, transports.rtspActive)
    }

    // ── pre-start hook ──

    @Test
    fun `the pre-start hook runs after the gate and before the start`() = runBlocking {
        val hookCalls = mutableListOf<StreamKind>()
        val transports = FakeTransports(webEnabled = false)
        StreamToggle(
            transports,
            onBeforeStart = { hookCalls.add(it) },
        ).startWeb()

        // Gated: the hook never ran.
        assertTrue(hookCalls.isEmpty())

        transports.webEnabled = true
        StreamToggle(
            transports,
            onBeforeStart = { hookCalls.add(it) },
        ).startWeb()

        assertEquals(listOf(StreamKind.WEB), hookCalls)
    }

    // ── outcome messages ──

    @Test
    fun `outcome messages keep the camera-screen wording`() {
        assertEquals("Web streaming is disabled in settings.", StreamStartOutcome.disabledMessage(StreamKind.WEB))
        assertEquals("RTSP streaming is disabled in settings.", StreamStartOutcome.disabledMessage(StreamKind.RTSP))
        assertEquals("Failed to start web streaming.", StreamStartOutcome.startFailedMessage(StreamKind.WEB))
        assertEquals("Failed to start RTSP streaming.", StreamStartOutcome.startFailedMessage(StreamKind.RTSP))
    }
}
