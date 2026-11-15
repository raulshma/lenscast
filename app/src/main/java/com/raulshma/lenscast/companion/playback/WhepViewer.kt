package com.raulshma.lenscast.companion.playback

import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.streaming.whip.WhipOfferBuilder
import com.raulshma.lenscast.streaming.webrtc.GatherObserver
import com.raulshma.lenscast.streaming.webrtc.WebRtcPlumbing
import com.raulshma.lenscast.streaming.webrtc.WebrtcSdpException
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pure WHEP wire verdicts, ported line-for-line from web/src/lib/whepClient.ts
 * (the Kotlin viewer pins the same contract the vitest suite pins there). The
 * HTTP exchange itself lives in [WhepViewer]; these helpers are the decisions
 * a JVM test can pin without any network.
 */
object WhepSignaling {

    /**
     * whepClient.ts `GATHER_TIMEOUT_MS`: host candidates appear instantly on
     * the LAN, so waiting past 1.5s never buys connectivity — the offer POSTs
     * with whatever gathered by then (the server side answers with its own
     * candidates either way). Deliberately shorter than the WHIP publisher's
     * [WebRtcPlumbing.GATHER_TIMEOUT_MS] (3s): a viewer wants join speed.
     */
    const val GATHER_TIMEOUT_MS = 1_500L

    /**
     * whepClient.ts `MEDIA_TIMEOUT_MS` — the "answer ≠ media" window. The SDP
     * answer can settle while no frame ever renders (ICE dead behind AP
     * isolation, an undecodable track), and a starved MediaStream raises no
     * element error, so past this window the rung reports
     * [WhepState.Failed] and the caller demotes the ladder. Longer than the
     * HLS/MJPEG readiness windows because WebRTC still owes ICE + DTLS + a
     * first keyframe after the answer lands.
     */
    const val FIRST_FRAME_TIMEOUT_MS = 6_000L

    /** whepClient.ts `isWhepAnswer`: 201 Created is the WHEP convention; 200 tolerated. */
    fun isWhepAnswer(status: Int, body: String): Boolean =
        (status == 201 || status == 200) && body.trimStart().startsWith("v=")

    /**
     * whepClient.ts `whepSessionIdFromLocation`: the session id out of a
     * `Location: /whep/{id}` header (absolute or relative). Null when the
     * header is absent or id-less — the session still plays; there is just
     * nothing to DELETE on teardown (the device reaps it once ICE dies).
     */
    fun sessionIdFromLocation(location: String?): String? {
        if (location == null) return null
        val trimmed = location.trim()
        val marker = "/whep/"
        val index = trimmed.lastIndexOf(marker)
        if (index < 0) return null
        val id = trimmed.substring(index + marker.length)
        return id.takeIf { it.isNotEmpty() }
    }

    /**
     * The teardown URL for a captured `Location`, built against the device's
     * [whepUrl] (`…/whep` + `/` + id) exactly as the ts client builds
     * `/whep/${id}` same-origin — the id is the only part that matters, and
     * resolving against the dialed base keeps a rewritten-Location MITM from
     * redirecting the DELETE off-device. Null when there is no id to free.
     */
    fun deleteSessionUrl(whepUrl: String, location: String?): String? {
        val id = sessionIdFromLocation(location) ?: return null
        return "$whepUrl/$id"
    }
}

/**
 * The link-level verdict the viewer watches. A direct mapping of
 * [PeerConnection.IceConnectionState] (the ts client's `iceConnectionState`)
 * with [isFatal] mirroring whepClient.ts `isFatalIceState`: FAILED and CLOSED
 * end the rung; DISCONNECTED is transient and waits (a Wi-Fi blip recovers —
 * the server's own hold loop treats it the same way).
 */
enum class WhepPeerLinkState {
    NEW,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED,
    ;

    /** whepClient.ts `isFatalIceState`: `failed` or `closed` — the rung is over. */
    val isFatal: Boolean
        get() = this == FAILED || this == CLOSED
}

/**
 * The PeerConnection half of the WHEP rung, behind a seam exactly like the
 * ts client's injectable `newPeerConnection` — the JVM tests drive the whole
 * offer/answer/watchdog state machine against a fake, and only the real
 * ICE/DTLS wire is device-bound ([AndroidWhepPeer]). One instance is ONE
 * peer connection: [WhepViewer] creates a fresh peer per start and
 * [close]s it on every teardown, so a restart never negotiates on a
 * burned-down PC.
 */
interface WhepPeer {

    /**
     * Attach/detach the render surface (the wave-3 composable wraps a
     * [SurfaceViewRenderer] in AndroidView and hands it here; null detaches).
     * Attachable before [open] (frames render from the first one) or after
     * (a late attach binds the already-flowing track). OWNERSHIP: the caller
     * owns the renderer's Android lifecycle (compose dispose calls
     * SurfaceViewRenderer.release); this peer only init()s and binds it.
     */
    fun attachRenderer(renderer: SurfaceViewRenderer?)

    /**
     * Build the PC, add recvonly video+audio transceivers, create the offer,
     * run the one-shot gather (capped), and return the local offer SDP with
     * every candidate inline — the body the caller POSTs. Throws on failure;
     * the viewer maps any throw to [WhepState.Failed].
     */
    suspend fun open(): String

    /** Apply the device's answer SDP; media starts flowing toward the surface. */
    fun acceptAnswer(answerSdp: String)

    /**
     * True once a decoded frame actually reached the attached surface (the
     * renderer's onFirstFrameRendered beat — the ts client's
     * `loadeddata`/`playing` element events). With NO surface attached the
     * closest available signal stands in for it: the link reaching CONNECTED
     * (media is flowing; there is just nothing to draw into) so a headless
     * viewer does not watchdog-fail on healthy media.
     */
    fun hasFirstFrameSignal(): Boolean

    /** The current link verdict; polled by the viewer's watchdog and hold loops. */
    fun linkState(): WhepPeerLinkState

    /** Close the PC and dispose everything this peer built; idempotent. */
    fun close()
}

/** The companion viewer's WHEP session states — whepClient.ts `WhepStatus` ported as a type. */
sealed interface WhepState {

    /** No session wanted (initial, or after [WhepViewer.stop]) — the ts client's `idle`. */
    data object Idle : WhepState

    /** start() called, handshake or first-frame wait in flight — `negotiating`. */
    data object Connecting : WhepState

    /** A frame reached the surface; the device holds a viewer slot until stop() DELETEs it — `playing`. */
    data object Live : WhepState

    /**
     * The rung is over — the caller demotes the ladder (PlayerLadder.demote).
     * [reason] is a readable verdict, never a parse target (the web client's
     * same rule: the status rides the error, never the string).
     */
    data class Failed(val reason: String) : WhepState
}

/**
 * The companion's WHEP viewer — the top ladder rung, the Kotlin port of
 * web/src/lib/whepClient.ts: one SDP offer POSTed to `/whep`, the answer SDP
 * back (201 + `Location: /whep/{id}`), the remote description set, and the
 * negotiated video track rendered into a surface.
 *
 * **Why this rung exists (and what it costs):** sub-second glass-to-glass
 * latency — one hardware H.264 decode straight off the RTP stack, no
 * segmented-buffer intermediate. The tradeoffs are accepted deliberately:
 * the SDP exchange is plaintext-over-HTTP against a device the client pins
 * by certificate fingerprint ([CompanionDevice.tlsFingerprint] — the pinning
 * rides [LenscastApiClient.sharedEngine], and the token/basic/cookie auth
 * headers ride [LenscastApiClient.sharedAuthHeaders], so the MITM posture is
 * identical to every other lane), and the media itself rides SRTP over the
 * same DTLS-certified ICE path. Latency-averse because live: the rung is
 * watched with a 6s "answer ≠ media" watchdog ([WhepSignaling.FIRST_FRAME_TIMEOUT_MS])
 * and demotes to HLS (~10s behind live) on any failure.
 *
 * **The viewer-slot economy:** the device serves at most 4 concurrent WHEP
 * viewers, each with its own hardware encoder (`StreamDefaults.WHEP_MAX_VIEWERS`).
 * A slot is held from the 201 answer until the teardown DELETE — this class
 * fires that DELETE on every exit path (stop, failure, a stale run whose POST
 * landed after the run was abandoned), so a demoted rung never parks a device
 * encoder for the reap window's duration. State [WhepState.Live] ⇔ slot held.
 *
 * **Structure (the ts client's injectable-transport translation):** the
 * signaling half is plain OkHttp over the shared engine and runs in any JVM;
 * the PeerConnection half sits behind [WhepPeer] (production:
 * [AndroidWhepPeer]; tests: a fake). The engine loop — offer → POST →
 * verdict → answer → first-frame watchdog → fatal-link hold — is the
 * vitest-pinned state machine, one supervised coroutine per start.
 *
 * Threading/cancellation: [start] launches into [scope] and [stop] cancels
 * that job; the in-flight offer POST is deliberately NOT call-cancelled (the
 * ts fetch is never aborted either) — a session the server created for a
 * dying run is parsed out of the late answer and DELETEd, never orphaned.
 * After [stop], [state] settles on [WhepState.Idle] and the instance can
 * start again (a fresh [WhepPeer] is minted per session by [peerFactory]).
 */
class WhepViewer(
    private val api: LenscastApiClient,
    /** One peer connection per session, minted fresh on every start. */
    private val peerFactory: () -> WhepPeer,
    /** The engine's home. stop() cancels the session job, not this scope. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clockMs: () -> Long = System::currentTimeMillis,
    /**
     * The loop's pacing seam: production sleeps, tests advance a fake clock —
     * the watchdog verdicts are pinned without real time (and without
     * kotlinx-coroutines-test virtual time, which false-passes beside real
     * threads; see MjpegFrameSourceTest's header note).
     */
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
) {

    private val _state = MutableStateFlow<WhepState>(WhepState.Idle)
    val state: StateFlow<WhepState> = _state.asStateFlow()

    /** The live session's job — stop() cancels it, start() replaces it. */
    private var sessionJob: Job? = null

    /** The session's peer, kept for renderer (re)attach from any thread. */
    @Volatile
    private var peer: WhepPeer? = null

    /** The teardown URL captured from the answer's Location, until freed. */
    @Volatile
    private var deleteUrl: String? = null

    /**
     * Starts one session into [surface] (attachable; may be attached later via
     * [attachRenderer]). A start over a live session is a restart: the old
     * session is torn down first, mirroring the ts `start()`'s leading stop().
     */
    fun start(surface: SurfaceViewRenderer? = null) {
        stop()
        peer = peerFactory()
        surface?.let { peer?.attachRenderer(it) }
        _state.value = WhepState.Connecting
        sessionJob = scope.launch { runSession() }
    }

    /** The wave-3 attach API: bind or rebind the render surface mid-session. */
    fun attachRenderer(renderer: SurfaceViewRenderer) {
        peer?.attachRenderer(renderer)
    }

    /** Unbind the surface (e.g. the composable left composition); media keeps flowing. */
    fun detachRenderer() {
        peer?.attachRenderer(null)
    }

    /**
     * Tears the session down: cancels the engine (which closes the peer and
     * DELETEs the device session), resets [state] to [WhepState.Idle].
     * Idempotent, safe mid-handshake.
     */
    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        peer = null
        if (_state.value != WhepState.Idle) _state.value = WhepState.Idle
    }

    // ── the engine: one offer/answer/watchdog session ──

    private suspend fun runSession() {
        val sessionPeer = checkNotNull(peer)
        try {
            negotiateAndHold(sessionPeer)
        } catch (ce: CancellationException) {
            throw ce // stop()/scope death — stop() already stamped Idle
        } catch (t: Throwable) {
            _state.value = WhepState.Failed(t.message ?: "WHEP session failed")
        } finally {
            withContext(NonCancellable) {
                sessionPeer.close()
                releaseSessionSlot()
            }
        }
    }

    private suspend fun negotiateAndHold(sessionPeer: WhepPeer) {
        val offerSdp = sessionPeer.open()
        // The m-line summary rides the POST: a refusal like "no video media
        // section" is undiagnosable without knowing what was actually sent.
        Log.i(
            "WhepViewer",
            "WHEP offer POST: ${offerSdp.lineSequence().filter { it.startsWith("m=") }.joinToString(" | ").ifBlank { "(no m-lines, ${offerSdp.length} chars)" }}",
        )

        // The offer POST is intentionally un-cancellable (suspendCancellableCoroutine
        // with no cancellation hook): stop() must not abort it, because the device may
        // have already minted the session — its answer carries the Location we need to
        // DELETE. The ts client relies on exactly this (fetch is never aborted; a stale
        // run parses the answer and DELETEs the stale id). One LAN round trip bounds the
        // wait; the shared engine's read timeout is the backstop.
        val answer = postOfferUninterruptibly(offerSdp)

        if (!currentCoroutineContext().isActive) {
            // This run was abandoned while the POST was in flight; the device may
            // have just created the session for it — DELETE it here, because the
            // winner's stop() had no id to release (the ts stale-run leak guard).
            WhepSignaling.deleteSessionUrl(api.whepUrl(), answer.location)?.let { stale ->
                deleteSession(stale)
            }
            return
        }
        if (!WhepSignaling.isWhepAnswer(answer.status, answer.body)) {
            throw SessionRefused(whepRefusalReason(answer.status, answer.body))
        }
        deleteUrl = WhepSignaling.deleteSessionUrl(api.whepUrl(), answer.location)

        sessionPeer.acceptAnswer(answer.body)

        // ── the "answer ≠ media" watchdog ──
        val deadline = clockMs() + WhepSignaling.FIRST_FRAME_TIMEOUT_MS
        while (!sessionPeer.hasFirstFrameSignal()) {
            val link = sessionPeer.linkState()
            if (link.isFatal) {
                throw SessionRefused("WebRTC link ${link.name.lowercase()} before the first frame")
            }
            if (clockMs() >= deadline) {
                throw SessionRefused(
                    // The link verdict rides the reason: a stale NEW means ICE
                    // never began (candidates missing), CHECKING means it never
                    // completed (firewall/route), CONNECTED means the track never
                    // rendered — three different fixes.
                    "no video within ${WhepSignaling.FIRST_FRAME_TIMEOUT_MS}ms of the answer (link ${sessionPeer.linkState()})",
                )
            }
            pauseMs(WATCHDOG_TICK_MS)
        }

        // ── live hold: a dead link (and only that) ends the rung ──
        _state.value = WhepState.Live
        while (true) {
            val link = sessionPeer.linkState()
            if (link.isFatal) {
                throw SessionRefused("WebRTC link ${link.name.lowercase()} during playback")
            }
            pauseMs(HOLD_TICK_MS)
        }
    }

    private class SessionRefused(reason: String) : Exception(reason)

    private fun whepRefusalReason(status: Int, body: String): String =
        when {
            status !in 200..299 ->
                "WHEP offer refused (HTTP $status)${body.take(120).ifBlank { "" }.let { if (it.isBlank()) "" else ": $it" }}"
            else -> "WHEP answer body is not SDP (HTTP $status)"
        }

    // ── signaling transport (OkHttp over the shared, pinned engine) ──

    private suspend fun postOfferUninterruptibly(offerSdp: String): WhepAnswer =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(api.whepUrl())
                // A hand-rolled body, NOT String.toRequestBody: okhttp 5.5's
                // string-body factories append "; charset=utf-8" to a
                // charset-less media type, and the web offer POST's exact
                // `Content-Type: application/sdp` is the wire parity this
                // client keeps (WhepSignalingTest pins it byte-for-byte).
                // contentLength() must be declared: a body of unknown length
                // goes out chunked, and the device's NanoHTTPD reads bodies
                // by Content-Length — a chunked offer arrived EMPTY and the
                // server 400'd "no video media section" over SDP it never
                // saw (found on the loopback E2E).
                .post(object : RequestBody() {
                    override fun contentType(): MediaType = SDP_MEDIA_TYPE
                    override fun contentLength(): Long =
                        offerSdp.toByteArray(Charsets.UTF_8).size.toLong()
                    override fun writeTo(sink: BufferedSink) {
                        sink.writeUtf8(offerSdp)
                    }
                })
                .apply {
                    // The same auth ladder the JSON lane rides, evaluated live — and
                    // the CSRF cover header, because a cookie-authed write (the
                    // session-cookie path of sharedAuthHeaders) 403s without it.
                    for ((name, value) in api.sharedAuthHeaders()) header(name, value)
                    header(CSRF_HEADER, CSRF_VALUE)
                }
                .build()
            api.sharedEngine().awaitUninterruptibly(request)
        }

    private suspend fun releaseSessionSlot() {
        val url = deleteUrl
        deleteUrl = null
        if (url == null) return
        withContext(Dispatchers.IO) {
            runCatching { deleteSession(url) }
        }
    }

    private suspend fun deleteSession(url: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .delete(ByteArray(0).toRequestBody())
            .apply { header(CSRF_HEADER, CSRF_VALUE) }
            .build()
        val response = api.sharedEngine().awaitCancellable(request)
        response.use { answered ->
            // Any 2xx, or 404/410 (already reaped) settles the release — the
            // same verdict WhipSignaling.isDeleteSettled applies for WHIP.
            return answered.code in 200..299 || answered.code == 404 || answered.code == 410
        }
    }

    companion object {
        private val SDP_MEDIA_TYPE = "application/sdp".toMediaType()

        private const val CSRF_HEADER = "X-Requested-With"
        private const val CSRF_VALUE = "XMLHttpRequest"

        /** Watchdog poll cadence: fast enough to beat the 6s window, slow enough to be free. */
        internal const val WATCHDOG_TICK_MS = 100L

        /** The live-hold poll cadence (WhipPublisher's session watch beat). */
        private const val HOLD_TICK_MS = 1_000L
    }
}

// ── OkHttp call adapters ──

/** One completed signaling exchange: status, body text, and the Location header. */
private data class WhepAnswer(val status: Int, val body: String, val location: String?)

/**
 * Enqueue + suspend with NO cancellation hook: the awaiter outlives a
 * cancelled caller just long enough to parse the answer (the stale-run DELETE
 * guard — see [WhepViewer.postOfferUninterruptibly]). The engine's read
 * timeout bounds the park.
 */
private suspend fun OkHttpClient.awaitUninterruptibly(request: Request): WhepAnswer =
    suspendCancellableCoroutine { continuation ->
        newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val parsed = response.use { answered ->
                    WhepAnswer(
                        status = answered.code,
                        body = answered.body?.string().orEmpty(),
                        location = answered.header("Location"),
                    )
                }
                continuation.resume(parsed)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        })
    }

/**
 * Enqueue + suspend, cancelled when the caller dies (closes the socket) — the
 * LenscastApiClient.await idiom, shared by the playback lanes' media GETs
 * (WHEP teardown DELETE, MJPEG open, audio open): the client's own adapter is
 * private and this package needs one cancellable call shape, so it lives here
 * once as internal rather than re-declared per file (same-package private
 * top-levels with equal signatures resolve ambiguously).
 */
internal suspend fun OkHttpClient.awaitCancellable(request: Request): Response =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        })
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
    }

/**
 * The production [WhepPeer]: one libwebrtc peer connection per session,
 * built on the repo's [WebRtcPlumbing] idioms (the WHIP publisher's and the
 * WHEP server's shared SDP/gather plumbing — the two ends cannot drift).
 *
 * Device-only by construction ([PeerConnectionFactory] loads the native
 * library), so — like [com.raulshma.lenscast.streaming.whip.WhipPublisher] —
 * it is compile- and device-verified; every decision in it that a JVM can
 * evaluate is pinned via the pure [WhepSignaling] helpers and the fake-peer
 * tests instead.
 *
 * The wire contract mirrors the ts client exactly: NO iceServers (LAN host
 * candidates — `new RTCPeerConnection()` with default config carries none),
 * recvonly video + audio transceivers (audio rides along when the device's
 * mic arbitration lets its answer carry it — a video-only server rejects the
 * audio m-line, which is fine), one-shot gather capped at
 * [WhepSignaling.GATHER_TIMEOUT_MS] with candidates folded into the offer
 * body ([WhipOfferBuilder.injectCandidates] — idempotent, covers a candidate
 * that landed past the cap), and the answer applied as the remote
 * description. Rendering rides [SurfaceViewRenderer] (stream-webrtc-android's
 * SurfaceEglRenderer behind it): the first
 * [org.webrtc.RendererCommon.RendererEvents.onFirstFrameRendered] beat is the
 * first-frame signal the watchdog watches.
 */
class AndroidWhepPeer(
    context: Context,
) : WhepPeer {

    /** Held as an app context so a screen rotation cannot strand the native pieces. */
    private val appContext: Context = context.applicationContext

    private val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var adm: AudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null

    /** The video track captured from onTrack, bound to the surface when both exist. */
    private var videoTrack: VideoTrack? = null

    /** The renderer currently bound as a sink (addSink twice would double-render). */
    private var boundRenderer: SurfaceViewRenderer? = null

    /** The surface [attachRenderer] last init()ed — the double-init guard's identity. */
    private var initializedRenderer: SurfaceViewRenderer? = null

    @Volatile
    private var renderer: SurfaceViewRenderer? = null

    @Volatile
    private var firstFrameRendered = false

    /** The link verdict as libwebrtc reports it (written on its callback thread). */
    @Volatile
    private var linkState = WhepPeerLinkState.NEW

    private val observer = object : GatherObserver(TAG) {
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            linkState = when (state) {
                PeerConnection.IceConnectionState.NEW -> WhepPeerLinkState.NEW
                PeerConnection.IceConnectionState.CHECKING -> WhepPeerLinkState.CONNECTING
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> WhepPeerLinkState.CONNECTED
                PeerConnection.IceConnectionState.DISCONNECTED -> WhepPeerLinkState.DISCONNECTED
                PeerConnection.IceConnectionState.FAILED -> WhepPeerLinkState.FAILED
                PeerConnection.IceConnectionState.CLOSED -> WhepPeerLinkState.CLOSED
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track() ?: return
            if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND && track is VideoTrack) {
                videoTrack = track
                bindTrackToRenderer()
            }
        }
    }

    /** The renderer's first-frame beat: the watchdog's cancel signal. */
    private val rendererEvents = object : org.webrtc.RendererCommon.RendererEvents {
        override fun onFirstFrameRendered() {
            firstFrameRendered = true
        }

        override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) = Unit
    }

    override fun attachRenderer(surface: SurfaceViewRenderer?) {
        val old = renderer
        renderer = surface
        firstFrameRendered = false
        // Unbind the previous surface (the caller owns its release lifecycle);
        // a fresh one is init()ed and any flowing track bound to it.
        val track = videoTrack
        if (old != null && track != null) runCatching { track.removeSink(old) }
        if (boundRenderer != surface) boundRenderer = null
        if (surface != null) {
            // webrtc's init() throws "Already initialized" on a second call
            // over the same surface — a real race here: a restart can land an
            // attach while the composable's own attach is still the live one
            // (found on the loopback E2E: saving a re-pair re-dialed the WHEP
            // rung mid-attach and crashed the process off the Save button).
            // Same surface means same rendererEvents, so skipping the
            // re-init is the correct no-op; the runCatching keeps a released
            // surface's refusal from taking the app down like any other
            // peer op on this class.
            if (initializedRenderer !== surface) {
                runCatching { surface.init(egl.eglBaseContext, rendererEvents) }
                initializedRenderer = surface
            }
            bindTrackToRenderer()
        }
    }

    private fun bindTrackToRenderer() {
        val surface = renderer ?: return
        val track = videoTrack ?: return
        if (boundRenderer == surface) return
        // SurfaceViewRenderer IS a VideoSink; addSink fans the decoded frames in.
        runCatching { track.addSink(surface) }
        boundRenderer = surface
    }

    override suspend fun open(): String {
        WebRtcPlumbing.ensureFactoryInitialized(appContext)
        val admModule = JavaAudioDeviceModule.builder(appContext)
            // Received Opus playout rides the ADM; no capture settings are
            // pinned because this end never records. Released in close().
            .setEnableVolumeLogger(false)
            .createAudioDeviceModule()
        adm = admModule
        val builtFactory = PeerConnectionFactory.builder()
            // Recvonly rung: a decoder factory is the only media factory the
            // session needs (the server encodes; libwebrtc's default software
            // encoder would never be invoked — not set, not paid for).
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(admModule)
            .createPeerConnectionFactory()
        factory = builtFactory

        val config = PeerConnection.RTCConfiguration(emptyList())
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE

        val pc = builtFactory.createPeerConnection(config, observer)
            ?: throw WebrtcSdpException("libwebrtc failed to create the WHEP viewer peer connection")
        peerConnection = pc

        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )

        val offer = WebRtcPlumbing.awaitCreateOffer(pc, "WHEP")
        WebRtcPlumbing.awaitSetDescription(pc, local = true, description = offer, label = "WHEP")
        val gathered = observer.awaitGatheringComplete(WhepSignaling.GATHER_TIMEOUT_MS)
        // One-shot, no trickle: whatever gathered by the cap goes inline
        // (host candidates appear instantly; this is the LAN contract).
        return WhipOfferBuilder.injectCandidates(
            pc.localDescription.description,
            gathered.map { "a=${it.sdp}" },
        )
    }

    override fun acceptAnswer(answerSdp: String) {
        val pc = peerConnection ?: throw WebrtcSdpException("WHEP peer is not open")
        WebRtcPlumbing.awaitSetDescription(
            pc,
            local = false,
            description = SessionDescription(SessionDescription.Type.ANSWER, answerSdp),
            label = "WHEP",
        )
    }

    override fun hasFirstFrameSignal(): Boolean =
        firstFrameRendered || (renderer == null && linkState == WhepPeerLinkState.CONNECTED)

    override fun linkState(): WhepPeerLinkState = linkState

    override fun close() {
        val surface = renderer
        renderer = null
        runCatching { videoTrack?.removeSink(surface) }
        videoTrack = null
        boundRenderer = null
        firstFrameRendered = false
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        runCatching { adm?.release() }
        adm = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { egl.release() }
        linkState = WhepPeerLinkState.CLOSED
    }

    private companion object {
        private const val TAG = "AndroidWhepPeer"
    }
}
