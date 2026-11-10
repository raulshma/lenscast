package com.raulshma.lenscast.streaming.rtmp

import com.raulshma.lenscast.streaming.EncodedSource
import com.raulshma.lenscast.streaming.PushConnectConfig
import com.raulshma.lenscast.streaming.PushOutput
import com.raulshma.lenscast.streaming.PushPhase
import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit

/**
 * RTMP's retained push config: just the push URL — the credentials live in
 * it (the stream key is the last path segment) and the parsed [RtmpUrl]
 * carries everything else the publisher connects with.
 */
internal data class RtmpPushConfig(
    override val url: String = "",
) : PushConnectConfig

/**
 * The RTMP push output — the RTMP configuration of the shared deep
 * [PushOutput] module (the RtspOutput-pattern collapse of the push twins):
 * the retained URL (settings land even while the output is stopped, so the
 * next start picks them up), the enabled gate, the start-time validation
 * ladder (usable URL parsed by the pure [RtmpUrl], plus the H.264 codec
 * gate — H.265 has no standard RTMP mapping, so a push under H.265 is
 * refused with a readable error instead of publishing garbage), and the
 * publisher lifecycle with restart-on-URL-change. This file carries only
 * the transport-typed wiring — the parse function, the codec gate over the
 * encoded hub's [EncodedSource], the status constructors, the
 * [RtmpPublisherHandle] seam, and the hub-fed media feed — while every
 * lifecycle decision lives in the deep module. The manager keeps the public
 * surface and the fan-out.
 */
internal class RtmpOutput(
    /** The encoded-stream hub: the codec gate reads the shared encode path's codec from it. */
    private val source: EncodedSource,
    /** The status mirror after every transition, so the owner's flows follow the output. */
    onStatusChanged: (RtmpStatus) -> Unit,
    /** The active mirror after every [isActive] flip, so the owner's derived streaming flow follows the output. */
    onActiveChanged: (Boolean) -> Unit = {},
    /** The publisher handle seam — the transport's factory, unchanged by the collapse. */
    publisherFactory: (url: RtmpUrl, onStatus: (RtmpStatus) -> Unit) -> RtmpPublisherHandle,
    /** The transition epilogue (hub refresh), run by the deep module's manager-driven start/stop. */
    private val settle: () -> Unit,
) {

    private val output = PushOutput(
        label = "RTMP",
        tag = TAG,
        onStatusChanged = onStatusChanged,
        onActiveChanged = onActiveChanged,
        settle = settle,
        initialConfig = RtmpPushConfig(),
        parse = RtmpUrl::parse,
        invalidUrlMessage = "Invalid RTMP URL — expected rtmp://host/app/stream-key (or rtmps://…)",
        videoCodec = { source.videoCodec },
        describeTarget = { "${it.hostAndPort}/${it.app}" },
        idleStatus = RtmpStatus.Idle,
        errorStatus = { reason -> RtmpStatus.Error(reason) },
        publisherFactory = { _, parsed, onStatus -> publisherFactory(parsed, onStatus) },
        startPublisher = { it.start() },
        stopPublisher = { it.stop() },
    )

    /** Switches the output on/off; true when the flag actually changed. Disabling stops a live output. */
    fun setEnabled(on: Boolean): Boolean = output.setEnabled(on)

    fun isEnabled(): Boolean = output.isEnabled()

    /** True from [start] until [stop] — the owner's "RTMP output live" answer. */
    fun isActive(): Boolean = output.isActive()

    /** The configured push URL, retained even while stopped. */
    fun configuredUrl(): String = output.retainedConfig().url

    fun status(): RtmpStatus = output.status()

    /** The derived [PushPhase] — the status × active pair named ([PushOutput.phase]). */
    fun phase(): PushPhase = output.phase()

    /**
     * Starts the push (no-op when already live); the validation ladder runs
     * synchronously in the deep module and a refusal leaves the status line
     * carrying the readable reason.
     */
    fun start(): PushOutput.StartResult = output.start()

    /** Stops the publisher (clean close) — no-op when already stopped. */
    fun stop() = output.stop()

    /** The hook-less stop, for the manager's codec-gate path (it writes its own Error and settles itself). */
    fun stopQuietly() = output.stopQuietly()

    /**
     * A new push URL lands immediately; a live output restarts on it through
     * the deep module's config-change path (the publisher's connect
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

    private companion object {
        private const val TAG = "RtmpOutput"
    }
}
