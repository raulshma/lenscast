package com.raulshma.lenscast.streaming.rtsp

/**
 * Pure RTSP session-protocol decisions, pulled out of [RtspServer]'s socket
 * class: the SETUP transport-header verdict, the CSeq monotonicity ladder,
 * the Session-header parse, PLAY's RTP-Info assembly, the RTCP sender report
 * bytes, and the `$`-interleaved framing. Android-free so every verdict is
 * JVM-tested byte-for-byte; [RtspServer] keeps the sockets and the session
 * state machine and only applies what this module decides.
 */
object RtspSessionProtocol {

    /** The `$` magic byte opening an interleaved frame on the RTSP connection. */
    const val INTERLEAVED_FRAME_MAGIC = 0x24

    // ── SETUP: transport header ──

    /** One TCP-interleaved channel pair as negotiated in the SETUP Transport header. */
    data class InterleavedChannels(val rtp: Int, val rtcp: Int)

    /** The SETUP Transport-header verdict: reject with 461, or accept with optional explicit channels. */
    sealed interface TransportVerdict {
        /** Not TCP-interleaved — the 461 Unsupported Transport case. */
        object Unsupported : TransportVerdict

        /**
         * TCP interleaved accepted. [channels] is null when the client sent no
         * explicit `interleaved=` pair — the caller keeps its defaults.
         */
        data class Interleaved(val channels: InterleavedChannels?) : TransportVerdict
    }

    /**
     * The SETUP Transport-header verdict. Only `RTP/AVP/TCP` with interleaved
     * delivery is supported; a matching header without explicit channel
     * numbers still accepts and keeps the caller's default channels.
     */
    fun parseTransportHeader(transport: String?): TransportVerdict {
        val header = transport ?: ""
        if (!header.contains("RTP/AVP/TCP", ignoreCase = true) ||
            !header.contains("interleaved", ignoreCase = true)
        ) {
            return TransportVerdict.Unsupported
        }
        val match = Regex("interleaved=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).find(header)
            ?: return TransportVerdict.Interleaved(channels = null)
        return TransportVerdict.Interleaved(
            InterleavedChannels(
                rtp = match.groupValues[1].toInt(),
                rtcp = match.groupValues[2].toInt(),
            )
        )
    }

    // ── CSeq monotonicity ──

    /** The per-request CSeq verdict; [CSeqVerdict.Reject.cseq] is what the 400 response echoes. */
    sealed interface CSeqVerdict {
        /** Request accepted; the CSeq also becomes the new monotonic floor. */
        data class Ok(val cseq: Int) : CSeqVerdict

        /** Reject with 400 Bad Request; [reason] names the violated rule. */
        data class Reject(val cseq: Int, val reason: String) : CSeqVerdict
    }

    /**
     * The CSeq ladder: a missing/garbage/negative header rejects with a
     * response CSeq of 0; a header that does not strictly increase over
     * [lastCSeq] (once one has been seen) rejects with its own value.
     */
    fun cseqVerdict(lastCSeq: Int, cseqHeader: String?): CSeqVerdict {
        val parsed = cseqHeader?.toIntOrNull()
        if (parsed == null || parsed < 0) {
            return CSeqVerdict.Reject(cseq = 0, reason = "missing, unparsable, or negative CSeq")
        }
        if (lastCSeq >= 0 && parsed <= lastCSeq) {
            return CSeqVerdict.Reject(cseq = parsed, reason = "CSeq does not strictly increase")
        }
        return CSeqVerdict.Ok(parsed)
    }

    // ── Session header ──

    /**
     * The session id as the `Session` header carries it: everything before
     * the first `;` (parameters like `timeout=60`), trimmed. Null when the
     * header is absent.
     */
    fun parseSessionHeader(sessionHeader: String?): String? =
        sessionHeader?.substringBefore(';')?.trim()

    // ── PLAY: RTP-Info ──

    /** One stream's progress as the RTP-Info header reports it. */
    data class RtpInfoEntry(val url: String, val seq: Int, val rtpTime: Long) {
        override fun toString(): String = "url=$url;seq=$seq;rtptime=$rtpTime"
    }

    /** The comma-joined RTP-Info value; the audio entry is omitted when null. */
    fun buildRtpInfo(video: RtpInfoEntry, audio: RtpInfoEntry?): String =
        listOfNotNull(video, audio).joinToString(",")

    // ── RTCP sender report ──

    private const val NTP_EPOCH_OFFSET_MS = 2_208_988_800_000L // 1900-01-01 vs 1970-01-01

    /**
     * The RFC 3550 sender report (no report blocks) for one RTP stream:
     * a fixed 28-byte packet carrying the SSRC, the wall clock as NTP,
     * the last RTP timestamp, and the packet/octet counters — byte-identical
     * to what the server assembled inline before.
     */
    fun senderReport(
        ssrc: Int,
        rtpTimestamp: Long,
        packets: Long,
        octets: Long,
        wallClockMs: Long,
    ): ByteArray {
        val ntpTimeMs = wallClockMs + NTP_EPOCH_OFFSET_MS
        val ntpSeconds = ntpTimeMs / 1000
        val ntpFraction = ((ntpTimeMs % 1000) shl 32) / 1000

        val packet = ByteArray(28)
        packet[0] = 0x80.toByte() // V=2, P=0, RC=0
        packet[1] = 200.toByte()  // PT=SR
        packet[2] = 0
        packet[3] = 6             // length in 32-bit words minus one
        writeInt(packet, 4, ssrc)
        writeInt(packet, 8, ntpSeconds.toInt())
        writeInt(packet, 12, ntpFraction.toInt())
        writeInt(packet, 16, (rtpTimestamp and 0xFFFFFFFFL).toInt())
        writeInt(packet, 20, (packets and 0xFFFFFFFFL).toInt())
        writeInt(packet, 24, (octets and 0xFFFFFFFFL).toInt())
        return packet
    }

    private fun writeInt(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = (value ushr 24).toByte()
        dest[offset + 1] = (value ushr 16).toByte()
        dest[offset + 2] = (value ushr 8).toByte()
        dest[offset + 3] = value.toByte()
    }

    // ── Interleaved framing ──

    /**
     * One `$`-framed interleaved packet as a single array, so the writer can
     * deliver header + payload in one `write()` call — writing the 4 header
     * bytes individually produced several tiny TCP segments per RTP packet
     * under TCP_NODELAY.
     */
    fun interleavedFrame(channel: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(4 + payload.size)
        frame[0] = INTERLEAVED_FRAME_MAGIC.toByte()
        frame[1] = channel.toByte()
        frame[2] = ((payload.size shr 8) and 0xFF).toByte()
        frame[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 4, payload.size)
        return frame
    }
}
