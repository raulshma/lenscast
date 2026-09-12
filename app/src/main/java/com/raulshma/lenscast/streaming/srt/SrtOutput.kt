package com.raulshma.lenscast.streaming.srt

import android.util.Log
import com.raulshma.lenscast.streaming.EncodedSource
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The SRT push output — [com.raulshma.lenscast.streaming.rtmp.RtmpOutput]'s
 * SRT twin: the retained URL (settings land even while the output is stopped,
 * so the next start picks them up), the enabled gate, the start-time
 * validation ladder (a usable `srt://` URL parsed by the pure [SrtUrl], plus
 * the H.264 video codec — MPEG-TS carries HEVC, but the shared encode path's
 * TS muxing and every consumer here are H.264-pinned, so a push under H.265
 * is refused with a readable error), and the publisher lifecycle with
 * restart-on-URL-change. The URL is a write-only credential over the Web API
 * (userinfo and streamid never echo — [SrtUrl.redacted] is the only
 * rendering that reaches a log). The manager keeps the public surface and
 * the hub fan-out; everything "the SRT output" means sits behind this narrow
 * class, JVM-tested behind the [SrtPublisherHandle] seam.
 */
internal class SrtOutput(
    private val source: EncodedSource,
    /** The status mirror after every transition, so the owner's flows follow the output. */
    private val onStatusChanged: (SrtStatus) -> Unit,
    private val publisherFactory: (url: SrtUrl, onStatus: (SrtStatus) -> Unit) -> SrtPublisherHandle,
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
    private var publisher: SrtPublisherHandle? = null

    @Volatile
    private var statusValue: SrtStatus = SrtStatus.Idle

    // ── flags & state reads ──

    /** Switches the output on/off; true when the flag actually changed. Disabling stops a live output. */
    fun setEnabled(on: Boolean): Boolean {
        val changed = enabled.getAndSet(on) != on
        if (changed && !on) stop()
        return changed
    }

    fun isEnabled(): Boolean = enabled.get()

    /** True from [start] until [stop] — the owner's "SRT output live" answer. */
    fun isActive(): Boolean = active.get()

    /** The configured push URL, retained even while stopped. */
    fun configuredUrl(): String = url

    fun status(): SrtStatus = statusValue

    /** The live wire stats (RTT, loss counts) — the status surfaces' read seam. */
    fun stats(): SrtStats = publisher?.stats() ?: SrtStats()

    // ── lifecycle ──

    /**
     * Starts the push (no-op when already live). The validation ladder runs
     * synchronously — a disabled output, an unusable URL, or the H.265 codec
     * refusal each answer [StartResult.Rejected] with the readable reason
     * and leave the status line carrying it; only a passing ladder starts
     * the publisher (whose handshake is asynchronous and auto-reconnecting).
     */
    fun start(): StartResult {
        if (active.get()) return StartResult.Started
        if (!enabled.get()) {
            return reject("SRT push is disabled")
        }
        val parsed = SrtUrl.parse(url)
            ?: return reject("Invalid SRT URL — expected srt://host:port?streamid=… (port defaults to 9710)")
        if (source.videoCodec != RtspVideoCodec.H264) {
            return reject(
                "SRT push requires the H.264 video codec — the encoded path is currently H.265; " +
                    "switch the RTSP video codec to H.264 in streaming settings",
            )
        }
        active.set(true)
        val handle = publisherFactory(parsed, ::publishStatus)
        publisher = handle
        handle.start()
        Log.i(TAG, "SRT output started toward ${parsed.redacted()}")
        return StartResult.Started
    }

    /** Stops the publisher (shutdown notice + socket release) — no-op when already stopped. */
    fun stop() {
        if (!active.getAndSet(false)) return
        publisher?.stop()
        publisher = null
        publishStatus(SrtStatus.Idle)
        Log.i(TAG, "SRT output stopped")
    }

    /**
     * A new push URL lands immediately; a live output restarts on it (the
     * publisher's handshake parameters are per-attempt). A same-URL call is
     * a no-op, so a settings re-emission never churns a live output.
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
        publishStatus(SrtStatus.Error(reason))
        Log.w(TAG, "SRT start refused: $reason")
        return StartResult.Rejected(reason)
    }

    private fun publishStatus(status: SrtStatus) {
        statusValue = status
        onStatusChanged(status)
    }

    private companion object {
        private const val TAG = "SrtOutput"
    }
}
