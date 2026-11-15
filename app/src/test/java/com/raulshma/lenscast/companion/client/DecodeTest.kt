package com.raulshma.lenscast.companion.client

import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The client's decode lane end to end: a recorded `/api/status` document
 * (the trimmed sample from the checked-in web/contract/status.json fixture)
 * served over MockWebServer decodes into CoreDtos' [DeviceStatus] through
 * the same Moshi adapters the fixture lockstep test pins — and the command
 * envelope (`{success, error?, url?}`) decodes through [CommandResponse].
 */
class DecodeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = LenscastApiClient(
        CompanionDevice(
            id = "test-device",
            name = "Test Device",
            host = "127.0.0.1",
            port = server.port,
            auth = CompanionAuthKind.TOKEN,
            role = "admin",
            addedAtMs = 0L,
        ),
    ) { StoredCredentials(CompanionAuthKind.TOKEN, token = "tok-123") }

    /** The required-fields skeleton of web/contract/status.json, verbatim values. */
    private val statusSample = """
        {
          "streaming": {
            "isActive": true,
            "url": "http://192.168.1.10:8080/stream",
            "webStreamingEnabled": true,
            "webStreamingActive": true,
            "clientCount": 2,
            "audioEnabled": true,
            "audioUrl": "http://192.168.1.10:8080/audio",
            "rtspEnabled": true,
            "rtspStreamingActive": true,
            "rtspUrl": "rtsp://192.168.1.10:8554/live",
            "rtmpStatus": "idle",
            "whepClients": 0
          },
          "thermal": "NORMAL",
          "camera": "Ready",
          "battery": {"level": 87, "isCharging": false, "isPowerSaveMode": false},
          "torchOn": false,
          "sirenActive": false,
          "zoomRatio": 1.75,
          "lensId": "0",
          "lensLabel": "Wide",
          "zoomRange": {"min": 1.0, "max": 8.0}
        }
    """.trimIndent()

    @Test
    fun `status payload served over the wire decodes into DeviceStatus`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(statusSample))

        val status = runBlocking { client().getStatus() }

        assertEquals(true, status.streaming.isActive)
        assertEquals("http://192.168.1.10:8080/stream", status.streaming.url)
        assertEquals(2, status.streaming.clientCount)
        assertEquals(0, status.streaming.whepClients)
        assertEquals("NORMAL", status.thermal)
        assertEquals(87, status.battery.level)
        assertEquals("Ready", status.camera)
        assertEquals(false, status.torchOn)
        assertEquals(false, status.sirenActive)
        assertEquals(1.75, status.zoomRatio, 0.0)
        assertEquals("Wide", status.lensLabel)
        assertEquals(1.0, status.zoomRange?.min)
        assertEquals(8.0, status.zoomRange?.max)
        assertEquals("/api/status", server.takeRequest().path)
    }

    @Test
    fun `command envelope decodes success url and error`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"isActive":true,"url":"http://192.168.1.10:8080/stream"}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"success":false,"error":"Camera not available"}"""),
        )

        val start = runBlocking { client().setWebStreamRunning(start = true) }
        val capture = runBlocking { client().capturePhoto() }

        assertTrue(start.success)
        assertEquals("http://192.168.1.10:8080/stream", start.url)
        assertTrue(start.isActive == true)
        assertEquals(false, capture.success)
        assertEquals("Camera not available", capture.error)
        assertNull(capture.url)
        assertEquals("/api/stream/web/start", server.takeRequest().path)
        assertEquals("/api/capture", server.takeRequest().path)
    }
}
