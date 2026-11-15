package com.raulshma.lenscast.companion.client.ws

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The talkback uplink's lifecycle states.
 *
 * `FAILED` carries its cause — a Kotlin enum entry cannot hold one, so the
 * session exposes it beside the state ([TalkbackSession.failureCause], null
 * except in FAILED).
 */
enum class TalkbackState {
    /** Handshake in flight; frames sent now are queued by OkHttp and flush on open. */
    CONNECTING,

    /** Upgrade done — binary frames are flowing to the device. */
    LIVE,

    /** [TalkbackSession.stop] sent the text `stop`; draining toward a clean close. */
    STOPPING,

    /** Closed cleanly (close handshake completed after `stop`). */
    CLOSED,

    /** The socket died before/without a clean close; see [TalkbackSession.failureCause]. */
    FAILED,
}

/** One live talkback uplink to a device's `/ws/talkback`. */
interface TalkbackSession {
    val state: StateFlow<TalkbackState>

    /** The transport failure behind [TalkbackState.FAILED]; null in every other state. */
    val failureCause: StateFlow<Throwable?>

    /**
     * Sends one mic chunk as a little-endian PCM16 binary WebSocket frame.
     * **The 16 kHz mono format is the CALLER's contract** — this method
     * owns no resampling: the mic sampler converts float→PCM16 (the
     * pttFallback.ts clamp-at-full-scale rule) and downsamples to 16 kHz
     * before calling; the device's uplink path (WsMediaRoutes.TalkbackSocket)
     * consumes exactly 16 kHz mono PCM16 and would play anything else as
     * noise. Safe from CONNECTING (OkHttp queues frames until the upgrade
     * lands); throws [IllegalStateException] once the session is
     * STOPPING/CLOSED/FAILED.
     */
    suspend fun sendPcm(samples: ShortArray)

    /**
     * Ends the push-to-talk hold the way the server's contract demands: send
     * the text `stop` (the TalkbackSocket's end-of-talk signal), then the
     * WebSocket close handshake, then — if the device never answers the
     * close within [TalkbackClient.closeGraceTimeoutMs] — an abrupt cancel.
     * Idempotent.
     */
    fun stop()
}

/**
 * The companion's WebSocket uplink for push-to-talk: connects to a paired
 * device's `/ws/talkback` (streaming/ws/WsMediaRoutes) and streams PCM16
 * chunks as binary frames, ending with the text `stop`. The server contract
 * (WsMediaRoutes.TalkbackSocket): the first binary after a gap (re)opens the
 * speaker stream; a zero-length payload or a text `stop` ends it.
 *
 * Auth rides [connect]'s header map verbatim. Note for the session layer:
 * the device's WS handshake gate reads ONLY the `Cookie` header
 * (WsMediaRoutes passes `headers["cookie"]` to the auth gate — browsers
 * cannot set Authorization/X-Api-Token on a WebSocket), so an authed device
 * needs the session cookie here, not the token header; with auth off the map
 * stays empty.
 */
class TalkbackClient(
    private val engine: OkHttpClient,
    /** How long [TalkbackSession.stop] waits for the close handshake before cancelling the socket. */
    val closeGraceTimeoutMs: Long = DEFAULT_CLOSE_GRACE_MS,
) {

    fun connect(url: String, headers: Map<String, String>): TalkbackSession {
        val request = Request.Builder().url(url).apply {
            for ((name, value) in headers) header(name, value)
        }.build()
        val session = LiveTalkbackSession(closeGraceTimeoutMs)
        // newWebSocket hands back the RealWebSocket synchronously — captures
        // it here so stop()/sendPcm() work from the very first moment (sends
        // during CONNECTING are enqueued by OkHttp and flush on open; there
        // is no silent-drop window before onOpen).
        val socket = engine.newWebSocket(request, session.listener)
        session.attach(socket)
        return session
    }

    /** The drain budget for the close handshake before the watchdog cancels the socket. */
    companion object {
        const val DEFAULT_CLOSE_GRACE_MS = 2_000L
    }

    private class LiveTalkbackSession(private val closeGraceTimeoutMs: Long) : TalkbackSession {

        private val _state = MutableStateFlow(TalkbackState.CONNECTING)
        override val state: StateFlow<TalkbackState> = _state.asStateFlow()

        private val _failureCause = MutableStateFlow<Throwable?>(null)
        override val failureCause: StateFlow<Throwable?> = _failureCause.asStateFlow()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // A stop() that raced the handshake wins: no LIVE beat, go
                // straight through the stop handshake the moment we open.
                if (_state.value == TalkbackState.STOPPING) {
                    runStopHandshake(webSocket)
                } else {
                    _state.value = TalkbackState.LIVE
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // The talkback socket is uplink-only; the device never texts
                // back. Ignore rather than parse — anything arriving is
                // out-of-band noise.
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Same: binary downlink frames are not part of the contract.
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = TalkbackState.CLOSED
                shutDownWatchdog()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A cancel() we initiated during STOPPING is the close
                // watchdog timing out — the session chose this ending, so it
                // lands as CLOSED, not FAILED (the user got their silence).
                if (_state.value == TalkbackState.STOPPING) {
                    _state.value = TalkbackState.CLOSED
                } else if (_state.value != TalkbackState.CLOSED) {
                    _failureCause.value = t
                    _state.value = TalkbackState.FAILED
                }
                shutDownWatchdog()
            }
        }

        @Volatile private var webSocket: WebSocket? = null
        private val stopOnce = AtomicBoolean(false)

        /**
         * Single daemon thread, spawned lazily on the first stop() — a
         * session that never stops costs nothing.
         */
        private val watchdog = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "TalkbackCloseWatchdog").apply { isDaemon = true }
        }

        fun attach(webSocket: WebSocket) {
            this.webSocket = webSocket
            if (_state.value == TalkbackState.STOPPING) runStopHandshake(webSocket)
        }

        override suspend fun sendPcm(samples: ShortArray) {
            when (_state.value) {
                TalkbackState.STOPPING, TalkbackState.CLOSED, TalkbackState.FAILED ->
                    throw IllegalStateException("Talkback session is ${_state.value}; no more audio")
                else -> Unit // CONNECTING: OkHttp queues frames until the upgrade lands
            }
            // Float→PCM16 conversion (and the clamp-at-full-scale rule from
            // pttFallback.ts) is the mic sampler's job; this is pure packing.
            val frame = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) frame.putShort(sample)
            // send() is an enqueue, not a socket write — cheap, and false
            // only after a close/cancel already raced us (state flips on the
            // listener thread right after; the next sendPcm throws instead).
            webSocket?.send(frame.array().toByteString())
        }

        override fun stop() {
            if (!stopOnce.compareAndSet(false, true)) return // idempotent
            // Already ended without us (transport failure / server close):
            // keep the terminal truth instead of rewriting FAILED→STOPPING.
            if (_state.value == TalkbackState.FAILED || _state.value == TalkbackState.CLOSED) return
            _state.value = TalkbackState.STOPPING
            val socket = webSocket ?: run {
                // connect() has not handed the socket back yet: attach() (or
                // the onOpen race path) runs the handshake when it arrives.
                return
            }
            runStopHandshake(socket)
        }

        /** Text `stop`, then the close handshake, then the timeout watchdog. */
        private fun runStopHandshake(socket: WebSocket) {
            // The server contract's end-of-talk signal, then the graceful
            // close — OkHttp flushes queued writes in order, so `stop`
            // always precedes the goodbye on the wire.
            socket.send(STOP_SIGNAL)
            socket.close(CLOSE_CODE, "talkback done")
            // The device answers the close handshake from its reader thread;
            // a vanished device never answers at all, so the watchdog bounds
            // the drain instead of trusting TCP.
            watchdog.execute {
                try {
                    Thread.sleep(closeGraceTimeoutMs)
                } catch (_: InterruptedException) {
                    return@execute // terminal state won; nothing to cancel
                }
                if (_state.value == TalkbackState.STOPPING) socket.cancel()
            }
        }

        private fun shutDownWatchdog() {
            watchdog.shutdownNow()
        }

        companion object {
            const val STOP_SIGNAL = "stop"
            const val CLOSE_CODE = 1000
        }
    }
}
