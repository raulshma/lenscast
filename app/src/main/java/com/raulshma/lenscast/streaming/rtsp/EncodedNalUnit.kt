package com.raulshma.lenscast.streaming.rtsp

/**
 * One encoded NAL unit on the wire — the codec-neutral access-unit element
 * every video sink (RTSP packetizers, the HLS muxer, the WS/WebCodecs path)
 * consumes, for H.264 and H.265 alike. [data] is header-stripped (no Annex-B
 * start code); [isKeyFrame] is the frame's keyframe verdict, so a sink can
 * gate mid-GOP joins without re-parsing NAL types.
 */
data class EncodedNalUnit(val data: ByteArray, val isKeyFrame: Boolean)
