package com.raulshma.lenscast.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tile view-state pins: the cached-status → stream-state-line mapping the
 * tile renders (pure half of [WearTileService]; no Robolectric on purpose).
 */
class WearTileModelsTest {

    private fun status(
        web: Boolean = false,
        rtsp: Boolean = false,
        clients: Int = 0,
    ) = WearStatus(
        streamingActive = web || rtsp,
        clientCount = clients,
        batteryLevel = 80,
        batteryCharging = false,
        cameraState = "ready",
        thermalState = "normal",
        webStreamingActive = web,
        rtspStreamingActive = rtsp,
    )

    @Test fun `null status renders the unknown line`() {
        val view = WearTileModels.viewState(null)
        assertEquals(WearTileModels.StateLine.UNKNOWN, view.line)
        assertEquals(0, view.clientCount)
    }

    @Test fun `both transports live wins`() {
        assertEquals(
            WearTileModels.StateLine.WEB_AND_RTSP,
            WearTileModels.stateLine(status(web = true, rtsp = true)),
        )
    }

    @Test fun `web-only and rtsp-only lines`() {
        assertEquals(WearTileModels.StateLine.WEB, WearTileModels.stateLine(status(web = true)))
        assertEquals(WearTileModels.StateLine.RTSP, WearTileModels.stateLine(status(rtsp = true)))
    }

    @Test fun `no transport live reads idle`() {
        assertEquals(WearTileModels.StateLine.IDLE, WearTileModels.stateLine(status()))
    }

    @Test fun `client count rides the view state`() {
        assertEquals(3, WearTileModels.viewState(status(web = true, clients = 3)).clientCount)
    }
}
