package com.raulshma.lenscast.streaming.whep

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.YuvConverter
import com.raulshma.lenscast.core.toHexString
import com.raulshma.lenscast.streaming.FrameThrottle
import com.raulshma.lenscast.streaming.FrameTiming
import com.raulshma.lenscast.streaming.whip.WhipOfferBuilder
import com.raulshma.lenscast.streaming.webrtc.GatherObserver
import com.raulshma.lenscast.streaming.webrtc.WebRtcPlumbing
import com.raulshma.lenscast.streaming.webrtc.WebrtcSdpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.NV21Buffer
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The WHEP viewer endpoint (RFC 9725's egress twin): browsers POST an SDP
 * offer to `/whep` and watch the live camera sub-second over WebRTC — the
 * viewing half of the WHIP push output's publishing half.
 *
 * **Media:** every viewer gets its own [PeerConnection] with its own
 * libwebrtc hardware H.264 encoder (the same
 * [DefaultVideoEncoderFactory] WHIP uses), fed from ONE shared
 * [VideoSource] that taps the same NV21 analysis frames the WHIP publisher
 * consumes — the manager fans every camera frame in through
 * [feedVideoFrame], throttled to the stream's target fps and rotated
 * upright exactly like WHIP. The tap is the supported libwebrtc
 * integration; the encoded-hub access units are deliberately not fed here
 * (see the WHIP publisher's KDoc for that decision). WHEP v1 is H.264-only
 * by construction — libwebrtc encodes its own video, independent of the
 * RTSP codec setting, and H.265 is not offered.
 *
 * **Audio:** one mic-arbitration verdict with WHIP's
 * (`streamAudioEnabled && !recording && !ecoIdle && shared capture free`),
 * evaluated per session at offer time. When audio is allowed, the shared
 * environment carries a dedicated 16 kHz mono [JavaAudioDeviceModule] whose
 * Opus track every viewer's peer connection shares — one AudioRecord for
 * all viewers, never one per session. When it is not, sessions run
 * video-only (libwebrtc's answer marks the audio m-line rejected).
 *
 * **Transport:** one-shot ICE, no trickle — the answer is only written once
 * gathering completed (capped at [WebRtcPlumbing.GATHER_TIMEOUT_MS], host
 * candidates alone are usable on a LAN) and carries every candidate inline.
 * The optional STUN setting is the existing WHIP one; there is no TURN
 * (same limitation as WHIP, documented). No keepalive exists in v1: a
 * viewer DELETEs `/whep/{id}` on teardown, and the registry reaps sessions
 * that never connected within [WhepSessionRegistry.CONNECT_REAP_MS] or stay
 * DISCONNECTED past [WhepSessionRegistry.DISCONNECTED_REAP_MS] — a dead
 * tab can never hold a hardware encoder slot.
 *
 * The whole libwebrtc path is device-only (the factory loads the native
 * library), so — like the WHIP publisher — this class is compile- and
 * device-verified; the decisions around it (offer gating, registry cap and
 * reap, auth) are the pure modules next to it, JVM-tested.
 */
class WhepServer(
    private val context: Context,
    /** The STUN setting provider — the manager hands over the WHIP STUN value. */
    private val stunServer: () -> String?,
    /** The per-session mic-arbitration verdict (WHIP's rule, evaluated at offer time). */
    private val audioAllowed: () -> Boolean,
    /** The concurrent-viewer cap (the RTSP server's max-4 convention). */
    private val maxSessions: Int = StreamDefaults.WHEP_MAX_VIEWERS,
) {

    sealed class Handshake {
        /** The offer was answered: the session resource id and the answer SDP. */
        data class Created(val sessionId: String, val answerSdp: String) : Handshake()

        /** The offer was refused with an HTTP status and a readable reason. */
        data class Rejected(val statusCode: Int, val message: String) : Handshake()
    }

    private val registry = WhepSessionRegistry()
    private val sessions = ConcurrentHashMap<String, WhepSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reapLoopStarted = AtomicBoolean(false)
    private val random = SecureRandom()

    @Volatile private var targetFps = StreamDefaults.STREAM_FPS
    private val frameThrottle = FrameThrottle(
        intervalMs = { FrameTiming.frameIntervalMs(targetFps) },
        tolerance = FrameThrottle.TOLERANCE,
        updateClockOnReject = true,
    )

    /** The shared libwebrtc environment, built lazily and only rebuilt while no session holds it. */
    @Volatile private var environment: PeerEnvironment? = null

    // ── lifecycle (rides the web transport via the Streaming Manager) ──

    /** Arms the reap loop; idempotent. Also called lazily by [handleOffer]. */
    fun start() {
        if (!reapLoopStarted.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                delay(REAP_INTERVAL_MS)
                runCatching { reapDeadSessions() }
            }
        }
    }

    /** Tears every session down and releases the shared environment (the web transport is going away). */
    fun stop() {
        sessions.keys.toList().forEach { teardown(it) }
        synchronized(this) {
            environment?.dispose()
            environment = null
        }
        registry.clear()
        Log.i(TAG, "WHEP endpoint stopped")
    }

    // ── the manager's fan-ins ──

    /** One camera NV21 analysis-tap frame, fanned to the shared video source (WHIP's frame feed twin). */
    fun feedVideoFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int) {
        val env = environment ?: return
        if (sessions.isEmpty()) return
        if (!frameThrottle.accept(System.currentTimeMillis())) return
        val upright: ByteArray
        val upWidth: Int
        val upHeight: Int
        when (rotation) {
            90, 270 -> {
                upright = YuvConverter.rotateNv21(nv21, width, height, rotation)
                upWidth = height
                upHeight = width
            }
            180 -> {
                upright = YuvConverter.rotateNv21(nv21, width, height, rotation)
                upWidth = width
                upHeight = height
            }
            else -> {
                upright = nv21.copyOf()
                upWidth = width
                upHeight = height
            }
        }
        val frame = VideoFrame(NV21Buffer(upright, upWidth, upHeight, null), 0, System.nanoTime())
        env.videoSource.capturerObserver.onFrameCaptured(frame)
    }

    /** Live target-fps change: the frame throttle's interval moves immediately. */
    fun setFrameRate(fps: Int) {
        targetFps = FrameTiming.effectiveFps(fps)
    }

    /** The live viewer count — the status snapshot's `whepClients` value. */
    fun sessionCount(): Int = sessions.size

    // ── the WHEP handshake ──

    /**
     * Answers a viewer's SDP offer, or refuses with the route's status code:
     * 400 for a body that is not a video-bearing SDP offer, 503 when the
     * viewer cap is reached, 502 when libwebrtc failed to build the answer.
     * The whole ladder runs bounded (SDP steps + gather cap), so the HTTP
     * worker thread is never held indefinitely.
     */
    fun handleOffer(offerSdp: String): Handshake {
        start() // the reap loop rides with the first session if the transport skipped it
        val offer = WhepSdp.parseOffer(offerSdp)
            ?: return Handshake.Rejected(400, "Body is not a valid SDP offer with a video media section")
        if (!registry.canAdmit(maxSessions)) {
            return Handshake.Rejected(503, "Too many WHEP viewers (limit $maxSessions)")
        }
        val sessionId = newSessionId()
        return try {
            val session = createSession(sessionId, offerSdp)
            registry.admit(sessionId, System.currentTimeMillis())
            sessions[sessionId] = session
            Log.i(
                TAG,
                "WHEP session $sessionId created (${sessions.size}/$maxSessions, " +
                    "audio ${if (session.audioEnabled) "on" else "off"})",
            )
            Handshake.Created(sessionId, session.answerSdp)
        } catch (e: WebrtcSdpException) {
            Log.w(TAG, "WHEP offer rejected: ${e.message}")
            Handshake.Rejected(400, "Offer rejected: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "WHEP session setup failed: ${e.message}")
            Handshake.Rejected(502, "WHEP session setup failed: ${e.message ?: "unknown error"}")
        }
    }

    /** Tears one session down (the viewer's DELETE, a reap verdict, or endpoint stop). True when it existed. */
    fun teardown(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return registry.remove(sessionId)
        registry.remove(sessionId)
        session.dispose()
        Log.d(TAG, "WHEP session $sessionId torn down (${sessions.size} left)")
        return true
    }

    private fun createSession(sessionId: String, offerSdp: String): WhepSession {
        WebRtcPlumbing.ensureFactoryInitialized(context)
        val env = ensureEnvironment(audioAllowed())
        return WhepSession(sessionId, env, offerSdp)
    }

    /**
     * The shared factory/source environment. Rebuilt whenever the audio
     * verdict moved AND no session holds the old one — live sessions keep
     * their environment, so a verdict flip lands on sessions created after
     * the last viewer left (WHIP's per-session arbitration twin).
     */
    private fun ensureEnvironment(audioWanted: Boolean): PeerEnvironment {
        synchronized(this) {
            val current = environment
            if (current != null && (current.audioEnabled == audioWanted || sessions.isNotEmpty())) {
                return current
            }
            current?.dispose()
            val built = buildEnvironment(audioWanted)
            environment = built
            return built
        }
    }

    private fun buildEnvironment(audioWanted: Boolean): PeerEnvironment {
        val egl = EglBase.create()
        var adm: AudioDeviceModule? = null
        var factory: PeerConnectionFactory? = null
        try {
            if (audioWanted) {
                // The dedicated capture (WHIP's rule): 16 kHz mono, no comms
                // audio processing — one-way publish, shared by every viewer.
                adm = JavaAudioDeviceModule.builder(context)
                    .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                    .setSampleRate(StreamDefaults.WHIP_AUDIO_SAMPLE_RATE_HZ)
                    .setUseStereoInput(false)
                    .setUseStereoOutput(false)
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
                    .setEnableVolumeLogger(false)
                    .createAudioDeviceModule()
            }
            val builtFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .apply { adm?.let { setAudioDeviceModule(it) } }
                .createPeerConnectionFactory()
            factory = builtFactory
            val audioSource = if (adm != null) builtFactory.createAudioSource(MediaConstraints()) else null
            return PeerEnvironment(
                egl = egl,
                factory = builtFactory,
                adm = adm,
                audioEnabled = audioWanted,
                audioSource = audioSource,
                videoSource = builtFactory.createVideoSource(false),
            )
        } catch (e: Exception) {
            runCatching { factory?.dispose() }
            runCatching { adm?.release() }
            runCatching { egl.release() }
            throw e
        }
    }

    private fun reapDeadSessions() {
        val now = System.currentTimeMillis()
        // ICE-observed deaths first (FAILED/CLOSED tear down eagerly via the
        // session's observer; this catch-all covers any missed state), then
        // the registry's time-based verdicts.
        sessions.keys.toList().forEach { id ->
            val state = sessions[id]?.iceState()
            if (state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.CLOSED
            ) {
                teardown(id)
            }
        }
        registry.reapIds(now).forEach { id ->
            Log.d(TAG, "WHEP reap: session $id never connected or stayed disconnected")
            teardown(id)
        }
    }

    private fun newSessionId(): String {
        val bytes = ByteArray(SESSION_ID_BYTES)
        random.nextBytes(bytes)
        return bytes.toHexString()
    }

    /**
     * One viewer's session: its own peer connection and hardware encoder
     * over the shared video source (and the shared Opus audio source when
     * audio is allowed). Owns the answer SDP and the ICE-state marks that
     * drive the registry's GC.
     */
    private inner class WhepSession(
        private val id: String,
        private val env: PeerEnvironment,
        offerSdp: String,
    ) {
        val audioEnabled: Boolean
        val answerSdp: String
        private val peerConnection: PeerConnection

        init {
            val config = PeerConnection.RTCConfiguration(WebRtcPlumbing.stunIceServers(stunServer()))
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE

            val observer = GatherObserver(TAG, onIceState = ::onIceState)
            val pc = env.factory.createPeerConnection(config, observer)
                ?: throw IllegalStateException("libwebrtc failed to create the viewer peer connection")
            peerConnection = pc

            val wantedAudio = env.audioEnabled && audioAllowed()
            pc.addTrack(env.factory.createVideoTrack("$id-video", env.videoSource))
            if (wantedAudio) {
                pc.addTrack(env.factory.createAudioTrack("$id-audio", env.audioSource))
            }
            audioEnabled = wantedAudio

            // The negotiated answer: offer in → answer out, one-shot gather, candidates inline.
            WebRtcPlumbing.awaitSetDescription(
                pc,
                local = false,
                description = SessionDescription(SessionDescription.Type.OFFER, offerSdp),
                label = "WHEP",
            )
            val answer = WebRtcPlumbing.awaitCreateAnswer(pc, "WHEP")
            WebRtcPlumbing.awaitSetDescription(pc, local = true, description = answer, label = "WHEP")
            val gathered = observer.awaitGatheringComplete(WebRtcPlumbing.GATHER_TIMEOUT_MS)
            answerSdp = WhipOfferBuilder.injectCandidates(
                pc.localDescription.description,
                gathered.map { "a=${it.sdp}" },
            )
        }

        /** The registry marks that drive the dead-session GC. */
        private fun onIceState(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> registry.markConnected(id, System.currentTimeMillis())
                PeerConnection.IceConnectionState.DISCONNECTED ->
                    registry.markDisconnected(id, System.currentTimeMillis())
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.CLOSED,
                // Never tear down from inside libwebrtc's callback thread.
                -> scope.launch { teardown(id) }
                else -> Unit
            }
        }

        fun iceState(): PeerConnection.IceConnectionState = try {
            peerConnection.iceConnectionState()
        } catch (_: Exception) {
            PeerConnection.IceConnectionState.CLOSED
        }

        fun dispose() {
            runCatching { peerConnection.close() }
            runCatching { peerConnection.dispose() }
        }
    }

    /** The shared libwebrtc pieces every viewer's session is built on. */
    private class PeerEnvironment(
        val egl: EglBase,
        val factory: PeerConnectionFactory,
        val adm: AudioDeviceModule?,
        val audioEnabled: Boolean,
        val audioSource: AudioSource?,
        val videoSource: VideoSource,
    ) {
        fun dispose() {
            runCatching { audioSource?.dispose() }
            runCatching { factory.dispose() }
            runCatching { adm?.release() }
            runCatching { egl.release() }
        }
    }

    companion object {
        private const val TAG = "WhepServer"

        /** The offer route on the main HTTP server. */
        const val OFFER_PATH = "/whep"

        private const val SESSION_ID_BYTES = 16

        /** The reap loop's cadence — comfortably under the connect-reap window. */
        private const val REAP_INTERVAL_MS = 2_000L
    }
}
