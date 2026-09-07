package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspUriPolicyTest {

    // ── normalizedPath ──

    @Test
    fun `normalizedPath strips scheme authority query fragment slashes and whitespace`() {
        assertEquals("/stream", RtspUriPolicy.normalizedPath("rtsp://host:8554/stream"))
        assertEquals("/stream", RtspUriPolicy.normalizedPath("stream"))
        assertEquals("/stream", RtspUriPolicy.normalizedPath("/stream/"))
        assertEquals("/stream", RtspUriPolicy.normalizedPath("//stream//"))
        assertEquals("/stream", RtspUriPolicy.normalizedPath("/stream?port=0&ttl=1"))
        assertEquals("/stream", RtspUriPolicy.normalizedPath("/stream#frag"))
        assertEquals("/stream", RtspUriPolicy.normalizedPath("/stream  "))
        assertEquals("/stream/trackID=0", RtspUriPolicy.normalizedPath("rtsp://h/stream/trackID=0?s=1"))
        assertEquals("/a/b/c", RtspUriPolicy.normalizedPath("/a//b///c"))
        // Leading whitespace is not trimmed: the leading-slash guarantee runs before
        // the trim, so it becomes part of the path (locked quirk).
        assertEquals("/  /stream", RtspUriPolicy.normalizedPath("  /stream"))
    }

    @Test
    fun `normalizedPath of empty and degenerate inputs is root`() {
        assertEquals("/", RtspUriPolicy.normalizedPath(""))
        assertEquals("/", RtspUriPolicy.normalizedPath("?x=1"))
        assertEquals("/", RtspUriPolicy.normalizedPath("#"))
        assertEquals("/", RtspUriPolicy.normalizedPath("rtsp://host:8554"))
        assertEquals("/", RtspUriPolicy.normalizedPath("rtsp://"))
    }

    // ── extractRtspPath ──

    @Test
    fun `extractRtspPath strips scheme and authority case-insensitively`() {
        assertEquals("/stream/trackID=0", RtspUriPolicy.extractRtspPath("rtsp://host:8554/stream/trackID=0"))
        assertEquals("/stream", RtspUriPolicy.extractRtspPath("RTSP://host/stream"))
        assertEquals("/", RtspUriPolicy.extractRtspPath("rtsp://host"))
        assertEquals("/", RtspUriPolicy.extractRtspPath("rtsp://"))
        // Non-absolute input passes through with a guaranteed leading slash.
        assertEquals("/trackID=0", RtspUriPolicy.extractRtspPath("trackID=0"))
        assertEquals("/stream", RtspUriPolicy.extractRtspPath("/stream"))
    }

    // ── isAggregateOrStreamUri ──

    @Test
    fun `aggregate uri accepts only root and the stream path`() {
        assertTrue(RtspUriPolicy.isAggregateOrStreamUri("/"))
        assertTrue(RtspUriPolicy.isAggregateOrStreamUri("/stream"))
        assertTrue(RtspUriPolicy.isAggregateOrStreamUri("/stream/"))
        assertTrue(RtspUriPolicy.isAggregateOrStreamUri("rtsp://h:8554/stream"))
        assertFalse(RtspUriPolicy.isAggregateOrStreamUri("/other"))
        assertFalse(RtspUriPolicy.isAggregateOrStreamUri("/stream2"))
        assertFalse(RtspUriPolicy.isAggregateOrStreamUri("/stream/trackID=0"))
    }

    // ── isStreamControlUri ──

    @Test
    fun `stream control uris cover the stream path and trackid forms`() {
        assertTrue(RtspUriPolicy.isStreamControlUri("/stream"))
        assertTrue(RtspUriPolicy.isStreamControlUri("/trackid=0"))
        assertTrue(RtspUriPolicy.isStreamControlUri("/trackID=0"))
        assertTrue(RtspUriPolicy.isStreamControlUri("/TrackId=0"))
        assertTrue(RtspUriPolicy.isStreamControlUri("/stream/trackid=0"))
        assertTrue(RtspUriPolicy.isStreamControlUri("/stream/trackID=1"))
        // Prefix match: any /stream/track* form counts as stream control.
        assertTrue(RtspUriPolicy.isStreamControlUri("/stream/track1"))
        assertTrue(RtspUriPolicy.isStreamControlUri("/stream/tracking"))
        assertFalse(RtspUriPolicy.isStreamControlUri("/"))
        assertFalse(RtspUriPolicy.isStreamControlUri("/trackid=1"))
        assertFalse(RtspUriPolicy.isStreamControlUri("/other"))
    }

    // ── isTrackUri ──

    @Test
    fun `track uris accept the bare trackID forms and the stream-prefixed prefix`() {
        assertTrue(RtspUriPolicy.isTrackUri("/trackID=0"))
        assertTrue(RtspUriPolicy.isTrackUri("/trackid=1"))
        assertTrue(RtspUriPolicy.isTrackUri("/stream/trackID=0"))
        assertTrue(RtspUriPolicy.isTrackUri("/stream/trackid=3"))
        // Prefix-only under /stream: even non-numeric suffixes pass.
        assertTrue(RtspUriPolicy.isTrackUri("/stream/trackID=abc"))
        assertFalse(RtspUriPolicy.isTrackUri("/trackID=2"))
        assertFalse(RtspUriPolicy.isTrackUri("/stream"))
        assertFalse(RtspUriPolicy.isTrackUri("/"))
    }

    // ── resolveTrackId ──

    @Test
    fun `track resolution is case-insensitive and defaults aggregate to video`() {
        assertEquals(0, RtspUriPolicy.resolveTrackId("/"))
        assertEquals(0, RtspUriPolicy.resolveTrackId("/stream"))
        assertEquals(0, RtspUriPolicy.resolveTrackId("/stream?x=1"))
        assertEquals(0, RtspUriPolicy.resolveTrackId("/trackID=0"))
        assertEquals(0, RtspUriPolicy.resolveTrackId("/trackid=0"))
        assertEquals(1, RtspUriPolicy.resolveTrackId("/trackID=1"))
        assertEquals(0, RtspUriPolicy.resolveTrackId("/stream/trackID=0"))
        assertEquals(1, RtspUriPolicy.resolveTrackId("/STREAM/TrackID=1"))
    }

    @Test
    fun `track resolution returns null for unknown or out-of-range tracks`() {
        assertNull(RtspUriPolicy.resolveTrackId("/trackID=2"))
        assertNull(RtspUriPolicy.resolveTrackId("/stream/trackID=7"))
        assertNull(RtspUriPolicy.resolveTrackId("/stream/trackID=abc"))
        assertNull(RtspUriPolicy.resolveTrackId("/other"))
        assertNull(RtspUriPolicy.resolveTrackId("/streamx"))
    }

    // ── isRequestUriAllowed ──

    @Test
    fun `OPTIONS and DESCRIBE address only aggregate uris`() {
        for (method in listOf("OPTIONS", "DESCRIBE")) {
            assertTrue(RtspUriPolicy.isRequestUriAllowed(method, "/"))
            assertTrue(RtspUriPolicy.isRequestUriAllowed(method, "/stream"))
            assertFalse(RtspUriPolicy.isRequestUriAllowed(method, "/stream/trackID=0"))
            assertFalse(RtspUriPolicy.isRequestUriAllowed(method, "/trackID=0"))
            assertFalse(RtspUriPolicy.isRequestUriAllowed(method, "/other"))
        }
    }

    @Test
    fun `SETUP addresses stream control and track uris but not the aggregate root`() {
        assertFalse(RtspUriPolicy.isRequestUriAllowed("SETUP", "/"))
        assertTrue(RtspUriPolicy.isRequestUriAllowed("SETUP", "/stream"))
        assertTrue(RtspUriPolicy.isRequestUriAllowed("SETUP", "/trackid=0"))
        assertTrue(RtspUriPolicy.isRequestUriAllowed("SETUP", "/trackID=1"))
        assertTrue(RtspUriPolicy.isRequestUriAllowed("SETUP", "/stream/trackID=0"))
        assertFalse(RtspUriPolicy.isRequestUriAllowed("SETUP", "/other"))
    }

    @Test
    fun `PLAY and TEARDOWN address aggregate and stream-control uris`() {
        for (method in listOf("PLAY", "TEARDOWN")) {
            assertTrue(RtspUriPolicy.isRequestUriAllowed(method, "/"))
            assertTrue(RtspUriPolicy.isRequestUriAllowed(method, "/stream"))
            assertTrue(RtspUriPolicy.isRequestUriAllowed(method, "/trackid=0"))
            assertTrue(RtspUriPolicy.isRequestUriAllowed(method, "/stream/trackid=0"))
            // Bare /trackID=1 is a SETUP-only form: neither aggregate nor stream control.
            assertFalse(RtspUriPolicy.isRequestUriAllowed(method, "/trackID=1"))
            assertFalse(RtspUriPolicy.isRequestUriAllowed(method, "/other"))
        }
    }

    @Test
    fun `parameter methods and unknown methods accept any uri`() {
        for (method in listOf("GET_PARAMETER", "SET_PARAMETER", "FOO")) {
            assertTrue(RtspUriPolicy.isRequestUriAllowed(method, "/"))
            assertTrue(RtspUriPolicy.isRequestUriAllowed(method, "/stream/trackID=0"))
            assertTrue(RtspUriPolicy.isRequestUriAllowed(method, "/whatever"))
        }
    }
}
