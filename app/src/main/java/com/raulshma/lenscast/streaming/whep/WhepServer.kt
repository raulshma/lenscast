package com.raulshma.lenscast.streaming.whep

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.YuvConverter
import com.raulshma.lenscast.core.toHexString
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.streaming.EncodedOverlayCompositor
import com.raulshma.lenscast.streaming.FramePacer
import com.raulshma.lenscast.streaming.FrameTiming
import com.raulshma.lenscast.streaming.whip.WhipOfferBuilder
import com.raulshma.lenscast.streaming.webrtc.GatherObserver
import com.raulshma.lenscast.streaming.webrtc.WebRtcPlumbing
import com.raulshma.lenscast.streaming.webrtc.WebrtcSdpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.NV21Buffer
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The WHEP viewer endpoint (RFC 9725's egress twin): browsers POST an SDP
 * offer to `/whep` and watch the live camera sub-second over WebRTC — the
 * viewing half of the WHIP push output's publishing half.
 *
 * **Media:** every viewer gets its own [PeerConnection] with its own
 * libwebrtc encoder (the same [DefaultVideoEncoderFactory]
 * WHIP uses), fed from ONE shared
 * [VideoSource] that taps the same NV21 analysis frames the WHIP publisher
 * consumes — the manager fans every camera frame in through
 * [feedVideoFrame], throttled to the stream's target fps, rotated upright
 * exactly like WHIP, and stamped with the configured burned-in overlay
 * ([EncodedOverlayCompositor]) so viewers see the same timestamp/branding as
 * every other output. The tap is the supported libwebrtc
 * integration; the encoded-hub access units are deliberately not fed here
 * (see the WHIP publisher's KDoc for that decision). libwebrtc encodes its
 * own video, independent of the RTSP codec setting — the wire codec is
 * whatever it negotiates per viewer (VP8 or H.264, device-dependent);
 * H.265 is not offered.
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
 * reap, auth, the stats report reading, the rate accounting and the stats
 * poll protocol) are the pure modules next to it, JVM-tested.
 *
 * **Stats (issue #25):** this lane never crosses the encoded-stream hub, so
 * its measured bitrate/fps come from libwebrtc itself. Each tick of the reap
 * loop asks every live session's peer connection for a stats report
 * ([PeerConnection.getStats], every [REAP_INTERVAL_MS]); the report's video
 * send row is read by the pure [WhepStatsReportParser] and its cumulative
 * counters are turned into a rate by [WhepStreamStats].
 * [whepStreamStatus] publishes it for /api/status's `whepStream` block.
 *
 * **Threading of that poll.** In the bundled stream-webrtc-android 1.3.10
 * (read off its bytecode and native library, not assumed) `getStats` is a
 * direct native call on the CALLING thread: no hop into libwebrtc's
 * signaling thread, and no disposed guard. Upstream libwebrtc wants that
 * call on the signaling thread (a debug-only check, compiled out of this
 * release build) and delivers the report there. This app's factory owns no
 * handle to that thread (libwebrtc creates it natively), so the endpoint
 * cannot post to it and issues the poll from its loop thread instead —
 * exactly as the handshake above issues its SDP verbs from the HTTP worker
 * thread. What the endpoint keeps safe is its own side, per session
 * ([WhepSession.pollStats] and [WhepSession.dispose] share one lock):
 *
 *  - never on a disposed peer connection — `dispose()` frees the native
 *    peer connection and leaves the Java pointer dangling, so a poll racing
 *    a teardown would call into freed memory;
 *  - one request owed at a time, with a quiet gap after every report —
 *    [WhepStatsPollGuard]'s two rules, so the app never touches a session's
 *    stats collector while libwebrtc's signaling thread is in it.
 *
 * The report callback does no libwebrtc call of its own, and never throws:
 * an exception escaping a native callback takes the process down.
 */
class WhepServer internal constructor(
    private val context: Context,
    /** The STUN setting provider — the manager hands over the WHIP STUN value. */
    private val stunServer: () -> String?,
    /** The per-session mic-arbitration verdict (WHIP's rule, evaluated at offer time). */
    private val audioAllowed: () -> Boolean,
    /** The concurrent-viewer cap (the RTSP server's max-4 convention). */
    private val maxSessions: Int,
    /** The burned-in overlay, stamped into the NV21 tap like every other output. */
    private val overlayCompositor: EncodedOverlayCompositor,
) {

    constructor(
        context: Context,
        stunServer: () -> String?,
        audioAllowed: () -> Boolean,
        maxSessions: Int = StreamDefaults.WHEP_MAX_VIEWERS,
        illuminance: () -> Int? = { null },
    ) : this(context, stunServer, audioAllowed, maxSessions, EncodedOverlayCompositor(illuminanceProvider = illuminance))

    sealed class Handshake {
        /** The offer was answered: the session resource id and the answer SDP. */
        data class Created(val sessionId: String, val answerSdp: String) : Handshake()

        /** The offer was refused with an HTTP status and a readable reason. */
        data class Rejected(val statusCode: Int, val message: String) : Handshake()
    }

    private val registry = WhepSessionRegistry()
    private val sessions = ConcurrentHashMap<String, WhepSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The lane's measured-stream stats (issue #25). Its clock is the
     * monotonic one: a rate is a difference between two readings, and the
     * wall clock can be stepped under it.
     */
    private val streamStats = WhepStreamStats(pollIntervalMs = REAP_INTERVAL_MS, nowMs = SystemClock::elapsedRealtime)

    /** The reap loop's handle — the idempotence and teardown seam for [start]/[stop]. */
    @Volatile private var reapJob: Job? = null
    private val random = SecureRandom()

    @Volatile private var targetFps = StreamDefaults.STREAM_FPS
    private val framePacer = FramePacer(
        intervalNs = { FrameTiming.frameIntervalNs(targetFps) },
    )

    /** The shared libwebrtc environment, built lazily and only rebuilt while no session holds it. */
    @Volatile private var environment: PeerEnvironment? = null

    // ── lifecycle (rides the web transport via the Streaming Manager) ──

    /**
     * Arms the reap loop; idempotent while the loop lives. The loop is held
     * as a [Job], not a fire-once flag: a start after a stop relaunches a
     * fresh loop (the flag this replaces never reset, so a start-after-stop
     * skipped the relaunch and dead sessions could hold encoder slots).
     * Also called lazily by [handleOffer].
     *
     * Each tick reaps, then polls the live sessions' stats — the reap first,
     * so a session going out on this tick is not asked for a report (one
     * torn down concurrently is skipped under its own lock).
     */
    fun start() {
        if (reapJob?.isActive == true) return
        reapJob = scope.launch {
            while (isActive) {
                delay(REAP_INTERVAL_MS)
                runCatching { reapDeadSessions() }
                runCatching { pollStreamStats() }
                    .onFailure { Log.w(TAG, "WHEP stats poll failed: $it") }
            }
        }
    }

    /**
     * Tears every session down, cancels the reap loop, and releases the
     * shared environment (the web transport is going away).
     */
    fun stop() {
        reapJob?.cancel()
        reapJob = null
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
    fun feedVideoFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int, timestampNs: Long = 0L) {
        val env = environment ?: return
        if (sessions.isEmpty()) return
        val sampleNs = if (timestampNs > 0L) timestampNs else System.nanoTime()
        if (!framePacer.accept(sampleNs)) return
        val tap = prepareTapFrame(nv21, width, height, rotation, overlayCompositor, System.currentTimeMillis())
        val frame = VideoFrame(NV21Buffer(tap.nv21, tap.width, tap.height, null), 0, sampleNs)
        env.videoSource.capturerObserver.onFrameCaptured(frame)
    }

    /** Live target-fps change: the frame throttle's interval moves immediately. */
    fun setFrameRate(fps: Int) {
        targetFps = FrameTiming.effectiveFps(fps)
    }

    /** The Settings Applier's overlay route in — the next tapped frame re-renders. */
    fun setOverlaySettings(settings: OverlaySettings) {
        overlayCompositor.setOverlaySettings(settings)
    }

    /** The live viewer count — the status snapshot's `whepClients` value. */
    fun sessionCount(): Int = sessions.size

    /**
     * The WHEP lane's measured-stream status (issue #25): the preview's own
     * bitrate, frame rate, codec and encoded frame size, summed over the
     * live viewers. Null until a viewer's first stats report — the caller
     * omits the wire block then, exactly while `encodedStream` is absent.
     */
    fun whepStreamStatus(): WhepStreamStats.Snapshot? = streamStats.snapshot()

    // ── the measured-stream stats poll ──

    /**
     * One tick of the poll: asks every live session for a stats report, in
     * the order the map hands them out. A session that is owed a report, or
     * still inside its quiet gap, is left alone ([WhepStatsPollGuard]); one
     * torn down concurrently is skipped under its own lock. No viewers, no
     * work: the map read is the whole cost.
     */
    private fun pollStreamStats() {
        if (sessions.isEmpty()) return
        val nowMs = SystemClock.elapsedRealtime()
        for (session in sessions.values) {
            try {
                session.pollStats(nowMs)
            } catch (e: Exception) {
                // The loop must outlive one bad session; its reports are the only thing lost.
                Log.w(TAG, "WHEP stats ${session.id}: poll failed: $e")
            }
        }
        // One line per tick for the lane's reading — what a device log is
        // read against when the dashboard disagrees. It is the PREVIOUS
        // tick's reports (the ones just asked for land milliseconds later).
        Log.d(TAG, "WHEP stream: ${sessions.size} viewer(s), ${describe(streamStats.snapshot())}")
    }

    /**
     * The report callback's whole body, for one session. Runs on whatever
     * thread libwebrtc delivers on (its signaling thread) and must not
     * throw; it makes no libwebrtc call.
     */
    private fun onStatsReport(session: WhepSession, report: RTCStatsReport) {
        try {
            val rows = report.statsMap.values.map { WhepStatRow(it.type, it.id, it.members) }
            if (session.markStatsShapeLogged()) {
                // Once per session: what this libwebrtc build actually
                // reports — the row types, and every RTP send row's media
                // and member names — and where from, in case the parser's
                // reading of it is ever wrong or the delivery thread is not
                // the expected one. Member names only; the video row's
                // values are on the line every report logs.
                val census = rows.groupingBy { it.type }.eachCount().entries.sortedBy { it.key }
                val sends = rows.filter { it.type == WhepStatsReportParser.TYPE_OUTBOUND_RTP }.joinToString { row ->
                    "${row.id}[${row.members["kind"] ?: "?"}]: ${row.members.keys.sorted()}"
                }
                Log.d(
                    TAG,
                    "WHEP stats ${session.id}: first report on ${Thread.currentThread().name}: " +
                        "timestampUs=${report.timestampUs} rows=${rows.size} types=$census sends={$sends}",
                )
            }
            val video = WhepStatsReportParser.videoOutbound(rows)
            if (video == null) {
                Log.d(TAG, "WHEP stats ${session.id}: no video outbound-rtp row in ${rows.size} rows")
                return
            }
            val accounted = streamStats.onStats(
                session.id,
                SystemClock.elapsedRealtime(),
                video.bytesSent,
                video.framesEncoded,
                video.codec,
                video.width,
                video.height,
            )
            val delta = accounted?.let { "+${it.bytes} B / ${it.frames} frames over ${it.intervalMs} ms" } ?: "baseline"
            Log.d(TAG, "WHEP stats ${session.id}: $delta -> ${describe(video)}")
        } catch (e: Exception) {
            Log.w(TAG, "WHEP stats ${session.id}: report dropped: $e")
        } finally {
            // Stamped last: the quiet gap starts when this callback is done.
            session.onStatsDelivered(SystemClock.elapsedRealtime())
        }
    }

    private fun describe(video: WhepVideoOutbound): String =
        "${video.bytesSent} B, ${video.framesEncoded} frames, ${video.codec ?: "?"}, " +
            "${video.width ?: "?"}x${video.height ?: "?"}"

    /** The lane's reading for the device log; "no measurement" until a viewer has reported. */
    private fun describe(snapshot: WhepStreamStats.Snapshot?): String {
        val measured = snapshot ?: return "no measurement"
        return "${measured.measuredBitrateKbps} kbps, ${"%.1f".format(Locale.ROOT, measured.fps)} fps, " +
            "${measured.codec ?: "?"}, ${measured.width ?: "?"}x${measured.height ?: "?"}"
    }

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
            // The lane's stats only ever count sessions the endpoint knows
            // (see [teardown] for why that is the rule).
            streamStats.onSessionAdmitted(sessionId)
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

    /**
     * Tears one session down (the viewer's DELETE, a reap verdict, or endpoint stop). True when it existed.
     *
     * The lane's stats forget the session BEFORE its peer connection goes:
     * a stats report still in flight for it then names an id nobody knows
     * and is dropped — it cannot resurrect the session as a silent viewer
     * that never leaves.
     */
    fun teardown(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return registry.remove(sessionId)
        registry.remove(sessionId)
        streamStats.onSessionRemoved(sessionId)
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
     * drive the registry's GC, and its own share of the lane's stats poll.
     */
    private inner class WhepSession(
        /** The session resource id — also the lane stats' key for this viewer. */
        val id: String,
        private val env: PeerEnvironment,
        offerSdp: String,
    ) {
        val audioEnabled: Boolean
        val answerSdp: String
        private val peerConnection: PeerConnection

        /**
         * The one lock the threads that touch this session's peer connection
         * after the handshake share — the stats poll and the teardown — so
         * a poll is never issued on a disposed connection. The report
         * callback never takes it: it would hold libwebrtc's signaling thread
         * behind a teardown.
         */
        private val lifecycle = Any()

        /** Set under [lifecycle] by [dispose]; read under it by [pollStats]. */
        private var disposed = false

        /** When this session may be asked for its next stats report. */
        private val statsPoll = WhepStatsPollGuard()

        private val statsShapeLogged = AtomicBoolean(false)

        private val statsCallback = RTCStatsCollectorCallback { report -> onStatsReport(this@WhepSession, report) }

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

        /**
         * One tick's ask for this session's stats report (the class doc has
         * the threading): skipped once disposed, and left to a later tick
         * while a report is owed or the last one's quiet gap has not passed.
         */
        fun pollStats(nowMs: Long) {
            synchronized(lifecycle) {
                if (disposed) return
                when (statsPoll.arm(nowMs)) {
                    WhepStatsPollGuard.Verdict.WAIT -> Unit
                    WhepStatsPollGuard.Verdict.REISSUE -> {
                        Log.w(TAG, "WHEP stats $id: no report for over ${WhepStatsPollGuard.STALE_MS} ms; asking again")
                        requestStats()
                    }
                    WhepStatsPollGuard.Verdict.ISSUE -> requestStats()
                }
            }
        }

        private fun requestStats() {
            try {
                peerConnection.getStats(statsCallback)
            } catch (e: Exception) {
                // Nothing is owed now; a later tick asks again.
                statsPoll.onFailed()
                Log.w(TAG, "WHEP stats $id: getStats failed: $e")
            }
        }

        /** The report callback's last act, on its own thread: nothing is owed from here, and the quiet gap starts. */
        fun onStatsDelivered(nowMs: Long) {
            statsPoll.onDelivered(nowMs)
        }

        /** Once per session: true for the first report, which gets its shape logged. */
        fun markStatsShapeLogged(): Boolean = statsShapeLogged.compareAndSet(false, true)

        fun dispose() {
            synchronized(lifecycle) {
                disposed = true
                runCatching { peerConnection.close() }
                runCatching { peerConnection.dispose() }
            }
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

        /**
         * The reap loop's cadence — comfortably under the connect-reap
         * window. The stats poll rides the same tick, so it is also the
         * lane's poll cadence ([streamStats] holds the not-yet-polled tail
         * for exactly one interval).
         */
        private const val REAP_INTERVAL_MS = 2_000L

        /**
         * The tap's frame prep: rotate upright and stamp the burned-in
         * overlay. Pure NV21 (no libwebrtc), so the stamp contract is
         * JVM-tested while the transport around it stays device-only.
         */
        internal fun prepareTapFrame(
            nv21: ByteArray,
            width: Int,
            height: Int,
            rotation: Int,
            compositor: EncodedOverlayCompositor,
            nowMs: Long,
        ): TapFrame {
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
            // [upright] is privately owned (a rotation result or an explicit
            // copy), so the overlay blends in place without touching the
            // camera's shared analysis array that the other taps still read.
            val stamped = compositor.applyTo(upright, upWidth, upHeight, nowMs, frameShared = false)
            return TapFrame(stamped, upWidth, upHeight)
        }
    }
}

/** The upright, overlay-stamped NV21 the shared video source consumes. */
internal class TapFrame(val nv21: ByteArray, val width: Int, val height: Int)
