package com.raulshma.lenscast.streaming.onvif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeReplyBuilderTest {

    private val probe = """
        <?xml version="1.0" encoding="UTF-8"?>
        <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                    xmlns:w="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                    xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing">
          <e:Header>
            <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>
            <a:MessageID>urn:uuid:caller-message-id</a:MessageID>
            <a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>
          </e:Header>
          <e:Body>
            <w:Probe>
              <w:Types>tdn:Device</w:Types>
            </w:Probe>
          </e:Body>
        </e:Envelope>
    """.trimIndent()

    @Test
    fun `reply echoes the probe message id in relates to`() {
        val reply = ProbeReplyBuilder.reply(
            relatesToMessageId = "urn:uuid:caller-message-id",
            endpointAddress = "urn:uuid:lenscast-endpoint",
            types = "tdn:Device",
            scopes = "onvif://www.onvif.org/type/video_encoder",
            xaddrs = "http://192.168.1.5:8080/onvif/device_service",
            replyMessageId = "urn:uuid:fixed-reply-id",
        )
        assertTrue(reply.contains("<a:RelatesTo>urn:uuid:caller-message-id</a:RelatesTo>"))
        assertTrue(reply.contains("<a:MessageID>urn:uuid:fixed-reply-id</a:MessageID>"))
    }

    @Test
    fun `reply carries endpoint types xaddrs and scopes`() {
        val reply = ProbeReplyBuilder.reply(
            relatesToMessageId = null,
            endpointAddress = "urn:uuid:lenscast-endpoint",
            types = "tdn:Device",
            scopes = "onvif://www.onvif.org/hardware/android",
            xaddrs = "http://192.168.1.5:8080/onvif/device_service",
        )
        assertTrue(reply.contains("<a:Address>urn:uuid:lenscast-endpoint</a:Address>"))
        assertTrue(reply.contains("<d:Types>tdn:Device</d:Types>"))
        assertTrue(reply.contains("<d:Scopes>onvif://www.onvif.org/hardware/android</d:Scopes>"))
        assertTrue(reply.contains("<d:XAddrs>http://192.168.1.5:8080/onvif/device_service</d:XAddrs>"))
        assertTrue(reply.contains("<d:ProbeMatches>"))
        assertTrue(reply.contains("<d:MetadataVersion>1</d:MetadataVersion>"))
        assertTrue(reply.contains("ProbeMatches</a:Action>"))
    }

    @Test
    fun `reply escapes the relates to value`() {
        val reply = ProbeReplyBuilder.reply(
            relatesToMessageId = "urn:uuid:a<b&c",
            endpointAddress = "urn:uuid:e",
            types = "tdn:Device",
            scopes = "",
            xaddrs = "http://x/onvif/device_service",
        )
        assertTrue(reply.contains("<a:RelatesTo>urn:uuid:a&lt;b&amp;c</a:RelatesTo>"))
    }

    @Test
    fun `probe parser detects probes in any prefix`() {
        assertTrue(WsDiscoveryProbeParser.isProbe(probe))
        assertTrue(WsDiscoveryProbeParser.isProbe("<d:Probe><d:Types>x</d:Types></d:Probe>"))
        assertTrue(WsDiscoveryProbeParser.isProbe("<Probe><Types>x</Types></Probe>"))
        assertFalse(WsDiscoveryProbeParser.isProbe("<d:ProbeMatch><d:XAddrs>x</d:XAddrs></d:ProbeMatch>"))
        assertFalse(WsDiscoveryProbeParser.isProbe("garbage"))
    }

    @Test
    fun `probe parser extracts the message id`() {
        assertEquals("urn:uuid:caller-message-id", WsDiscoveryProbeParser.messageId(probe))
    }

    @Test
    fun `probe parser returns null when the message id is absent`() {
        assertNull(WsDiscoveryProbeParser.messageId("<d:Probe><d:Types>tdn:Device</d:Types></d:Probe>"))
        assertNull(WsDiscoveryProbeParser.messageId("garbage"))
    }
}
