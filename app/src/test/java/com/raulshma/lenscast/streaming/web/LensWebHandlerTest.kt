package com.raulshma.lenscast.streaming.web

import android.app.Application
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.data.SettingsDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.CoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The `/api/camera/torch` body contract (the drift half of issue #21): the
 * handler has always required an explicit body and an explicit `enabled` —
 * an omitted or `{}` body throws rather than toggling — but the OpenAPI
 * schema used to document an optional field with an "omitted toggles"
 * promise no code ever implemented. The schema now marks the field required
 * and the summary says so; these tests pin the handler side so the
 * documented contract and the wire behavior cannot drift apart again.
 *
 * The zoom persist tests share the file: issue #25's second whole-document
 * write. `setZoom` used to copy the shared flow's `.value` — the
 * placeholder default until DataStore's first read lands (the
 * default-first race) — so a remote zoom landing inside that window saved
 * the DEFAULTS over every stored camera setting. The copy base is now
 * `settingsFromDisk()`, and the tests hold the race open deterministically
 * with the parked-dispatcher harness of `SettingsDataStoreDiskReadTest`
 * (real DataStore, real file; the share-in collectors never run, so the
 * flow stays at its default while the disk holds truth).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LensWebHandlerTest.PlainApplication::class)
class LensWebHandlerTest {

    class PlainApplication : Application()

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

    // ── setZoom: the persist reads disk truth, not the lagging flow ──

    /**
     * A dispatcher that parks every dispatched block forever: the store's
     * eagerly-shared collectors queue up and never execute, so the settings
     * StateFlow stays at its descriptor default for the whole test — the
     * default-first race, held open deterministically.
     */
    private val parkedDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // Parked on purpose — see the KDoc.
        }
    }

    @Test
    fun `setZoom persists the accepted ratio onto disk truth and keeps every sibling field`() = runBlocking {
        val store = SettingsDataStore(
            RuntimeEnvironment.getApplication(),
            CoroutineScope(SupervisorJob() + parkedDispatcher),
        )
        // Disk truth that discriminates from the descriptor default: a zoom
        // a previous session stored, plus a non-default camera field an
        // automation rule wrote.
        store.saveSettings(CameraSettings(zoomRatio = 2.5f, iso = 3200))
        // The flow is still at the placeholder default — the parked
        // dispatcher guarantees it, and the assertion proves the persist
        // below cannot be answering from it.
        assertEquals(
            CameraSettings(),
            store.settings.value,
        )

        every { cameraService.setZoomRatio(3.0f) } returns true
        every { cameraService.availableZoomRange } returns
            MutableStateFlow(1f..8f)
        val zoomHandler = LensWebHandler(cameraService, store)

        val response = zoomHandler.setZoom("""{"zoomRatio":3.0}""")

        assertTrue(response.contains("\"success\":true"))
        // The zoom landed, and the sibling field the flow never saw is intact.
        val persisted = store.settingsFromDisk()
        assertEquals(3.0f, persisted.zoomRatio)
        assertEquals(3200, persisted.iso)
    }

    @Test
    fun `setZoom clamps the persisted ratio to the device range`() = runBlocking {
        val store = SettingsDataStore(
            RuntimeEnvironment.getApplication(),
            CoroutineScope(SupervisorJob() + parkedDispatcher),
        )
        every { cameraService.setZoomRatio(50f) } returns true
        every { cameraService.availableZoomRange } returns
            MutableStateFlow(1f..8f)
        val zoomHandler = LensWebHandler(cameraService, store)

        zoomHandler.setZoom("""{"zoomRatio":50.0}""")

        assertEquals(8f, store.settingsFromDisk().zoomRatio)
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
