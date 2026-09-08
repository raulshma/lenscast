package com.raulshma.lenscast.streaming.hls

import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * HLS segment ring fed by the RTSP encoder path: H.264 + AAC in, TS segments out.
 * The production [HlsVideoSink]/[HlsSegmentSource]: [RtspServer] feeds through
 * the sink seam and [MediaResponder] serves through the source seam, both
 * injected at construction; [StreamingManager] owns the lifecycle (reset on
 * start/stop) via [setEnabled].
 */
object HlsManager : HlsVideoSink, HlsSegmentSource {
    private const val TAG = "HlsManager"
    private const val MAX_SEGMENTS = 8
    // ~48 AUs at 24fps ≈ 2s per segment.
    private const val AUS_PER_SEGMENT = 48

    private val lock = Any()
    private val segments = ArrayDeque<Pair<Long, ByteArray>>()
    private val sequence = AtomicLong(0)
    private val pending = mutableListOf<ByteArray>()
    @Volatile private var enabled = false
    @Volatile private var pts90k = 0L
    @Volatile private var audioPts90k = 0L

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) reset()
    }

    fun reset() {
        synchronized(lock) {
            segments.clear()
            pending.clear()
            sequence.set(0)
            pts90k = 0
            audioPts90k = 0
        }
        TsPacketizer.reset()
    }

    override fun feedAudio(aacData: ByteArray) {
        if (!enabled || aacData.isEmpty()) return
        try {
            val ts = TsPacketizer.audioFrameToTs(aacData, audioPts90k)
            audioPts90k += 1920 // 1024 samples @48kHz → 90kHz clock
            synchronized(lock) {
                // Audio rides the current open segment so A/V stay muxed.
                pending.add(ts)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HLS audio feed failed", e)
        }
    }

    override fun feedVideo(nalus: List<com.raulshma.lenscast.streaming.rtsp.H264Encoder.EncodedNalUnit>) {
        if (!enabled || nalus.isEmpty()) return
        try {
            val raw = nalus.map { it.data }
            val ts = TsPacketizer.videoAuToTs(raw, pts90k)
            pts90k += 3750 // 90kHz / 24fps
            synchronized(lock) {
                pending.add(ts)
                if (pending.size >= AUS_PER_SEGMENT) {
                    val seq = sequence.incrementAndGet()
                    val combined = pending.fold(ByteArray(0)) { acc, b -> acc + b }
                    segments.addLast(seq to combined)
                    while (segments.size > MAX_SEGMENTS) segments.removeFirst()
                    pending.clear()
                    Log.d(TAG, "HLS segment $seq ready (${combined.size}B, window=${segments.size})")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HLS feed failed", e)
        }
    }

    override fun playlist(): String = synchronized(lock) {
        HlsPlaylist.build(segments.map { HlsPlaylist.segmentName(it.first) }, sequence.get())
    }

    override fun segment(name: String): ByteArray? = synchronized(lock) {
        segments.firstOrNull { HlsPlaylist.segmentName(it.first) == name }?.second
    }

    override fun hasSegments(): Boolean = synchronized(lock) { segments.isNotEmpty() }
}
