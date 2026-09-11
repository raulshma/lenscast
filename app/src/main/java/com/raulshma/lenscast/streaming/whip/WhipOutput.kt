package com.raulshma.lenscast.streaming.whip

import android.util.Log
import com.raulshma.lenscast.streaming.FrameTiming
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The WHIP push output, [com.raulshma.lenscast.streaming.rtmp.RtmpOutput]'s
 * twin: the retained settings (URL, token, STUN server land even while the
 * output is stopped, so the next start picks them up), the enabled gate, the
 * start-time validation ladder (enabled + usable URL — no codec gate, because
 * libwebrtc encodes its own H.264 and is independent of the RTSP codec
 * setting), and the publisher lifecycle with restart-on-config-change. The
 * manager keeps the public surface and the fan-out; everything "the WHIP
 * output" means sits behind this narrow class, JVM-tested behind the
 * [WhipPublisherHandle] seam with a fake publisher (the factory path is
 * device-verified only — libwebrtc is native).
 */
internal class WhipOutput(
    /** The mic-arbitration verdict provider, evaluated at publisher-construction time. */
    private val audioAllowed: () -> Boolean,
    /** The status mirror after every transition, so the owner's flows follow the output. */
    private val onStatusChanged: (WhipStatus) -> Unit,
    private val publisherFactory: (
        url: WhipUrl,
        token: String?,
        stunServer: String?,
        audioAllowed: Boolean,
        onStatus: (WhipStatus) -> Unit,
    ) -> WhipPublisherHandle,
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
    private var token: String = ""

    @Volatile
    private var stunServer: String = com.raulshma.lenscast.core.StreamDefaults.WHIP_STUN_SERVER

    @Volatile
    private var targetFps: Int = com.raulshma.lenscast.core.StreamDefaults.STREAM_FPS

    @Volatile
    private var publisher: WhipPublisherHandle? = null

    @Volatile
    private var statusValue: WhipStatus = WhipStatus.Idle

    // ── flags & state reads ──

    /** Switches the output on/off; true when the flag actually changed. Disabling stops a live output. */
    fun setEnabled(on: Boolean): Boolean {
        val changed = enabled.getAndSet(on) != on
        if (changed && !on) stop()
        return changed
    }

    fun isEnabled(): Boolean = enabled.get()

    /** True from [start] until [stop] — the owner's "WHIP output live" answer. */
    fun isActive(): Boolean = active.get()

    /** The configured endpoint URL, retained even while stopped. */
    fun configuredUrl(): String = url

    fun status(): WhipStatus = statusValue

    // ── lifecycle ──

    /**
     * Starts the push (no-op when already live). The validation ladder runs
     * synchronously — a disabled output or an unusable URL answers
     * [StartResult.Rejected] with the readable reason and leaves the status
     * line carrying it; only a passing ladder starts the publisher (whose
     * offer/answer is asynchronous and auto-reconnecting).
     */
    fun start(): StartResult {
        if (active.get()) return StartResult.Started
        if (!enabled.get()) {
            return reject("WHIP push is disabled")
        }
        val parsed = WhipUrl.parse(url)
            ?: return reject("Invalid WHIP URL — expected http(s)://host[:port]/whip-endpoint")
        active.set(true)
        val handle = publisherFactory(parsed, token.ifBlank { null }, stunServer.ifBlank { null }, audioAllowed(), ::publishStatus)
        publisher = handle
        handle.setFrameRate(targetFps)
        handle.start()
        Log.i(TAG, "WHIP output started toward ${parsed.hostAndPort}${parsed.resourcePath}")
        return StartResult.Started
    }

    /** Stops the publisher (clean DELETE + teardown) — no-op when already stopped. */
    fun stop() {
        if (!active.getAndSet(false)) return
        publisher?.stop()
        publisher = null
        publishStatus(WhipStatus.Idle)
        Log.i(TAG, "WHIP output stopped")
    }

    /**
     * New settings land immediately; a live output restarts on any of them
     * (the publisher's connect parameters are per-attempt). A same-values
     * call is a no-op, so a settings re-emission never churns a live output.
     */
    fun setUrl(newUrl: String) {
        val trimmed = newUrl.trim()
        if (trimmed == url) return
        url = trimmed
        restartIfLive()
    }

    fun setToken(newToken: String) {
        val trimmed = newToken.trim()
        if (trimmed == token) return
        token = trimmed
        restartIfLive()
    }

    fun setStunServer(newServer: String) {
        val trimmed = newServer.trim()
        if (trimmed == stunServer) return
        stunServer = trimmed
        restartIfLive()
    }

    /** The stream's target fps — the throttle's interval moves live, no restart owed. */
    fun setFrameRate(fps: Int) {
        targetFps = FrameTiming.effectiveFps(fps)
        publisher?.setFrameRate(targetFps)
    }

    /**
     * The mic-arbitration verdict moved after the output went live (recording
     * claimed the microphone, or the stream-audio toggle flipped): a live
     * output restarts so the next session is built with the fresh verdict —
     * the audio decision is a publisher-construction input, not a hot-swap.
     * A stopped output only remembers it for the next start.
     */
    fun onMicVerdictChanged() {
        restartIfLive()
    }

    // ── the analysis tap's sink ──

    /** One camera NV21 frame from the manager's push fan-out. No-op while stopped. */
    fun feedVideoFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int) {
        publisher?.feedVideoFrame(nv21, width, height, rotation)
    }

    // ── internals ──

    private fun restartIfLive() {
        if (enabled.get() && active.get()) {
            stop()
            start()
        }
    }

    private fun reject(reason: String): StartResult.Rejected {
        publishStatus(WhipStatus.Error(reason))
        Log.w(TAG, "WHIP start refused: $reason")
        return StartResult.Rejected(reason)
    }

    private fun publishStatus(status: WhipStatus) {
        statusValue = status
        onStatusChanged(status)
    }

    private companion object {
        private const val TAG = "WhipOutput"
    }
}
