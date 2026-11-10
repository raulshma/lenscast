package com.raulshma.lenscast.streaming.srt

import com.raulshma.lenscast.streaming.EncodedSource
import com.raulshma.lenscast.streaming.PushConnectConfig
import com.raulshma.lenscast.streaming.PushOutput
import com.raulshma.lenscast.streaming.PushPhase
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit

/**
 * SRT's retained push config: just the push URL — userinfo and streamid
 * travel inside it, and the parsed [SrtUrl] carries everything else the
 * publisher connects with.
 */
internal data class SrtPushConfig(
    override val url: String = "",
) : PushConnectConfig

/**
 * The SRT push output — the SRT configuration of the shared deep
 * [PushOutput] module (the RtspOutput-pattern collapse of the push twins):
 * the retained URL (settings land even while the output is stopped, so the
 * next start picks them up), the enabled gate, the start-time validation
 * ladder (a usable `srt://` URL parsed by the pure [SrtUrl], plus the H.264
 * codec gate — MPEG-TS carries HEVC, but the shared encode path's TS muxing
 * and every consumer here are H.264-pinned, so a push under H.265 is
 * refused with a readable error), and the publisher lifecycle with
 * restart-on-URL-change. The URL is a write-only credential over the Web
 * API (userinfo and streamid never echo — [SrtUrl.redacted] is the only
 * rendering that reaches a log, handed to the deep module as its
 * `describeTarget` redaction seam). This file carries only the
 * transport-typed wiring — parse, codec gate, status constructors, the
 * [SrtPublisherHandle] seam, the hub-fed media feed, and the wire-stats
 * read — while every lifecycle decision lives in the deep module. The
 * manager keeps the public surface and the hub fan-out.
 */
internal class SrtOutput(
    /** The encoded-stream hub: the codec gate reads the shared encode path's codec from it. */
    private val source: EncodedSource,
    /** The status mirror after every transition, so the owner's flows follow the output. */
    onStatusChanged: (SrtStatus) -> Unit,
    /** The active mirror after every [isActive] flip, so the owner's derived streaming flow follows the output. */
    onActiveChanged: (Boolean) -> Unit = {},
    /** The publisher handle seam — the transport's factory, unchanged by the collapse. */
    publisherFactory: (url: SrtUrl, onStatus: (SrtStatus) -> Unit) -> SrtPublisherHandle,
    /** The transition epilogue (hub refresh), run by the deep module's manager-driven start/stop. */
    private val settle: () -> Unit,
) {

    private val output = PushOutput(
        label = "SRT",
        tag = TAG,
        onStatusChanged = onStatusChanged,
        onActiveChanged = onActiveChanged,
        settle = settle,
        initialConfig = SrtPushConfig(),
        parse = SrtUrl::parse,
        invalidUrlMessage = "Invalid SRT URL — expected srt://host:port?streamid=… (port defaults to 9710)",
        videoCodec = { source.videoCodec },
        describeTarget = { it.redacted() },
        idleStatus = SrtStatus.Idle,
        errorStatus = { reason -> SrtStatus.Error(reason) },
        publisherFactory = { _, parsed, onStatus -> publisherFactory(parsed, onStatus) },
        startPublisher = { it.start() },
        stopPublisher = { it.stop() },
    )

    /** Switches the output on/off; true when the flag actually changed. Disabling stops a live output. */
    fun setEnabled(on: Boolean): Boolean = output.setEnabled(on)

    fun isEnabled(): Boolean = output.isEnabled()

    /** True from [start] until [stop] — the owner's "SRT output live" answer. */
    fun isActive(): Boolean = output.isActive()

    /** The configured push URL, retained even while stopped. */
    fun configuredUrl(): String = output.retainedConfig().url

    fun status(): SrtStatus = output.status()

    /** The derived [PushPhase] — the status × active pair named ([PushOutput.phase]). */
    fun phase(): PushPhase = output.phase()

    /**
     * Starts the push (no-op when already live); the validation ladder runs
     * synchronously in the deep module and a refusal leaves the status line
     * carrying the readable reason.
     */
    fun start(): PushOutput.StartResult = output.start()

    /** Stops the publisher (shutdown notice + socket release) — no-op when already stopped. */
    fun stop() = output.stop()

    /** The hook-less stop, for the manager's codec-gate path (it writes its own Error and settles itself). */
    fun stopQuietly() = output.stopQuietly()

    /**
     * A new push URL lands immediately; a live output restarts on it through
     * the deep module's config-change path (the publisher's handshake
     * parameters are per-attempt). A same-URL call is a no-op, so a
     * settings re-emission never churns a live output.
     */
    fun setUrl(newUrl: String) {
        output.update { it.copy(url = newUrl.trim()) }
    }

    // ── the encoded-stream hub's sink ──

    /** One encoded H.264 access unit from the encoded-stream hub. No-op while stopped. */
    fun feedEncodedVideo(nalUnits: List<EncodedNalUnit>) {
        output.currentPublisher()?.feedVideo(nalUnits)
    }

    /** One AAC access unit from the encoded-stream hub. No-op while stopped. */
    fun feedEncodedAudio(aacData: ByteArray) {
        output.currentPublisher()?.feedAudio(aacData)
    }

    // ── reads ──

    /** The live wire stats (RTT, loss counts) — the status surfaces' read seam. */
    fun stats(): SrtStats = output.currentPublisher()?.stats() ?: SrtStats()

    private companion object {
        private const val TAG = "SrtOutput"
    }
}
