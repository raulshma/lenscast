package com.raulshma.lenscast.streaming.whep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reading of a libwebrtc stats report ([WhepStatsReportParser]): which
 * row describes the viewer's video, and what it says. The rows below are
 * built the way the JNI glue builds them — a row's type beside its members,
 * member values boxed as `Integer` (32-bit counters), `Long` (64-bit ones),
 * `Double` or `String`, absent members simply missing — so the parser is
 * pinned against the library's real shapes without loading libwebrtc.
 */
class WhepStatsReportParserTest {

    /** An `outbound-rtp` row: the members the collector fills for a sending stream. */
    private fun outbound(
        id: String,
        ssrc: Int = 111,
        kind: String? = "video",
        codecId: String? = "C_vp8",
        bytesSent: Any? = 1_062_500L,
        framesEncoded: Any? = 240,
        width: Any? = 600,
        height: Any? = 800,
    ) = WhepStatRow(
        type = "outbound-rtp",
        id = id,
        members = buildMap {
            put("ssrc", ssrc)
            kind?.let { put("kind", it) }
            codecId?.let { put("codecId", it) }
            put("transportId", "T01")
            put("packetsSent", 741)
            bytesSent?.let { put("bytesSent", it) }
            put("headerBytesSent", 11_856L)
            put("retransmittedBytesSent", 0L)
            framesEncoded?.let { put("framesEncoded", it) }
            width?.let { put("frameWidth", it) }
            height?.let { put("frameHeight", it) }
            put("framesSent", 240)
        },
    )

    private fun codec(id: String, mimeType: String, payloadType: Int = 96) = WhepStatRow(
        type = "codec",
        id = id,
        members = mapOf("payloadType" to payloadType, "mimeType" to mimeType, "clockRate" to 90_000, "channels" to 2),
    )

    /** The rows a connected send-only session's report carries besides its two sends. */
    private fun otherRows() = listOf(
        WhepStatRow("remote-inbound-rtp", "T01_remote_inbound_111", mapOf("ssrc" to 111, "kind" to "video", "codecId" to "C_vp8", "bytesReceived" to 1_060_000L, "fractionLost" to 0.0)),
        WhepStatRow("transport", "T01", mapOf("dtlsState" to "connected", "bytesSent" to 1_300_000L)),
        WhepStatRow("candidate-pair", "CP01", mapOf("state" to "succeeded", "bytesSent" to 1_300_000L, "currentRoundTripTime" to 0.002)),
        WhepStatRow("local-candidate", "LC01", mapOf("candidateType" to "host", "bytesSent" to 1_300_000L)),
        WhepStatRow("remote-candidate", "RC01", mapOf("candidateType" to "host")),
        WhepStatRow("peer-connection", "P", mapOf("dataChannelsOpened" to 0)),
    )

    /** Everything a connected session with audio reports: codecs, both sends, the ICE/transport rows. */
    private fun fullReport() = listOf(
        codec("C_vp8", "video/VP8"),
        codec("C_opus", "audio/opus", payloadType = 111),
        outbound("T01_outbound_222", ssrc = 222, kind = "audio", codecId = "C_opus", bytesSent = 80_000L, framesEncoded = 100, width = null, height = null),
    ) + otherRows() + outbound("T01_outbound_111") // the viewer's video: the only row the parser must answer with

    @Test
    fun `picks the video outbound row out of a full report`() {
        val video = WhepStatsReportParser.videoOutbound(fullReport())!!

        assertEquals(1_062_500L, video.bytesSent)
        assertEquals(240L, video.framesEncoded)
        assertEquals("vp8", video.codec)
        assertEquals(600, video.width)
        assertEquals(800, video.height)
    }

    @Test
    fun `the audio send is never mistaken for the video`() {
        val rows = listOf(
            codec("C_opus", "audio/opus"),
            outbound("T01_outbound_222", ssrc = 222, kind = "audio", codecId = "C_opus", bytesSent = 9_000_000L, framesEncoded = 9_000, width = null, height = null),
            outbound("T01_outbound_111", bytesSent = 1_062_500L, framesEncoded = 240),
        )
        // The audio stream sent far more bytes — `kind` is what selects, not size.
        assertEquals(1_062_500L, WhepStatsReportParser.videoOutbound(rows)!!.bytesSent)
    }

    @Test
    fun `the codec is the mime type's lower-case sub-type`() {
        mapOf(
            "video/VP8" to "vp8",
            "video/rtx" to "rtx",
            "video/H264" to "h264",
            "video/VP9" to "vp9",
            "video/AV1" to "av1",
            "video/red" to "red",
        ).forEach { (mime, expected) -> assertEquals(mime, expected, WhepStatsReportParser.codecWireName(mime)) }

        val rows = listOf(codec("C_h264", "video/H264"), outbound("T01_outbound_111", codecId = "C_h264"))
        assertEquals("h264", WhepStatsReportParser.videoOutbound(rows)!!.codec)
    }

    @Test
    fun `an unresolved codec id leaves the codec unknown`() {
        // No codecId member (payload type not known yet)…
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", codecId = null)))!!.codec)
        // …a codecId naming a row the report does not carry…
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", codecId = "C_missing")))!!.codec)
        // …or a codec row without a mime type.
        val muteCodec = WhepStatRow("codec", "C_vp8", mapOf("payloadType" to 96))
        assertNull(WhepStatsReportParser.videoOutbound(listOf(muteCodec, outbound("T01_outbound_111")))!!.codec)
        // The counters still count in every case.
        assertEquals(1_062_500L, WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", codecId = null)))!!.bytesSent)
    }

    @Test
    fun `absent or zero frame size stays unknown`() {
        // libwebrtc omits frameWidth/frameHeight until frames were encoded.
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", width = null, height = null, framesEncoded = 0)))!!.width)
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", width = null, height = null, framesEncoded = 0)))!!.height)
        // A zero is "none" too, not a 0x0 geometry.
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", width = 0, height = 0)))!!.width)
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", width = 0, height = 0)))!!.height)
    }

    @Test
    fun `a report without a video send answers null`() {
        // Nothing at all (the collector emits outbound rows only once connected)…
        assertNull(WhepStatsReportParser.videoOutbound(emptyList()))
        // …only non-RTP rows…
        assertNull(WhepStatsReportParser.videoOutbound(otherRows()))
        // …audio only…
        assertNull(
            WhepStatsReportParser.videoOutbound(
                listOf(codec("C_opus", "audio/opus"), outbound("T01_outbound_222", kind = "audio", codecId = "C_opus")),
            ),
        )
        // …or a video row without the counters to difference (the receive-side shape).
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", bytesSent = null, framesEncoded = null))))
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", framesEncoded = null))))
    }

    @Test
    fun `a libwebrtc without the kind member is recognized by its codec`() {
        // `kind` joined the RTP stream stats later than the rest; on a build
        // without it the codec's mime type says which stream is the video.
        val rows = listOf(
            codec("C_opus", "audio/opus", payloadType = 111),
            codec("C_vp8", "video/VP8"),
            outbound("T01_outbound_222", ssrc = 222, kind = null, codecId = "C_opus", bytesSent = 80_000L, framesEncoded = 100, width = null, height = null),
            outbound("T01_outbound_111", kind = null, codecId = "C_vp8"),
        )
        assertEquals(1_062_500L, WhepStatsReportParser.videoOutbound(rows)!!.bytesSent)
        assertEquals("vp8", WhepStatsReportParser.videoOutbound(rows)!!.codec)
    }

    @Test
    fun `numeric members are read whatever their boxed type`() {
        // 32-bit members arrive as Integer, 64-bit ones as Long, doubles as
        // Double — a build that widens or narrows a member must still read.
        assertEquals(425_000L, WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", bytesSent = 425_000, framesEncoded = 48)))!!.bytesSent)
        assertEquals(425_000L, WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", bytesSent = 425_000L, framesEncoded = 48)))!!.bytesSent)
        assertEquals(425_000L, WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", bytesSent = 425_000.0, framesEncoded = 48)))!!.bytesSent)
        assertEquals(48L, WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", framesEncoded = 48L)))!!.framesEncoded)
        assertEquals(1_000_000_000_000L, WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", bytesSent = 1_000_000_000_000L)))!!.bytesSent)
    }

    @Test
    fun `an unusable counter makes the row no observation`() {
        // Not a number…
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", bytesSent = "1062500"))))
        // …not finite…
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", framesEncoded = Double.NaN))))
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", bytesSent = Double.POSITIVE_INFINITY))))
        // …or negative (cumulative totals never are).
        assertNull(WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", bytesSent = -5L))))
        // A frame size that is unusable is simply unknown, not fatal.
        val video = WhepStatsReportParser.videoOutbound(listOf(outbound("T01_outbound_111", width = "600", height = -1)))!!
        assertNull(video.width)
        assertNull(video.height)
        assertEquals(1_062_500L, video.bytesSent)
    }

    @Test
    fun `with more than one video send the row that sent most bytes answers`() {
        // Not something the endpoint produces (one track, one encoding) —
        // but if a build ever reports a second video stream the answer stays
        // deterministic: the biggest, ties by id.
        val rows = listOf(
            outbound("T01_outbound_333", ssrc = 333, bytesSent = 900_000L, framesEncoded = 240, width = 300, height = 400),
            outbound("T01_outbound_111", ssrc = 111),
        )
        val video = WhepStatsReportParser.videoOutbound(rows)!!
        assertEquals(1_062_500L, video.bytesSent)
        assertEquals(600, video.width)

        val tied = listOf(
            outbound("T01_outbound_333", ssrc = 333, bytesSent = 900_000L, width = 300),
            outbound("T01_outbound_111", ssrc = 111, bytesSent = 900_000L),
        )
        assertEquals(300, WhepStatsReportParser.videoOutbound(tied)!!.width)
    }
}
