package com.raulshma.lenscast.streaming.whip

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.YuvConverter
import com.raulshma.lenscast.streaming.FrameThrottle
import com.raulshma.lenscast.streaming.FrameTiming
import com.raulshma.lenscast.streaming.webrtc.GatherObserver
import com.raulshma.lenscast.streaming.webrtc.WebRtcPlumbing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The lifecycle surface [WhipOutput] drives; [WhipPublisher] implements it in
 * production, JVM tests substitute a fake (the
 * [com.raulshma.lenscast.streaming.rtmp.RtmpPublisherHandle] pattern). The
 * whole libwebrtc path is device-only — PeerConnectionFactory loads the
 * native library, so it can never run under a JVM unit test — which is why
 * the factory construction sits behind this seam: the output's lifecycle and
 * status mirror are JVM-tested with a fake, and the real factory path is
 * device-verified only.
 */
internal interface WhipPublisherHandle {
    /** Starts the supervised offer/answer/reconnect loop; idempotent. */
    fun start()

    /** Clean teardown: DELETE the session resource, close the peer connection; idempotent. */
    fun stop()

    /** One NV21 analysis-tap frame from the camera. No-op while the video source is not up. */
    fun feedVideoFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int)

    /** Live target-fps change: the frame throttle's interval moves immediately. */
    fun setFrameRate(fps: Int)

    /** The current lifecycle state — the status row / status snapshot value. */
    fun status(): WhipStatus
}

/**
 * The WHIP publisher — the libwebrtc half of the push output (RFC 9725),
 * built on Stream's maintained libwebrtc (`org.webrtc` package).
 *
 * **The software-path decision, stated:** the video source is the camera's
 * NV21 analysis tap — the same frames pro tools and motion detection
 * consume — converted to I420 and fed into a libwebrtc
 * [VideoSource][PeerConnectionFactory.createVideoSource]; the encoding is
 * libwebrtc's own, through its hardware H.264 encoder
 * ([DefaultVideoEncoderFactory]). The pre-encoded MediaCodec access units of
 * the RTSP/RTMP pipeline are deliberately NOT fed to libwebrtc: its
 * encoded-source path (packets-to-depacketized-frames) is fragile and
 * effectively unsupported; the I420 tap is the supported integration. That
 * costs one extra encode when RTSP/HLS/RTMP run concurrently, and buys a
 * WHIP output that is independent of the RTSP codec setting — the RTMP-only
 * H.265 refusal does not apply here.
 *
 * Video frames arrive at the tap's native resolution and are never rescaled;
 * they are throttled to the stream's target fps ([setFrameRate]) and
 * rotated to upright with the same [YuvConverter.rotateNv21] the encoded
 * hub applies, so the wire always carries rotation-0 frames.
 *
 * **Audio arbitration:** the publisher owns a dedicated AudioRecord (through
 * libwebrtc's [JavaAudioDeviceModule], 16 kHz mono, Opus at libwebrtc's
 * default ~32 kbps mono rate) rather than tapping the shared mic capture —
 * the shared capture feeds AAC subscribers and cannot produce an Opus track.
 * The manager decides whether the mic is free at publisher-construction time
 * (stream-audio toggle on, no recording capture, no eco idle, and the shared
 * live capture — talkback/RTSP/RTMP audio — not already running); when it is
 * not, the publisher runs video-only. The verdict is per-session: it is
 * re-evaluated on every (re)connect through the output's restart path.
 *
 * **Transport:** signaling is HTTPS/HTTP with the system + user trust stores
 * only ([HttpsWhipHttpClient]) — LensCast has no client-side insecure-trust
 * helper, so there is no self-signed opt-in. One-shot ICE: after
 * setLocalDescription the publisher waits for gathering completion (capped
 * at [ICE_GATHER_TIMEOUT_MS]) and POSTs the offer with the gathered
 * candidates inline; a blank STUN setting means no iceServers at all — host
 * candidates only, i.e. LAN-only reachability.
 *
 * One supervised coroutine on [Dispatchers.IO] runs the whole ladder per
 * attempt (factory → peer connection → offer → gather → POST → answer),
 * then holds the session open, watching the ICE state; a failure or a
 * disconnect restarts the ladder under a capped backoff while the output is
 * wanted. Stop DELETEs the session resource before the pieces are disposed.
 */
internal class WhipPublisher(
    private val context: Context,
    private val url: WhipUrl,
    private val token: String?,
    private val stunServer: String?,
    /** The mic-arbitration verdict the manager computed for this session. */
    private val audioAllowed: Boolean,
    private val onStatus: (WhipStatus) -> Unit,
    private val httpClient: WhipHttpClient = HttpsWhipHttpClient(),
) : WhipPublisherHandle {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wanted = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile private var targetFps = StreamDefaults.STREAM_FPS
    private val frameThrottle = FrameThrottle(
        intervalMs = { FrameTiming.frameIntervalMs(targetFps) },
        tolerance = FrameThrottle.TOLERANCE,
        updateClockOnReject = true,
    )

    // The session's live collaborators, owned by the attempt coroutine and
    // only read elsewhere through volatile references (the frame feed).
    @Volatile private var videoSource: VideoSource? = null

    @Volatile private var statusValue: WhipStatus = WhipStatus.Idle

    override fun status(): WhipStatus = statusValue

    private fun publishStatus(status: WhipStatus) {
        statusValue = status
        onStatus(status)
    }

    // ── lifecycle ──

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        wanted.set(true)
        val session = scope.launch {
            var attempt = 0
            while (wanted.get()) {
                publishStatus(WhipStatus.Connecting)
                try {
                    runAttempt()
                    attempt = 0 // a session that ended cleanly starts over without penalty
                } catch (e: Exception) {
                    if (!wanted.get()) break
                    publishStatus(WhipStatus.Error(e.message ?: "WHIP session failed"))
                    Log.w(TAG, "WHIP attempt to ${url.hostAndPort} failed: ${e.message}")
                }
                if (!wanted.get()) break
                delay(backoffMs(attempt))
                attempt += 1
            }
            publishStatus(WhipStatus.Idle)
        }
        session.invokeOnCompletion { scope.cancel() }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        wanted.set(false)
        videoSource = null
        // The attempt loop's watch beat notices wanted=false within
        // SESSION_WATCH_INTERVAL_MS and runs the teardown (DELETE + dispose)
        // on its own coroutine — no force-close needed, unlike the RTMP
        // socket, because every WHIP step is deadline-bounded.
    }

    // ── the analysis tap's frame feed (camera thread) ──

    override fun feedVideoFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int) {
        if (!wanted.get()) return
        val source = videoSource ?: return
        if (!frameThrottle.accept(System.currentTimeMillis())) return

        // Rotate to upright (the encoded hub's rule), then hand libwebrtc a
        // private copy at the tap's native size — never rescaled. The frame
        // is not released here: NV21Buffer holds a Java reference and the
        // native source drains it synchronously inside onFrameCaptured; GC
        // reclaims the array (bounded by the fps throttle).
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
        source.capturerObserver.onFrameCaptured(frame)
    }

    override fun setFrameRate(fps: Int) {
        targetFps = FrameTiming.effectiveFps(fps)
    }

    // ── one session attempt: factory → PC → offer → answer → hold ──

    private suspend fun runAttempt() {
        WebRtcPlumbing.ensureFactoryInitialized(context)
        val egl = EglBase.create()
        var adm: AudioDeviceModule? = null
        var factory: PeerConnectionFactory? = null
        var peerConnection: PeerConnection? = null
        var localVideoSource: VideoSource? = null
        var audioSource: org.webrtc.AudioSource? = null
        var resourceUrl: String? = null
        try {
            if (audioAllowed) {
                // The dedicated capture: 16 kHz mono, no comms audio processing
                // (one-way publish), started/stopped with this factory's lifetime.
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

            val config = PeerConnection.RTCConfiguration(WebRtcPlumbing.stunIceServers(stunServer))
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE

            val observer = GatherObserver(TAG)
            val pc = builtFactory.createPeerConnection(config, observer)
                ?: throw WhipSignalingException("libwebrtc failed to create the peer connection")
            peerConnection = pc

            val source = builtFactory.createVideoSource(false)
            localVideoSource = source
            videoSource = source
            pc.addTrack(builtFactory.createVideoTrack(VIDEO_TRACK_ID, source))
            if (audioAllowed) {
                val source2 = builtFactory.createAudioSource(MediaConstraints())
                audioSource = source2
                pc.addTrack(builtFactory.createAudioTrack(AUDIO_TRACK_ID, source2))
            } else {
                Log.i(TAG, "WHIP runs video-only: the microphone is claimed by another output")
            }

            // Offer → local description → one-shot candidate gather (capped).
            val offer = WebRtcPlumbing.awaitCreateOffer(pc, "WHIP")
            WebRtcPlumbing.awaitSetDescription(pc, local = true, description = offer, label = "WHIP")
            val gathered = observer.awaitGatheringComplete(WebRtcPlumbing.GATHER_TIMEOUT_MS)
            val offerBody = WhipOfferBuilder.injectCandidates(
                pc.localDescription.description,
                gathered.map { "a=${it.sdp}" },
            )

            // POST the offer; 201 + Location + answer SDP come back.
            val response = httpClient.execute(WhipSignaling.offerRequest(url, token, offerBody))
            val answer = WhipSignaling.parseOfferResponse(response, url.resourceUrl)
            resourceUrl = answer.resourceUrl
            awaitSetRemoteAnswer(pc, answer.answerSdp)

            publishStatus(WhipStatus.Connected)
            Log.i(
                TAG,
                "WHIP session accepted by ${url.hostAndPort}${url.resourcePath} " +
                    "(audio ${if (audioAllowed) "on" else "off"})",
            )

            // Hold the session: a dead ICE answers within one watch beat.
            while (wanted.get()) {
                when (pc.iceConnectionState()) {
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED,
                    -> throw WhipSignalingException("WHIP ICE connection ${pc.iceConnectionState()}".lowercase())
                    PeerConnection.IceConnectionState.DISCONNECTED -> Unit // transient; FAILED decides
                    else -> Unit
                }
                delay(SESSION_WATCH_INTERVAL_MS)
            }
            // Clean stop path: DELETE the resource while the session lives.
            resourceUrl?.let { resource ->
                runCatching { deleteSession(resource) }.onFailure {
                    Log.w(TAG, "WHIP resource DELETE failed: ${it.message}")
                }
            }
        } finally {
            videoSource = null
            resourceUrl?.let { resource ->
                if (wanted.get()) { // a failure path — the session is being abandoned
                    runCatching { deleteSession(resource) }
                }
            }
            runCatching { peerConnection?.close() }
            runCatching { peerConnection?.dispose() }
            runCatching { localVideoSource?.dispose() }
            runCatching { audioSource?.dispose() }
            runCatching { factory?.dispose() }
            runCatching { adm?.release() }
            runCatching { egl.release() }
        }
    }

    private fun deleteSession(resourceUrl: String) {
        val request = WhipSignaling.deleteRequest(resourceUrl, token, url.username, url.password)
        val response = httpClient.execute(request)
        if (!WhipSignaling.isDeleteSettled(response.statusCode)) {
            Log.w(TAG, "WHIP DELETE answered ${response.statusCode}")
        }
    }

    /** The remote answer's bounded set — the last SDP step of the ladder. */
    private fun awaitSetRemoteAnswer(pc: PeerConnection, answerSdp: String) {
        WebRtcPlumbing.awaitSetDescription(
            pc,
            local = false,
            description = SessionDescription(SessionDescription.Type.ANSWER, answerSdp),
            label = "WHIP",
        )
    }

    private fun backoffMs(attempt: Int): Long {
        var ms = INITIAL_BACKOFF_MS
        repeat(attempt.coerceAtLeast(0)) {
            ms = (ms * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        return ms
    }

    private companion object {
        private const val TAG = "WhipPublisher"

        private const val VIDEO_TRACK_ID = "lenscast-video"
        private const val AUDIO_TRACK_ID = "lenscast-audio"

        private const val SESSION_WATCH_INTERVAL_MS = 1_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}

/**
 * The default signaling transport: `HttpsURLConnection` over the platform's
 * SSL socket factory — system CAs plus any user-installed credential. This is
 * the WHIP layer's whole TLS story: LensCast has no client-side insecure-trust
 * helper to reuse, so self-signed WHIP endpoints are simply unsupported
 * (documented in the settings UI as "system trust only").
 */
class HttpsWhipHttpClient : WhipHttpClient {

    override fun execute(request: WhipHttpRequest): WhipHttpResponse {
        val connection = (java.net.URI(request.url).toURL().openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            request.body?.let { doOutput = true }
        }
        try {
            request.body?.let { bytes ->
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val headers = mutableMapOf<String, String>()
            connection.headerFields.forEach { (name, values) ->
                if (!name.isNullOrBlank() && !values.isNullOrEmpty()) headers[name.lowercase()] = values.first()
            }
            val body = (if (code in 200..399) connection.inputStream else connection.errorStream)?.use {
                it.readBytes()
            } ?: ByteArray(0)
            return WhipHttpResponse(code, headers, body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
