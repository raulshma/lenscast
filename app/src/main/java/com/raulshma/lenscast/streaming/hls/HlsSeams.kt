package com.raulshma.lenscast.streaming.hls

/**
 * The HLS seams behind the transport and encoder paths: the RTSP server feeds
 * encoded AUs through [HlsVideoSink] and the media responder serves segments
 * through [HlsSegmentSource]. [HlsManager] implements both in production;
 * JVM tests substitute fakes. The seams keep the singleton out of
 * RtspServer/MediaResponder call sites.
 */
interface HlsVideoSink {
    fun feedVideo(nalus: List<com.raulshma.lenscast.streaming.rtsp.H264Encoder.EncodedNalUnit>)

    fun feedAudio(aacData: ByteArray)
}

interface HlsSegmentSource {
    fun hasSegments(): Boolean

    fun playlist(): String

    fun segment(name: String): ByteArray?
}
