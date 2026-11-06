package com.raulshma.lenscast.streaming

import android.util.Log
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The push outputs' shared lifecycle state — the open base the three
 * transport hierarchies extend
 * ([com.raulshma.lenscast.streaming.rtmp.RtmpStatus],
 * [com.raulshma.lenscast.streaming.srt.SrtStatus],
 * [com.raulshma.lenscast.streaming.whip.WhipStatus]) so [PushOutput] can
 * mirror any of them without knowing the transport. The transport classes
 * keep their exact public shape — same names, same constructors, same
 * `wireName` values (the `/api/status` contract); extending this base only
 * hands the deep module a common type to hold. Deliberately an open
 * [abstract] class rather than `sealed`: the transport hierarchies live in
 * their own packages, and Kotlin sealed hierarchies cannot cross a package
 * boundary. Public only because the transport statuses are (the manager's
 * status flows expose them); the module's own machinery stays internal.
 */
abstract class PushStatus(val wireName: String)

/**
 * The one field every push transport's retained config carries: the push
 * URL. The deep module's start ladder parses it; the config value classes
 * behind it ([com.raulshma.lenscast.streaming.rtmp.RtmpPushConfig],
 * [com.raulshma.lenscast.streaming.srt.SrtPushConfig],
 * [com.raulshma.lenscast.streaming.whip.WhipPushConfig]) add whatever else
 * their transport connects with (WHIP's token and STUN server).
 */
internal interface PushConnectConfig {
    val url: String
}

/**
 * How a settings change reaches a live push output, in the [RestartTrigger]
 * style: [FROM_DIFF] is the default routing — a real config change restarts
 * a live output, an empty diff is a no-op so a settings re-emission never
 * churns a push. [WHILE_ACTIVE] overrides the diff and restarts whenever
 * the output is live even when no config field moved — the WHIP
 * mic-arbitration re-arm, whose verdict is a publisher-construction input
 * rather than a config field.
 */
internal enum class PushRestartTrigger {
    FROM_DIFF,
    WHILE_ACTIVE,
}

/**
 * The deep push output — the RTMP/SRT/WHIP twins collapsed into one module,
 * in [RtspOutput]'s pattern: the retained config (every setting lands here
 * even while the output is stopped, so the next start picks it all up), the
 * enabled gate, the start-time validation ladder (enabled → URL parse →
 * optional H.264 codec gate), the publisher lifecycle with
 * restart-on-config-change, and the status mirror. Everything "the RTMP
 * output" (or SRT's, or WHIP's) means sits behind this one class;
 * [com.raulshma.lenscast.streaming.rtmp.RtmpOutput] and its two siblings are
 * thin configurations of it — they supply the per-transport parameters and
 * keep the transport-typed surface (the parsed-URL type, the status type,
 * the publisher handle, the media feed) so the manager's wiring stays
 * stable.
 *
 * The per-transport configuration points:
 * - [parse] — the pure URL decode ([com.raulshma.lenscast.streaming.rtmp.RtmpUrl.parse]
 *   / [com.raulshma.lenscast.streaming.srt.SrtUrl.parse] /
 *   [com.raulshma.lenscast.streaming.whip.WhipUrl.parse]); a null verdict
 *   refuses the start with [invalidUrlMessage].
 * - [videoCodec] — the shared encode path's codec verdict, read at start.
 *   The H.264-only transports (RTMP — no standard H.265 mapping; SRT — the
 *   shared TS muxing is H.264-pinned) pass the hub's codec and refuse H.265
 *   with a readable error; WHIP passes null (no gate — libwebrtc encodes its
 *   own H.264, independent of the RTSP codec setting).
 * - [publisherFactory] — the transport's publisher handle seam, exactly like
 *   [RtspOutput]'s `serverFactory`; the handle comes back typed and the deep
 *   module only drives it through [startPublisher]/[stopPublisher], so the
 *   per-transport handle interfaces and the publishers behind them stay
 *   untouched.
 * - [describeTarget] — the log-safe rendering of a parsed target. This is
 *   the redaction seam: SRT hands back [com.raulshma.lenscast.streaming.srt.SrtUrl.redacted]
 *   (userinfo and streamid are write-only credentials that never reach a
 *   log), RTMP/WHIP render host(+path) only.
 * - [idleStatus]/[errorStatus] — the transport's status constructors, so the
 *   mirror publishes the transport's own type.
 * - [beforeStart] — the hook between publisher construction and its start
 *   (WHIP's frame-rate handoff).
 *
 * The transition epilogue is injected, not owned: a manager-driven
 * [start]/[stop] runs [settle] after the transition lands (the encoded-hub
 * refresh + streaming-state mirror, exactly where the pre-collapse manager
 * scaffolding ran it), while an internal restart (config change, the
 * WHILE_ACTIVE trigger) and a disable-stop settle quietly — the hub's
 * per-frame policy verdict re-reads the activity flags on its own, and the
 * next manager-driven transition catches the state mirror up.
 *
 * Decisions are JVM-tested behind the publisher-handle seams in
 * [com.raulshma.lenscast.streaming.PushOutputTest] (the shared lifecycle,
 * run against all three transport configurations) plus the per-transport
 * suites (transport-distinct wiring).
 */
internal class PushOutput<C : PushConnectConfig, P, H, S : PushStatus>(
    /** The wire-safe family label ("RTMP"/"SRT"/"WHIP") — the readable refusal messages and log lines. */
    private val label: String,
    /** The log tag ("RtmpOutput"/"SrtOutput"/"WhipOutput") — the transports keep their own tags. */
    private val tag: String,
    /** The status mirror after every transition, so the owner's flows follow the output. */
    private val onStatusChanged: (S) -> Unit,
    /** The transition epilogue for manager-driven starts/stops (hub refresh + streaming state); see the class doc. */
    private val settle: () -> Unit,
    /** The retained config the transport starts from (all defaults — a fresh output has no settings). */
    initialConfig: C,
    /** The pure URL decode; a null verdict refuses the start with [invalidUrlMessage]. */
    private val parse: (String) -> P?,
    /** The readable refusal for an unparsable URL, with the transport's shape hint. */
    private val invalidUrlMessage: String,
    /**
     * The shared encode path's codec verdict, read at start — the H.264
     * gate. Null for the transports outside the encoded hub (WHIP): no
     * codec input exists there, so nothing can refuse for the codec setting.
     */
    private val videoCodec: (() -> RtspVideoCodec)?,
    /** The log-safe rendering of a parsed target — the redaction seam. */
    private val describeTarget: (P) -> String,
    /** The transport's idle status (its data object) — published by stop. */
    private val idleStatus: S,
    /** The transport's Error constructor — published by every refused start. */
    private val errorStatus: (String) -> S,
    /** Builds the publisher handle from the retained config + parsed target — the transport's factory seam. */
    private val publisherFactory: (config: C, parsed: P, onStatus: (S) -> Unit) -> H,
    /** Drives the handle's start (the per-transport handle interfaces stay untouched behind the lambda). */
    private val startPublisher: (H) -> Unit,
    /** Drives the handle's clean close. */
    private val stopPublisher: (H) -> Unit,
    /** Runs between the publisher's construction and its start; WHIP hands the target fps over here. */
    private val beforeStart: (H) -> Unit = {},
) {

    /** The output's start verdict: Started, or Rejected with the readable reason. */
    sealed class StartResult {
        data object Started : StartResult()
        data class Rejected(val reason: String) : StartResult()
    }

    /** The retained connect config; settings land here even while stopped, so the next start picks them up. */
    @Volatile
    var config: C = initialConfig
        private set

    /** The output is switched on — start() while disabled refuses. */
    private val enabled = AtomicBoolean(false)

    /** The output is live: started and not yet stopped, even while connecting/reconnecting. */
    private val active = AtomicBoolean(false)

    @Volatile
    private var publisher: H? = null

    @Volatile
    private var statusValue: S = idleStatus

    // ── flags & state reads ──

    /**
     * Switches the output on/off; true when the flag actually changed.
     * Disabling stops a live output through the hook-less stop — the
     * manager's disable path never ran the transition epilogue (the hub's
     * per-frame policy verdict re-reads the activity flags on its own), and
     * that ordering is preserved here.
     */
    fun setEnabled(on: Boolean): Boolean {
        val changed = enabled.getAndSet(on) != on
        if (changed && !on) stopQuietly()
        return changed
    }

    fun isEnabled(): Boolean = enabled.get()

    /** True from [start] until [stop] — the owner's "output live" answer. */
    fun isActive(): Boolean = active.get()

    /** The retained config, for the transport's configured-value reads. */
    fun retainedConfig(): C = config

    /** The live publisher handle, or null while stopped — the transport's feed/stats read seam. */
    fun currentPublisher(): H? = publisher

    fun status(): S = statusValue

    // ── lifecycle ──

    /**
     * Starts the push (no-op when already live). The validation ladder runs
     * synchronously — a disabled output, an unusable URL, or the H.265 codec
     * refusal each answer [StartResult.Rejected] with the readable reason
     * and leave the status line carrying it; only a passing ladder starts
     * the publisher (whose connect is asynchronous and auto-reconnecting).
     * Either verdict settles through the epilogue — a refused start leaves
     * the manager state refreshed exactly like a started one.
     */
    fun start(): StartResult {
        if (active.get()) return StartResult.Started
        val result = startQuietly()
        settle()
        return result
    }

    /** Stops the publisher (the transport's clean close) and settles — no-op when already stopped. */
    fun stop() {
        if (!active.getAndSet(false)) return
        teardownPublisher()
        settle()
    }

    /**
     * The hook-less stop for the manager's direct-drive paths: the H.265
     * codec-gate stop writes its own crafted Error status and runs its own
     * epilogue after it, so no settle happens here. Same guard and teardown
     * as [stop] — only the epilogue differs.
     */
    fun stopQuietly() {
        if (!active.getAndSet(false)) return
        teardownPublisher()
    }

    /** The transport's clean close: stop the handle, drop it, publish idle. */
    private fun teardownPublisher() {
        publisher?.let(stopPublisher)
        publisher = null
        publishStatus(idleStatus)
        Log.i(tag, "$label output stopped")
    }

    // ── settings: the restart routing ──

    /**
     * The one settings entry, in the [RtspOutput.update] style: land the
     * transform on the retained config, then route. Any real config change
     * restarts a live, enabled output through its own stop → start path
     * (the publisher's connect parameters are per-attempt, so nothing can
     * hot-swap) — and a restart that fails its own start ladder (e.g. a
     * live output re-targeted at an unparsable URL) lands stopped with the
     * readable error on the status line. An empty diff is a no-op, so a
     * settings re-emission never churns a live output. The
     * [PushRestartTrigger.WHILE_ACTIVE] trigger skips the diff entirely:
     * the change it carries is not a config field, and a live output must
     * rebuild regardless (a stopped output only remembers its settings for
     * the next start). Restarts settle quietly — see the class doc.
     */
    fun update(
        trigger: PushRestartTrigger = PushRestartTrigger.FROM_DIFF,
        transform: (C) -> C = { it },
    ) {
        val old = config
        config = transform(old)
        if (trigger == PushRestartTrigger.WHILE_ACTIVE || config != old) {
            restartQuietly()
        }
    }

    // ── internals ──

    /**
     * The restart ladder a live output takes on any config change: stop →
     * start, with the enabled check kept from the twins (active implies
     * enabled, but the guard documents the intent). The start re-runs the
     * full validation ladder — a restart is a start, not a hot-swap.
     */
    private fun restartQuietly() {
        if (enabled.get() && active.get()) {
            stopQuietly()
            startQuietly()
        }
    }

    /** The validation ladder + publisher construction, without the settle. */
    private fun startQuietly(): StartResult {
        if (active.get()) return StartResult.Started
        if (!enabled.get()) {
            return reject("$label push is disabled")
        }
        val parsed = parse(config.url)
            ?: return reject(invalidUrlMessage)
        val codec = videoCodec?.invoke()
        if (codec != null && codec != RtspVideoCodec.H264) {
            return reject(
                "$label push requires the H.264 video codec — the encoded path is currently H.265; " +
                    "switch the RTSP video codec to H.264 in streaming settings",
            )
        }
        active.set(true)
        val handle = publisherFactory(config, parsed, ::publishStatus)
        publisher = handle
        beforeStart(handle)
        startPublisher(handle)
        Log.i(tag, "$label output started toward ${describeTarget(parsed)}")
        return StartResult.Started
    }

    private fun reject(reason: String): StartResult.Rejected {
        publishStatus(errorStatus(reason))
        Log.w(tag, "$label start refused: $reason")
        return StartResult.Rejected(reason)
    }

    private fun publishStatus(status: S) {
        statusValue = status
        onStatusChanged(status)
    }
}
