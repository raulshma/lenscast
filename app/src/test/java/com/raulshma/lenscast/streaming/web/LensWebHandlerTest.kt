package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.camera.CameraService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The `/api/camera/torch` body contract (the drift half of issue #21): the
 * handler has always required an explicit body and an explicit `enabled` —
 * an omitted or `{}` body throws rather than toggling — but the OpenAPI
 * schema used to document an optional field with an "omitted toggles"
 * promise no code ever implemented. The schema now marks the field required
 * and the summary says so; these tests pin the handler side so the
 * documented contract and the wire behavior cannot drift apart again.
 */
class LensWebHandlerTest {

    private val cameraService: CameraService = mockk()
    private val handler = LensWebHandler(cameraService)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setTorch drives the explicit enabled verdict through the live seam`() = runBlocking {
        every { cameraService.setTorchEnabled(true) } returns true

        val response = handler.setTorch("""{"enabled":true}""")

        assertTrue(response.contains("\"success\":true"))
        verify(exactly = 1) { cameraService.setTorchEnabled(true) }
    }

    @Test
    fun `an omitted torch body throws and never touches the torch`() = runBlocking {
        // The omitted body arrives as the empty string (StreamingServer
        // builds ApiRequest with a default empty body).
        expectTorchError { handler.setTorch("") }
        verify(exactly = 0) { cameraService.setTorchEnabled(any()) }
    }

    @Test
    fun `an empty-object torch body throws and never touches the torch`() = runBlocking {
        expectTorchError { handler.setTorch("{}") }
        verify(exactly = 0) { cameraService.setTorchEnabled(any()) }
    }

    @Test
    fun `an explicit-null enabled throws the same way`() = runBlocking {
        expectTorchError { handler.setTorch("""{"enabled":null}""") }
        verify(exactly = 0) { cameraService.setTorchEnabled(any()) }
    }

    /** The one body-parse shape: a rejected body throws, the LED never moves. */
    private inline fun expectTorchError(body: () -> Unit) {
        try {
            body()
            fail("a rejected torch body must throw the documented error")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
