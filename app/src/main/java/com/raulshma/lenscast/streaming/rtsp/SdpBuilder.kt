package com.raulshma.lenscast.streaming.rtsp

import java.util.Base64

/**
 * Pure SDP generation for the RTSP DESCRIBE response — the buildSdp body,
 * byte-identical. The server supplies the live encoder state (SPS/PPS, AAC
 * AudioSpecificConfig) plus the connection details; everything else — the
 * fmtp line, the AAC config hex with its [AacFormat] fallback, the line
 * order — is owned here so it is JVM-tested.
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
    ): String {
        val spsBase64 = sps?.let { bytesToBase64(it) }
        val ppsBase64 = pps?.let { bytesToBase64(it) }
        val fmtp = H264NalParser.buildFmtp(
            H264NalParser.profileLevelId(sps),
            spsBase64,
            ppsBase64
        )

        return buildString {
            appendLine("v=0")
            appendLine("o=- $sessionId 1 IN IP4 $ip")
            appendLine("s=LensCast Camera Stream")
            appendLine("t=0 0")
            appendLine("a=tool:LensCast")
            appendLine("a=type:broadcast")
            appendLine("a=control:*")
            appendLine("a=range:npt=0-")
            appendLine("m=video 0 RTP/AVP 96")
            appendLine("c=IN IP4 0.0.0.0")
            appendLine("b=AS:${videoBitrate / 1000}")
            appendLine("a=rtpmap:96 H264/90000")
            appendLine("a=fmtp:96 $fmtp")
            appendLine("a=control:${RtspUriPolicy.DEFAULT_STREAM_PATH}")

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
                appendLine("c=IN IP4 0.0.0.0")
                appendLine("a=rtpmap:97 mpeg4-generic/$audioSampleRateHz/$audioChannelCount")
                appendLine("a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=$configHex")
                appendLine("a=control:trackID=1")
            }
        }
    }

    private fun bytesToBase64(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
    }
}
