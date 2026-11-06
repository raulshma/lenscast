package com.raulshma.lenscast.streaming.whip

import com.raulshma.lenscast.streaming.FrameTiming
import com.raulshma.lenscast.streaming.PushConnectConfig
import com.raulshma.lenscast.streaming.PushOutput
import com.raulshma.lenscast.streaming.PushRestartTrigger

/**
 * WHIP's retained push config: the endpoint URL, the optional bearer token,
 * and the STUN server (blank = host candidates only). All three are connect
 * parameters — a change to any of them restarts a live output through the
 * deep module's config diff. The frame rate is deliberately not here: it is
 * a live control, not a connect parameter (see [WhipOutput.setFrameRate]).
 */
internal data class WhipPushConfig(
    override val url: String = "",
    val token: String = "",
    val stunServer: String = com.raulshma.lenscast.core.StreamDefaults.WHIP_STUN_SERVER,
) : PushConnectConfig

/**
 * The WHIP push output — the WHIP configuration of the shared deep
 * [PushOutput] module (the RtspOutput-pattern collapse of the push twins):
 * the retained endpoint/token/STUN settings (they land even while the
 * output is stopped, so the next start picks them up), the enabled gate,
 * the start-time validation ladder (enabled + usable URL parsed by the pure
 * [WhipUrl] — no codec gate, because libwebrtc encodes its own H.264 and is
 * independent of the RTSP codec setting), and the publisher lifecycle with
 * restart-on-config-change. This file carries only the transport-typed
 * wiring — the parse function, the status constructors, the
 * [WhipPublisherHandle] seam (with the blank→null credential handoff), the
 * mic-arbitration verdict evaluated at publisher-construction time, the
 * frame feed, and the fps fan-out — while every lifecycle decision lives in
 * the deep module. The manager keeps the public surface and the fan-out.
 */
internal class WhipOutput(
    /** The mic-arbitration verdict provider, evaluated at publisher-construction time. */
    private val audioAllowed: () -> Boolean,
    /** The status mirror after every transition, so the owner's flows follow the output. */
    onStatusChanged: (WhipStatus) -> Unit,
    /** The publisher handle seam — the transport's factory, unchanged by the collapse. */
    publisherFactory: (
        url: WhipUrl,
        token: String?,
        stunServer: String?,
        audioAllowed: Boolean,
        onStatus: (WhipStatus) -> Unit,
    ) -> WhipPublisherHandle,
    /** The transition epilogue (streaming state; no hub refresh — WHIP does not consume the encoded hub). */
    settle: () -> Unit,
) {

    private val output = PushOutput(
        label = "WHIP",
        tag = TAG,
        onStatusChanged = onStatusChanged,
        settle = settle,
        initialConfig = WhipPushConfig(),
        parse = WhipUrl::parse,
        invalidUrlMessage = "Invalid WHIP URL — expected http(s)://host[:port]/whip-endpoint",
        videoCodec = null,
        describeTarget = { "${it.hostAndPort}${it.resourcePath}" },
        idleStatus = WhipStatus.Idle,
        errorStatus = { reason -> WhipStatus.Error(reason) },
        publisherFactory = { config, parsed, onStatus ->
            // Blank settings hand over nulls, and the mic verdict is captured
            // here — a publisher-construction input, so every (re)start
            // re-arbitrates by re-running this factory.
            publisherFactory(
                parsed,
                config.token.ifBlank { null },
                config.stunServer.ifBlank { null },
                audioAllowed(),
                onStatus,
            )
        },
        startPublisher = { it.start() },
        stopPublisher = { it.stop() },
        beforeStart = { it.setFrameRate(targetFps) },
    )

    // The stream's target fps — a live control on the publisher, not a
    // connect parameter, so it never routes through the config diff.
    @Volatile
    private var targetFps: Int = com.raulshma.lenscast.core.StreamDefaults.STREAM_FPS

    /** Switches the output on/off; true when the flag actually changed. Disabling stops a live output. */
    fun setEnabled(on: Boolean): Boolean = output.setEnabled(on)

    fun isEnabled(): Boolean = output.isEnabled()

    /** True from [start] until [stop] — the owner's "WHIP output live" answer. */
    fun isActive(): Boolean = output.isActive()

    /** The configured endpoint URL, retained even while stopped. */
    fun configuredUrl(): String = output.retainedConfig().url

    fun status(): WhipStatus = output.status()

    /**
     * Starts the push (no-op when already live); the validation ladder runs
     * synchronously in the deep module and a refusal leaves the status line
     * carrying the readable reason.
     */
    fun start(): PushOutput.StartResult = output.start()

    /** Stops the publisher (clean DELETE + teardown) — no-op when already stopped. */
    fun stop() = output.stop()

    /**
     * New settings land immediately; a live output restarts on any of them
     * through the deep module's config-change path (the publisher's connect
     * parameters are per-attempt, so nothing can hot-swap). A same-values
     * call is a no-op, so a settings re-emission never churns a live output.
     */
    fun setUrl(newUrl: String) {
        output.update { it.copy(url = newUrl.trim()) }
    }

    fun setToken(newToken: String) {
        output.update { it.copy(token = newToken.trim()) }
    }

    fun setStunServer(newServer: String) {
        output.update { it.copy(stunServer = newServer.trim()) }
    }

    /** The stream's target fps — the throttle's interval moves live, no restart owed. */
    fun setFrameRate(fps: Int) {
        targetFps = FrameTiming.effectiveFps(fps)
        output.currentPublisher()?.setFrameRate(targetFps)
    }

    /**
     * The mic-arbitration verdict moved after the output went live (recording
     * claimed the microphone, or the stream-audio toggle flipped): a live
     * output restarts so the next session is built with the fresh verdict —
     * the audio decision is a publisher-construction input, not a hot-swap.
     * A stopped output only remembers it for the next start. Routed through
     * the deep module's [PushRestartTrigger.WHILE_ACTIVE] trigger: the
     * verdict is not a config field, so the restart fires whenever the
     * output is live, diff or not.
     */
    fun onMicVerdictChanged() {
        output.update(PushRestartTrigger.WHILE_ACTIVE)
    }

    // ── the analysis tap's sink ──

    /** One camera NV21 frame from the manager's push fan-out. No-op while stopped. */
    fun feedVideoFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int) {
        output.currentPublisher()?.feedVideoFrame(nv21, width, height, rotation)
    }

    private companion object {
        private const val TAG = "WhipOutput"
    }
}
