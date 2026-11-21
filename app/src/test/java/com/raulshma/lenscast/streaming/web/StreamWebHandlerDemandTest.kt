package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `/stream` viewer demand over the real Stream Toggle ladder with a
 * mocked manager/session seam: a live output is a no-op true, a stopped one
 * starts through gate → start → session begin, and every refusal or failed
 * session attach answers false so the transport 503s instead of accepting a
 * viewer whose body will never produce a frame (issue #24).
 */
class StreamWebHandlerDemandTest {

    private val manager: StreamingManager = mockk(relaxed = true)
    private val session: StreamingSession = mockk(relaxed = true)
    private val webActive = MutableStateFlow(false)

    private fun handler(): StreamWebHandler {
        every { manager.isWebEnabled } returns MutableStateFlow(true)
        every { manager.isWebStreamingActive } returns webActive
        return StreamWebHandler(manager, session)
    }

    @Test
    fun `an already live output short-circuits without starting`() = runBlocking {
        webActive.value = true
        val handler = handler()

        assertTrue(handler.ensureWebLive())

        verify(exactly = 0) { manager.startWebStreaming() }
        coVerify(exactly = 0) { session.begin() }
    }

    @Test
    fun `a stopped output starts through the session ladder`() = runBlocking {
        every { manager.startWebStreaming() } returns true
        val handler = handler()

        assertTrue(handler.ensureWebLive())

        verify(exactly = 1) { manager.startWebStreaming() }
        coVerify(exactly = 1) { session.begin() }
    }

    @Test
    fun `a refused start answers false and never attaches a session`() = runBlocking {
        every { manager.startWebStreaming() } returns false
        val handler = handler()

        assertFalse(handler.ensureWebLive())

        coVerify(exactly = 0) { session.begin() }
        verify(exactly = 0) { manager.stopWebStreaming() }
    }

    @Test
    fun `a disabled output answers false without starting`() = runBlocking {
        every { manager.isWebEnabled } returns MutableStateFlow(false)
        every { manager.isWebStreamingActive } returns webActive
        val handler = StreamWebHandler(manager, session)

        assertFalse(handler.ensureWebLive())

        verify(exactly = 0) { manager.startWebStreaming() }
    }

    @Test
    fun `a failed session attach rolls the just-started output back and answers false`() = runBlocking {
        every { manager.startWebStreaming() } returns true
        coEvery { session.begin() } throws IllegalStateException("camera keep-alive attach timed out")
        val handler = handler()

        assertFalse(handler.ensureWebLive())

        verify(exactly = 1) { manager.stopWebStreaming() }
    }
}
