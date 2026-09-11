package com.raulshma.lenscast.streaming.onvif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ONVIF SOAP classifier and the WS-Discovery datagram classifier under
 * hostile text: a deterministic corpus fuzzer.
 *
 * ── What these parsers are ──
 * [OnvifRequestParser] routes by regex over the SOAP Body's first child —
 * deliberately not an XML parser (no namespaces, no entities, no DTDs), so
 * hostile input is only ever a "does the regex match" question. The known
 * operation names route to builders; everything else is the shared fault.
 * [WsDiscoveryProbeParser] is the same story over UDP datagrams (64 KB max
 * on the wire).
 *
 * ── The contract ──
 * operation()/isProbe()/messageId() answer null/true/false/a name — never an
 * exception, and never a CPU wedge: the regexes use possessive quantifiers,
 * so a long no-match run is linear (the pre-fix greedy forms backtracked
 * quadratically — a 1 MB garbage body burned ~10^12 steps on a server
 * thread). The oversized-input tests run under timeouts sized so the
 * quadratic form could not pass them.
 */
class OnvifDiscoveryFuzzTest {

    // ── the deterministic corpus ──

    @Test(timeout = 10_000)
    fun `corpus - hostile SOAP bodies answer null or an operation name`() {
        val hostile = listOf(
            null, "", " ", "garbage", "<<<>>>", "<>", "</Body>",
            "<Body></Body>", "<s:Body></s:Body>", "<Body></Body></Body>",
            "<Body><Body>", "<!--<Body>-->", "<?xml ?><Body>",
            "<Body><![CDATA[<GetProfiles>]]></Body>",
            "<tds:Body><tds:GetDeviceInformation/></tds:Body>",
            "<BODY><GETCAPABILITIES/></BODY>",
            "<Body><GetStreamUri></GetStreamUri></Body>",
            "<s:Body>" + "<a/>".repeat(10_000) + "</s:Body>",
            "<" + "x".repeat(100_000) + ":Body></" + "x".repeat(100_000) + ":Body>",
            "<Body>" + "text without elements " + "</Body>",
            "\u200B<Body></Body>", // zero-width space prefix
            "<Body> ".repeat(50),
        )
        for (body in hostile) {
            val operation = OnvifRequestParser.operation(body)
            if (operation != null) {
                assertTrue(operation.isNotBlank())
            }
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - the supported operations still route`() {
        assertEquals(
            "GetDeviceInformation",
            OnvifRequestParser.operation("<s:Body><tds:GetDeviceInformation/></s:Body>"),
        )
        assertEquals("GetSystemDateAndTime", OnvifRequestParser.operation("<s:Body><GetSystemDateAndTime/></s:Body>"))
        assertEquals(
            "GetStreamUri",
            OnvifRequestParser.operation("<s:Body><trt:GetStreamUri>...</trt:GetStreamUri></s:Body>"),
        )
        // The parser routes on ANY local-name; unknown ones are the caller's
        // fault branch — but they arrive as a non-null name.
        assertEquals("Nonsense", OnvifRequestParser.operation("<s:Body><Nonsense/></s:Body>"))
        assertNull(OnvifRequestParser.operation("<s:Body></s:Body>"))
    }

    // ── regression: quadratic regex backtracking on long non-matching runs ──

    @Test(timeout = 10_000)
    fun `regression - a megabyte garbage body answers null in linear time`() {
        val garbage = "a".repeat(1_000_000)
        assertNull(OnvifRequestParser.operation(garbage))
        assertNull(OnvifRequestParser.operation("<" + "a".repeat(1_000_000)))
        assertNull(OnvifRequestParser.operation("<Body>" + "a".repeat(1_000_000))) // no closing tag
    }

    @Test(timeout = 10_000)
    fun `corpus - hostile discovery datagrams answer false or null in linear time`() {
        val hostile = listOf(
            "", "garbage", "<Probe", "<Probe/>", "<d:Probe/>", "<w5:Probe />",
            "<:Probe>", "<" + "p".repeat(100_000) + "hh:Probe>",
            "<MessageID>urn:uuid:x", "<a:MessageID>urn:uuid:" + "y".repeat(100_000),
            "<d:Probe>\u0000\u0001</d:Probe>",
        )
        for (datagram in hostile) {
            WsDiscoveryProbeParser.isProbe(datagram)
            WsDiscoveryProbeParser.messageId(datagram)
        }
        // A megabyte word-run (16x the 64 KB datagram cap, for margin) must
        // not wedge the responder thread.
        val flood = "a".repeat(1_000_000)
        assertEquals(false, WsDiscoveryProbeParser.isProbe(flood))
        assertNull(WsDiscoveryProbeParser.messageId(flood))
        // Real shapes still parse.
        assertEquals(true, WsDiscoveryProbeParser.isProbe("<d:Probe xmlns:d=\"urn:schemas-xmlsoap-org:ws-discovery\">"))
        assertEquals(
            "urn:uuid:84fa6d18-4c2d-11da-a94f-00e08170ef34",
            WsDiscoveryProbeParser.messageId(
                "<a:MessageID>urn:uuid:84fa6d18-4c2d-11da-a94f-00e08170ef34</a:MessageID>",
            ),
        )
    }
}
