package com.raulshma.lenscast.streaming.onvif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnvifResponsesTest {

    private val serviceUrl = "http://192.168.1.5:8080/onvif/device_service"

    @Test
    fun `get system date and time contains the response element and utc time`() {
        // 2026-09-09 12:34:56 UTC.
        val utc = java.util.GregorianCalendar(
            java.util.TimeZone.getTimeZone("UTC"),
        ).apply { clear(); set(2026, 8, 9, 12, 34, 56) }.timeInMillis
        val xml = OnvifResponses.getSystemDateAndTime(utc)
        assertTrue(xml.contains("<tds:GetSystemDateAndTimeResponse>"))
        assertTrue(xml.contains("<tt:DaylightSavings>false</tt:DaylightSavings>"))
        assertTrue(xml.contains("<tt:TZ>UTC</tt:TZ>"))
        assertTrue(xml.contains("<tt:Year>2026</tt:Year>"))
        assertTrue(xml.contains("<tt:Month>9</tt:Month>"))
        assertTrue(xml.contains("<tt:Hour>12</tt:Hour>"))
    }

    @Test
    fun `get capabilities advertises device and media with streaming uri`() {
        val xml = OnvifResponses.getCapabilities(serviceUrl)
        assertTrue(xml.contains("<tds:GetCapabilitiesResponse>"))
        assertTrue(xml.contains("<tt:Device><tt:XAddr>$serviceUrl</tt:XAddr></tt:Device>"))
        assertTrue(xml.contains("<tt:Media>"))
        assertTrue(xml.contains("<tt:StreamingUri>true</tt:StreamingUri>"))
        assertTrue(xml.contains("<tt:RTP_TCP>true</tt:RTP_TCP>"))
    }

    @Test
    fun `get services lists device and media entries with the xaddr`() {
        val xml = OnvifResponses.getServices(serviceUrl)
        assertTrue(xml.contains("<tds:GetServicesResponse>"))
        assertTrue(xml.contains("<tt:Namespace>http://www.onvif.org/ver10/device/wsdl</tt:Namespace>"))
        assertTrue(xml.contains("<tt:Namespace>http://www.onvif.org/ver20/media/wsdl</tt:Namespace>"))
        assertEquals(2, Regex("<tt:XAddr>").findAll(xml).count())
        assertTrue(xml.contains("<tt:XAddr>$serviceUrl</tt:XAddr>"))
    }

    @Test
    fun `get device information contains the identity fields escaped`() {
        val xml = OnvifResponses.getDeviceInformation(
            manufacturer = "LensCast",
            model = "Pixel<Test>",
            firmwareVersion = "0.0.7",
            serialNumber = "abc&123",
            hardwareId = "android",
        )
        assertTrue(xml.contains("<tds:GetDeviceInformationResponse>"))
        assertTrue(xml.contains("<tt:Manufacturer>LensCast</tt:Manufacturer>"))
        assertTrue(xml.contains("<tt:Model>Pixel&lt;Test&gt;</tt:Model>"))
        assertTrue(xml.contains("<tt:FirmwareVersion>0.0.7</tt:FirmwareVersion>"))
        assertTrue(xml.contains("<tt:SerialNumber>abc&amp;123</tt:SerialNumber>"))
        assertTrue(xml.contains("<tt:HardwareId>android</tt:HardwareId>"))
    }

    @Test
    fun `get video sources contains the single source token and resolution`() {
        val xml = OnvifResponses.getVideoSources(width = 1280, height = 720, fps = 24)
        assertTrue(xml.contains("<trt:GetVideoSourcesResponse>"))
        assertTrue(xml.contains("token=\"${OnvifTokens.VIDEO_SOURCE_TOKEN}\""))
        assertTrue(xml.contains("<tt:Width>1280</tt:Width>"))
        assertTrue(xml.contains("<tt:Height>720</tt:Height>"))
        assertEquals(1, Regex("<tt:VideoSource ").findAll(xml).count())
    }

    @Test
    fun `get profiles contains the h264 encoder with configured values`() {
        val xml = OnvifResponses.getProfiles(
            width = 1920,
            height = 1080,
            videoBitrate = 2_000_000,
            fps = 30,
            audioEnabled = false,
        )
        assertTrue(xml.contains("<trt:GetProfilesResponse>"))
        assertTrue(xml.contains("token=\"${OnvifTokens.PROFILE_TOKEN}\""))
        assertTrue(xml.contains("<tt:Encoding>H264</tt:Encoding>"))
        assertTrue(xml.contains("<tt:Width>1920</tt:Width>"))
        assertTrue(xml.contains("<tt:FrameRateLimit>30</tt:FrameRateLimit>"))
        // ONVIF advertises the bitrate in kbps; LensCast configures in bps.
        assertTrue(xml.contains("<tt:BitrateLimit>2000</tt:BitrateLimit>"))
        assertFalse(xml.contains("<tt:Encoding>AAC</tt:Encoding>"))
    }

    @Test
    fun `get profiles includes the aac encoder only when audio is enabled`() {
        val withAudio = OnvifResponses.getProfiles(
            width = 1280,
            height = 720,
            videoBitrate = 2_000_000,
            fps = 24,
            audioEnabled = true,
        )
        assertTrue(withAudio.contains("<tt:Encoding>AAC</tt:Encoding>"))
        assertTrue(withAudio.contains("token=\"${OnvifTokens.AUDIO_ENCODER_TOKEN}\""))
        val withoutAudio = OnvifResponses.getProfiles(
            width = 1280,
            height = 720,
            videoBitrate = 2_000_000,
            fps = 24,
            audioEnabled = false,
        )
        assertFalse(withoutAudio.contains("<tt:AudioEncoderConfiguration"))
    }

    @Test
    fun `get stream uri embeds the passed uri escaped`() {
        val xml = OnvifResponses.getStreamUri("rtsp://192.168.1.5:8554/stream?a=1&b=2")
        assertTrue(xml.contains("<trt:GetStreamUriResponse>"))
        assertTrue(xml.contains("<tt:Uri>rtsp://192.168.1.5:8554/stream?a=1&amp;b=2</tt:Uri>"))
    }

    @Test
    fun `get snapshot uri embeds the passed uri`() {
        val xml = OnvifResponses.getSnapshotUri("http://192.168.1.5:8080/snapshot")
        assertTrue(xml.contains("<trt:GetSnapshotUriResponse>"))
        assertTrue(xml.contains("<tt:Uri>http://192.168.1.5:8080/snapshot</tt:Uri>"))
    }

    @Test
    fun `unknown operation fault carries the ter action not supported subcode`() {
        val xml = OnvifResponses.fault()
        assertTrue(xml.contains("<s:Fault>"))
        assertTrue(xml.contains("ter:ActionNotSupported"))
        assertTrue(xml.contains("<s:Value>s:Sender</s:Value>"))
    }

    @Test
    fun `every response is a complete soap envelope`() {
        val responses = listOf(
            OnvifResponses.getSystemDateAndTime(0L),
            OnvifResponses.getCapabilities(serviceUrl),
            OnvifResponses.getServices(serviceUrl),
            OnvifResponses.getDeviceInformation("m", "mo", "f", "s", "h"),
            OnvifResponses.getVideoSources(width = 1, height = 1, fps = 1),
            OnvifResponses.getProfiles(width = 1, height = 1, videoBitrate = 1, fps = 1, audioEnabled = true),
            OnvifResponses.getStreamUri("rtsp://x/stream"),
            OnvifResponses.getSnapshotUri("http://x/snapshot"),
            OnvifResponses.fault(),
        )
        responses.forEach { xml ->
            assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
            assertTrue(xml.contains("xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""))
            assertTrue(xml.contains("xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\""))
            assertTrue(xml.contains("xmlns:trt=\"http://www.onvif.org/ver20/media/wsdl\""))
            assertTrue(xml.contains("xmlns:tdn=\"http://www.onvif.org/ver10/network/wsdl\""))
            assertTrue(xml.contains("<s:Body>"))
            assertTrue(xml.endsWith("</s:Envelope>"))
        }
    }

    @Test
    fun `xml escape escapes ampersand angle quotes and apostrophe`() {
        assertEquals("&amp;&lt;&gt;&apos;&quot;", xmlEscape("&<>'\""))
        assertEquals("a&amp;b", xmlEscape("a&b"))
        assertEquals("", xmlEscape(""))
    }
}
