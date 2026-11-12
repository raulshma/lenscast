package com.raulshma.lenscast.streaming.hls

import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit

/**
 * The HLS seams behind the transport and encoder paths: the RTSP server feeds
 * encoded AUs through [HlsVideoSink] and the media responder serves segments
 * through [HlsSegmentSource]. [HlsManager] implements both in production;
 * JVM tests substitute fakes. The seams keep the singleton out of
 * RtspServer/MediaResponder call sites.
 */
interface HlsVideoSink {
    fun feedVideo(nalus: List<EncodedNalUnit>)

    fun feedAudio(aacData: ByteArray)

    /**
     * The AAC encoder's format, so the TS muxer can build ADTS headers (raw
     * AAC is not valid in TS). Called when the shared encode (re)attaches
     * audio; sinks that do not mux TS ignore it.
     */
    fun setAudioFormat(sampleRateHz: Int, channelCount: Int) {}
}

interface HlsSegmentSource {
    fun hasSegments(): Boolean

    fun playlist(): String

    fun segment(name: String): ByteArray?

    /**
     * Registers demand from a request that may not reach a playlist/segment
     * serve — a cold-ring playlist poll answers 503 without serving, and
     * without this signal the shared encoders would never restart (the ring
     * stays empty because the encoders are off because the ring is empty).
     */
    fun noteRequest() {}
}
