package com.raulshma.lenscast.streaming.onvif

import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import org.junit.Assert.assertTrue
import org.junit.Test

class OnvifServerTest {

    private fun server(
        enabled: () -> Boolean = { true },
        httpsEnabled: () -> Boolean = { false },
        videoCodec: () -> RtspVideoCodec = { RtspVideoCodec.H264 },
    ) = OnvifServer(
        ipAddress = { "192.168.1.5" },
        rtspPort = { 8554 },
        webPort = { 8080 },
        audioEnabled = { true },
        enabled = enabled,
        httpsEnabled = httpsEnabled,
        videoWidth = { 1280 },
        videoHeight = { 720 },
        videoBitrate = { 2_000_000 },
        videoFps = { 24 },
        videoCodec = videoCodec,
        firmwareVersion = "0.0.7",
        serialNumber = "serial-1",
        now = { 0L },
    )

    @Test
    fun `handle routes the soap operation to its response`() {
        assertTrue(
            server().handle(
                "<s:Envelope><s:Body><tds:GetDeviceInformation/></s:Body></s:Envelope>",
            ).contains("<tds:GetDeviceInformationResponse>"),
        )
        assertTrue(
            server().handle(
                "<s:Envelope><s:Body><trt:GetProfiles/></s:Body></s:Envelope>",
            ).contains("<trt:GetProfilesResponse>"),
        )
        assertTrue(
            server().handle(
                "<s:Envelope><s:Body><tds:GetCapabilities/></s:Body></s:Envelope>",
            ).contains("<tds:GetCapabilitiesResponse>"),
        )
    }

    @Test
    fun `handle answers a bodyless request with the date and time response`() {
        assertTrue(server().handle(null).contains("<tds:GetSystemDateAndTimeResponse>"))
        assertTrue(server().handle("").contains("<tds:GetSystemDateAndTimeResponse>"))
    }

    @Test
    fun `handle answers an unknown operation with the fault`() {
        assertTrue(
            server().handle(
                "<s:Envelope><s:Body><tds:SetSomethingDangerous/></s:Body></s:Envelope>",
            ).contains("ter:ActionNotSupported"),
        )
        // Garbage is malformed, not bodyless — fault, not a date answer.
        assertTrue(server().handle("garbage").contains("ter:ActionNotSupported"))
    }

    @Test
    fun `handle builds the stream and snapshot uris from the providers`() {
        val stream = server().handle(
            "<s:Envelope><s:Body><trt:GetStreamUri/></s:Body></s:Envelope>",
        )
        assertTrue(stream.contains("<tt:Uri>rtsp://192.168.1.5:8554/stream</tt:Uri>"))
        val snapshot = server().handle(
            "<s:Envelope><s:Body><trt:GetSnapshotUri/></s:Body></s:Envelope>",
        )
        assertTrue(snapshot.contains("<tt:Uri>http://192.168.1.5:8080/snapshot</tt:Uri>"))
        val services = server().handle(
            "<s:Envelope><s:Body><tds:GetServices/></s:Body></s:Envelope>",
        )
        assertTrue(services.contains("http://192.168.1.5:8080/onvif/device_service"))
    }

    @Test
    fun `a disabled service answers the fault for every operation`() {
        val response = server(enabled = { false }).handle(
            "<s:Envelope><s:Body><tds:GetDeviceInformation/></s:Body></s:Envelope>",
        )
        assertTrue(response.contains("The device service is disabled"))
    }

    @Test
    fun `https mode switches the advertised web urls to https`() {
        val snapshot = server(httpsEnabled = { true }).handle(
            "<s:Envelope><s:Body><trt:GetSnapshotUri/></s:Body></s:Envelope>",
        )
        assertTrue(snapshot.contains("<tt:Uri>https://192.168.1.5:8080/snapshot</tt:Uri>"))
    }

    @Test
    fun `the profile advertises the configured codec`() {
        val h264 = server().handle("<s:Envelope><s:Body><trt:GetProfiles/></s:Body></s:Envelope>")
        assertTrue(h264.contains("<tt:Encoding>H264</tt:Encoding>"))
        assertTrue(h264.contains("<tt:H264>"))
        val h265 = server(videoCodec = { RtspVideoCodec.H265 })
            .handle("<s:Envelope><s:Body><trt:GetProfiles/></s:Body></s:Envelope>")
        assertTrue(h265.contains("<tt:Encoding>H265</tt:Encoding>"))
        assertTrue(h265.contains("<tt:H265>"))
    }
}
