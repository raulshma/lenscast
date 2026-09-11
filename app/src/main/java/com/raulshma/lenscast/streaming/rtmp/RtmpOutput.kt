package com.raulshma.lenscast.streaming.rtmp

import android.util.Log
import com.raulshma.lenscast.streaming.EncodedSource
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The RTMP push output, pulled out of [com.raulshma.lenscast.streaming.StreamingManager]
 * — [com.raulshma.lenscast.streaming.RtspOutput]'s twin for push: the retained
 * URL (settings land even while the output is stopped, so the next start picks
 * them up), the enabled gate, the start-time validation ladder (usable URL,
 * H.264 video codec — H.265 has no standard RTMP mapping, so a push under
 * H.265 is refused with a readable error instead of publishing garbage), and
 * the publisher lifecycle with restart-on-URL-change. The manager keeps the
 * public surface and the fan-out; everything "the RTMP output" means sits
 * behind this narrow class, JVM-tested behind the [RtmpPublisherHandle] seam
 * with a fake publisher and a fake [EncodedSource].
 */
internal class RtmpOutput(
    private val source: EncodedSource,
    /** The status mirror after every transition, so the owner's flows follow the output. */
    private val onStatusChanged: (RtmpStatus) -> Unit,
    private val publisherFactory: (url: RtmpUrl, onStatus: (RtmpStatus) -> Unit) -> RtmpPublisherHandle,
) {

    /** The output's start verdict: Started, or Rejected with the readable reason. */
    sealed class StartResult {
        data object Started : StartResult()
        data class Rejected(val reason: String) : StartResult()
    }

    /** The output is switched on — start() while disabled refuses. */
    private val enabled = AtomicBoolean(false)

    /** The output is live: started and not yet stopped, even while connecting/reconnecting. */
    private val active = AtomicBoolean(false)

    @Volatile
    private var url: String = ""

    @Volatile
    private var publisher: RtmpPublisherHandle? = null

    @Volatile
    private var statusValue: RtmpStatus = RtmpStatus.Idle

    // ── flags & state reads ──

    /** Switches the output on/off; true when the flag actually changed. Disabling stops a live output. */
    fun setEnabled(on: Boolean): Boolean {
        val changed = enabled.getAndSet(on) != on
        if (changed && !on) stop()
        return changed
    }

    fun isEnabled(): Boolean = enabled.get()

    /** True from [start] until [stop] — the owner's "RTMP output live" answer. */
    fun isActive(): Boolean = active.get()

    /** The configured push URL, retained even while stopped. */
    fun configuredUrl(): String = url

    fun status(): RtmpStatus = statusValue

    // ── lifecycle ──

    /**
     * Starts the push (no-op when already live). The validation ladder runs
     * synchronously — a disabled output, an unusable URL, or the H.265 codec
     * refusal each answer [StartResult.Rejected] with the readable reason and
     * leave the status line carrying it; only a passing ladder starts the
     * publisher (whose connect is asynchronous and auto-reconnecting).
     */
    fun start(): StartResult {
        if (active.get()) return StartResult.Started
        if (!enabled.get()) {
            return reject("RTMP push is disabled")
        }
        val parsed = RtmpUrl.parse(url)
            ?: return reject("Invalid RTMP URL — expected rtmp://host/app/stream-key (or rtmps://…)")
        if (source.videoCodec != RtspVideoCodec.H264) {
            return reject(
                "RTMP push requires the H.264 video codec — the encoded path is currently H.265; " +
                    "switch the RTSP video codec to H.264 in streaming settings",
            )
        }
        active.set(true)
        val handle = publisherFactory(parsed, ::publishStatus)
        publisher = handle
        handle.start()
        Log.i(TAG, "RTMP output started toward ${parsed.hostAndPort}/${parsed.app}")
        return StartResult.Started
    }

    /** Stops the publisher (clean close) — no-op when already stopped. */
    fun stop() {
        if (!active.getAndSet(false)) return
        publisher?.stop()
        publisher = null
        publishStatus(RtmpStatus.Idle)
        Log.i(TAG, "RTMP output stopped")
    }

    /**
     * A new push URL lands immediately; a live output restarts on it (the
     * publisher's connect parameters are per-attempt). A same-URL call is a
     * no-op, so a settings re-emission never churns a live output.
     */
    fun setUrl(newUrl: String) {
        val trimmed = newUrl.trim()
        if (trimmed == url) return
        url = trimmed
        if (enabled.get() && active.get()) {
            stop()
            start()
        }
    }

    // ── the encoded-stream hub's sink ──

    /** One encoded H.264 access unit from the encoded-stream hub. No-op while stopped. */
    fun feedEncodedVideo(nalUnits: List<EncodedNalUnit>) {
        publisher?.feedVideo(nalUnits)
    }

    /** One AAC access unit from the encoded-stream hub. No-op while stopped. */
    fun feedEncodedAudio(aacData: ByteArray) {
        publisher?.feedAudio(aacData)
    }

    // ── internals ──

    private fun reject(reason: String): StartResult.Rejected {
        publishStatus(RtmpStatus.Error(reason))
        Log.w(TAG, "RTMP start refused: $reason")
        return StartResult.Rejected(reason)
    }

    private fun publishStatus(status: RtmpStatus) {
        statusValue = status
        onStatusChanged(status)
    }

    private companion object {
        private const val TAG = "RtmpOutput"
    }
}
