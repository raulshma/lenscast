package com.raulshma.lenscast.streaming.model

import com.raulshma.lenscast.core.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clients DTO's additive RTSP half: the new [RtspClientDto] list
 * round-trips through the production AppJson adapter and the legacy fields
 * keep their names — the additive contract the dashboard reads.
 */
class StreamClientsDtoTest {

    private val adapter = AppJson.moshi.adapter(StreamClientsResponseDto::class.java)

    @Test
    fun `legacy shape serializes without the new field`() {
        val json = adapter.toJson(
            StreamClientsResponseDto(
                httpClients = listOf("mjpeg_1"),
                httpCount = 1,
                rtspCount = 2,
            ),
        )
        assertTrue(json.contains("\"httpClients\""))
        assertTrue(json.contains("\"rtspCount\":2"))
        // An empty rtspClients list still renders (Moshi emits all fields).
        assertTrue(json.contains("\"rtspClients\":[]"))
    }

    @Test
    fun `rtsp client entries round-trip`() {
        val dto = StreamClientsResponseDto(
            httpClients = emptyList(),
            httpCount = 0,
            rtspCount = 1,
            rtspClients = listOf(
                RtspClientDto(
                    id = "3",
                    remoteAddress = "192.168.1.50:51000",
                    connectedAtMs = 1_788_825_600_000,
                    transport = "RTP/AVP/TCP;interleaved",
                    media = listOf("video", "audio", "sub"),
                    playing = true,
                    framesSent = 1234,
                ),
            ),
        )
        val parsed = adapter.fromJson(adapter.toJson(dto))!!
        assertEquals(1, parsed.rtspClients.size)
        val client = parsed.rtspClients.first()
        assertEquals("3", client.id)
        assertEquals("192.168.1.50:51000", client.remoteAddress)
        assertEquals(1_788_825_600_000, client.connectedAtMs)
        assertEquals("RTP/AVP/TCP;interleaved", client.transport)
        assertEquals(listOf("video", "audio", "sub"), client.media)
        assertTrue(client.playing)
        assertEquals(1234, client.framesSent)
    }
}
