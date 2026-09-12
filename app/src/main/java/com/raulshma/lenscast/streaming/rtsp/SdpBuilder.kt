package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.core.Base64Codec

/**
 * Pure SDP generation for the RTSP DESCRIBE response — the buildSdp body,
 * byte-identical. The server supplies the live encoder state (the active
 * codec's parameter sets, AAC AudioSpecificConfig) plus the connection
 * details; everything else — the fmtp line, the AAC config hex with its
 * [AacFormat] fallback, the line order — is owned here so it is JVM-tested.
 *
 * [controlPath] is the aggregate `a=control:` target: the main stream's path
 * by default, the sub-stream's (`sub`) for the /sub DESCRIBE — one media
 * section per SDP either way, so /sub is a separate RTSP URL, not a second
 * MediaDescription inside the main SDP. [addressType] (`IP4`/`IP6`) selects
 * the origin/connection address family for the advertised host.
 */
object SdpBuilder {

    fun build(
        sessionId: String,
        ip: String,
        videoBitrate: Int,
        audioEnabled: Boolean,
        audioSampleRateHz: Int,
        audioChannelCount: Int,
        sps: ByteArray?,
        pps: ByteArray?,
        audioSpecificConfig: ByteArray?,
        codec: RtspVideoCodec = RtspVideoCodec.H264,
        vps: ByteArray? = null,
        controlPath: String = RtspUriPolicy.DEFAULT_STREAM_PATH,
        addressType: String = "IP4",
    ): String {
        val spsBase64 = sps?.let { Base64Codec.encode(it) }
        val ppsBase64 = pps?.let { Base64Codec.encode(it) }
        val videoLines = when (codec) {
            RtspVideoCodec.H264 -> listOf(
                "a=rtpmap:96 H264/90000",
                "a=fmtp:96 " + H264NalParser.buildFmtp(
                    H264NalParser.profileLevelId(sps),
                    spsBase64,
                    ppsBase64
                ),
            )
            // RFC 7798 §7.1: the fmtp carries the base64 sprop triple. When
            // the parameter sets are not learned yet the whole line is
            // omitted — there is no other H.265 fmtp attribute to carry
            // (H.264 keeps its line and drops only its sprop).
            RtspVideoCodec.H265 -> buildList {
                add("a=rtpmap:96 H265/90000")
                H265NalParser.buildFmtp(
                    vps?.let { Base64Codec.encode(it) },
                    spsBase64,
                    ppsBase64,
                )?.let { add("a=fmtp:96 $it") }
            }
        }
        val connectionAddress = RtspAddressing.connectionAddress(addressType)

        return buildString {
            appendLine("v=0")
            appendLine("o=- $sessionId 1 IN $addressType $ip")
            appendLine("s=LensCast Camera Stream")
            appendLine("t=0 0")
            appendLine("a=tool:LensCast")
            appendLine("a=type:broadcast")
            appendLine("a=control:*")
            appendLine("a=range:npt=0-")
            appendLine("m=video 0 RTP/AVP 96")
            appendLine("c=IN $addressType $connectionAddress")
            appendLine("b=AS:${videoBitrate / 1000}")
            for (line in videoLines) {
                appendLine(line)
            }
            appendLine("a=control:$controlPath")

            if (audioEnabled) {
                // No live ASC yet (DESCRIBE raced the encoder start): derive the
                // fallback from the actual rate/channel count so a mono default
                // never advertises the old hardcoded stereo bytes.
                val configHex = if (audioSpecificConfig != null && audioSpecificConfig.size >= 2) {
                    AacFormat.bytesToHex(audioSpecificConfig.copyOfRange(0, 2))
                } else {
                    AacFormat.fallbackAscHex(audioSampleRateHz, audioChannelCount)
                }

                appendLine("m=audio 0 RTP/AVP 97")
                appendLine("c=IN $addressType $connectionAddress")
                appendLine("a=rtpmap:97 mpeg4-generic/$audioSampleRateHz/$audioChannelCount")
                appendLine("a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=$configHex")
                appendLine("a=control:trackID=1")
            }
        }
    }
}
