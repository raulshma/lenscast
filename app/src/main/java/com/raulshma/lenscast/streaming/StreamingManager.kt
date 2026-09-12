package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.streaming.rtsp.EncodedNalUnit
import android.content.Context
import android.util.Log
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.core.EcoIdlePolicy
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.SirenPlayer
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.web.ApiRouter
import com.raulshma.lenscast.streaming.web.AuditLog
import com.raulshma.lenscast.streaming.web.AuditWebHandler
import com.raulshma.lenscast.streaming.web.AuthWebHandler
import com.raulshma.lenscast.streaming.web.CaptureWebHandler
import com.raulshma.lenscast.streaming.web.DetectionEventsWebHandler
import com.raulshma.lenscast.streaming.web.DetectionTestWebHandler
import com.raulshma.lenscast.streaming.web.DeterrenceWebHandler
import com.raulshma.lenscast.streaming.web.GalleryWebHandler
import com.raulshma.lenscast.streaming.web.PushWebHandler
import com.raulshma.lenscast.streaming.web.IntervalCaptureWebHandler
import com.raulshma.lenscast.streaming.web.LensWebHandler
import com.raulshma.lenscast.streaming.web.RecordingWebHandler
import com.raulshma.lenscast.streaming.web.SettingsWebHandler
import com.raulshma.lenscast.streaming.web.StatusWebHandler
import com.raulshma.lenscast.streaming.web.StreamWebHandler
import com.raulshma.lenscast.streaming.web.SystemWebHandler
import com.raulshma.lenscast.streaming.hls.HlsManager
import com.raulshma.lenscast.streaming.hls.TsPacketizer
import com.raulshma.lenscast.streaming.rtmp.RtmpOutput
import com.raulshma.lenscast.streaming.rtmp.RtmpPublisher
import com.raulshma.lenscast.streaming.rtmp.RtmpStatus
import com.raulshma.lenscast.streaming.srt.SrtStats
import com.raulshma.lenscast.streaming.srt.SrtOutput
import com.raulshma.lenscast.streaming.srt.SrtPublisher
import com.raulshma.lenscast.streaming.srt.SrtStatus
import com.raulshma.lenscast.streaming.whip.WhipOutput
import com.raulshma.lenscast.streaming.whip.WhipPublisher
import com.raulshma.lenscast.streaming.whip.WhipStatus
import com.raulshma.lenscast.streaming.whep.WhepServer
import com.raulshma.lenscast.streaming.rtsp.RtspAuthSpec
import com.raulshma.lenscast.streaming.rtsp.RtspConfigDiff
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspResolution
import com.raulshma.lenscast.streaming.rtsp.RtspServer
import com.raulshma.lenscast.streaming.rtsp.RtspUriPolicy
import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StreamingManager(
    private val context: Context,
    private val thermalMonitor: ThermalMonitor,
) {

    private val audioStreamingManager = AudioStreamingManager(context)

    // The store-backed auth settings the RTSP spec provider reads live: the
    // Settings Applier saves there and applies through [updateAuthSettings],
    // so no manager-side mirror is retained. Lazy like [buildWebApiStack] —
    // the application object is only cast at use time.
    private val authSettingsStore: SettingsDataStore by lazy {
        (context.applicationContext as MainApplication).settingsDataStore
    }

    // WebSocket sidecar for WebCodecs video + PTT talkback.
    @Volatile private var wsMediaServer: com.raulshma.lenscast.streaming.ws.WsMediaServer? = null
    private val wsVideoSink: (List<EncodedNalUnit>) -> Unit = { nalUnits ->
        wsMediaServer?.feedVideo(nalUnits)
    }

    // The shared H.264/AAC encode pipeline: started whenever any encoded sink
    // is active (RTSP output, RTMP push output, HLS ring, WS video clients —
    // the [EncodedStreamPolicy] verdict), and fanning its encoded access units
    // out to every sink. Its rtsp sink forwards to whatever server instance
    // the RTSP output currently holds, so the output stays a sink consumer —
    // never the only encode trigger.
    private val encodedHub: EncodedStreamHub = EncodedStreamHub(
        policyInputs = ::encodedStreamInputs,
        audio = audioStreamingManager,
        audioConfig = ::audioConfig,
        // Eco idle counts as audio-not-wanted: with the mode dropped in there
        // are no consumers by definition, so the hub stops the AAC encoder.
        audioWanted = { streamAudioEnabled.get() && !recordingAudioCaptureActive && !ecoIdleActive },
        audioBitrateKbps = { streamAudioBitrateKbps.get() },
        rtspSink = object : EncodedSink {
            override fun feedVideo(nalUnits: List<EncodedNalUnit>) {
                rtspOutput.feedEncodedVideo(nalUnits)
            }

            override fun feedAudio(aacData: ByteArray) {
                rtspOutput.feedEncodedAudio(aacData)
            }
        },
        hlsSink = HlsManager,
        wsVideoSink = wsVideoSink,
        rtmpSink = object : EncodedSink {
            override fun feedVideo(nalUnits: List<EncodedNalUnit>) {
                rtmpOutput.feedEncodedVideo(nalUnits)
            }

            override fun feedAudio(aacData: ByteArray) {
                rtmpOutput.feedEncodedAudio(aacData)
            }
        },
        // The SRT push output rides the same encoded-AU tap as the RTMP
        // push (H.264 AUs + AAC, gated off under H.265 at the fan-out).
        srtSink = object : EncodedSink {
            override fun feedVideo(nalUnits: List<EncodedNalUnit>) {
                srtOutput.feedEncodedVideo(nalUnits)
            }

            override fun feedAudio(aacData: ByteArray) {
                srtOutput.feedEncodedAudio(aacData)
            }
        },
        // The low-res sub-stream's fan-out onto the RTSP server's /sub URL.
        subVideoSink = { nalUnits -> rtspOutput.feedEncodedSubVideo(nalUnits) },
    )

    /** The sink-activity snapshot the hub's policy verdicts read. */
    private fun encodedStreamInputs(): EncodedStreamPolicy.Inputs = EncodedStreamPolicy.Inputs(
        webActive = webStreamingActive.get(),
        rtspActive = rtspOutput.isActive(),
        hlsRequested = HlsManager.isHot(),
        wsVideoClients = wsMediaServer?.videoClientCount() ?: 0,
        rtmpActive = rtmpOutput.isActive(),
        srtActive = srtOutput.isActive(),
    )

    /**
     * The active-client count the MQTT telemetry sensor publishes — the same
     * consumer list the eco verdict reads (MJPEG/WS/RTSP clients, HLS
     * fetches, RTMP/WHIP/SRT pushes), so the broker can never see a number
     * that disagrees with the device's own adaptation inputs.
     */
    fun telemetryActiveClientCount(): Int = try {
        liveConsumerCount()
    } catch (_: Exception) {
        0
    }

    // The RTSP output behind this manager's public surface: retained config,
    // server lifecycle, the restart-vs-apply choice, the audio-stream handle,
    // the URL, and the audio-wanted/mic-arbitration decision all live in the
    // deep module; this class keeps the fan-out and the web/mDNS concerns.
    private val rtspOutput: RtspOutput = RtspOutput(
        audio = audioStreamingManager,
        audioConfig = ::audioConfig,
        authSpec = { rtspAuthSpec(authSettingsStore.authSettings.value) },
        releaseAudio = ::releaseRtspOwnedAudio,
        onVideoBitrateChanged = encodedHub::setVideoBitrate,
        onStateChanged = { running, url ->
            _isRtspRunning.value = running
            _rtspUrl.value = url
        },
        // rtsps when HTTPS mode is on — the listener and every advertised URL follow.
        secure = { tlsEnabled },
        serverFactory = { port ->
            RtspServer(
                port = port,
                encodedSource = encodedHub,
                // TLS on/off follows the HTTPS setting: the same self-signed
                // identity, the same 8554 port, clients use rtsps://.
                sslServerSocketFactory = rtspTlsFactory(),
                subStreamActive = { encodedHub.isSubStreamEnabled() },
                encodedSendTap = ::onEncodedSinkSend,
            )
        },
    )

    // The RTMP push output behind this manager's public surface: the retained
    // URL, the enabled gate, the H.264-only validation ladder, and the
    // publisher lifecycle all live in the deep module (the RtspOutput
    // pattern); this class keeps the fan-out and the status mirror. Its sink
    // receives the hub's H.264/AAC access units exactly like RTSP/HLS/WS —
    // gated off under H.265 at the hub's fan-out.
    private val rtmpOutput: RtmpOutput = RtmpOutput(
        source = encodedHub,
        onStatusChanged = { status -> _rtmpStatus.value = status },
        publisherFactory = { url, onStatus -> RtmpPublisher(url, encodedHub, onStatus) },
    )

    // The SRT push output behind this manager's public surface: the RTMP
    // output's twin — retained URL, enabled gate, the H.264-only validation
    // ladder, and the publisher lifecycle all live in the deep module; this
    // class keeps the fan-out and the status mirror. Its sink receives the
    // hub's H.264/AAC access units exactly like RTSP/HLS/WS/RTMP.
    private val srtOutput: SrtOutput = SrtOutput(
        source = encodedHub,
        onStatusChanged = { status -> _srtStatus.value = status },
        publisherFactory = { url, onStatus -> SrtPublisher(url, encodedHub, onStatus) },
    )

    // The WHIP push output behind this manager's public surface: the retained
    // endpoint/token/STUN, the enabled gate, and the publisher lifecycle live
    // in the deep module (the RtmpOutput pattern). Unlike RTMP it does not
    // consume the encoded hub — libwebrtc encodes its own H.264 from the same
    // NV21 analysis tap the manager fans out in [pushFrame] — so its only
    // runtime couplings here are the frame feed, the fps fan-out, the status
    // mirror, and the mic-arbitration verdict evaluated at publisher
    // construction (audio on only when the shared capture is free).
    private val whipOutput: WhipOutput = WhipOutput(
        audioAllowed = ::whipAudioAllowed,
        onStatusChanged = { status -> _whipStatus.value = status },
        publisherFactory = { url, token, stunServer, audioAllowed, onStatus ->
            WhipPublisher(context, url, token, stunServer, audioAllowed, onStatus)
        },
    )

    // The WHEP viewer endpoint (streaming/whep/): the WebRTC egress twin of
    // the WHIP push — browsers POST /whep on the web transport and watch the
    // camera sub-second. Like WHIP it does not consume the encoded hub
    // (libwebrtc encodes its own H.264 from the NV21 analysis tap); its
    // runtime couplings here are the frame feed, the fps fan-out, the
    // transport lifecycle (sessions die with the web server, like the WS
    // sidecar), the WHIP STUN setting reused for ICE, and WHIP's exact
    // mic-arbitration verdict re-evaluated per session at offer time.
    private val whepServer: WhepServer =
        WhepServer(
            context = context,
            stunServer = { whipStunSetting },
            audioAllowed = ::whipAudioAllowed,
        )

    private val serviceDiscoveryManager = ServiceDiscoveryManager(context)
    private val sirenPlayer = SirenPlayer()

    private val webStreamingEnabled = AtomicBoolean(true)
    private val mdnsEnabled = AtomicBoolean(true)
    @Volatile
    private var currentOverlaySettings = OverlaySettings()
    private val networkQualityMonitor = NetworkQualityMonitor()
    private val adaptiveBitrateController = AdaptiveBitrateController(networkQualityMonitor)
    private val qualityPolicy = StreamQualityPolicy(thermalMonitor, adaptiveBitrateController)
    private val framePipeline = FramePipeline(qualityPolicy)
    private val webApiStack: WebApiStack by lazy { buildWebApiStack() }

    // One gate for the app's web server: owned here so sessions survive a
    // server recreation (e.g. a port change) — and, via the file store, an
    // app restart. Declared before the first server — every StreamingServer
    // receives this one shared instance at construction.
    private val webAuthGate = WebAuthGate(sessionPersistence = AuthSessionStore(context))

    private var server: StreamingServer = createServer(StreamDefaults.WEB_PORT)
    private val webStreamingActive = AtomicBoolean(false)
    private val jpegQuality = AtomicInteger(StreamDefaults.JPEG_QUALITY)
    private val streamAudioEnabled = AtomicBoolean(true)
    private val streamAudioBitrateKbps = AtomicInteger(StreamDefaults.AUDIO_BITRATE_KBPS)
    private val streamAudioChannels = AtomicInteger(StreamDefaults.AUDIO_CHANNELS)
    private val streamAudioEchoCancellation = AtomicBoolean(true)
    @Volatile
    private var recordingAudioCaptureActive = false
    private var currentPort: Int = StreamDefaults.WEB_PORT

    // TLS mode: when on, the server socket is secure (self-signed cert owned
    // by the app's TlsCertManager) and every URL the app hands out uses https.
    @Volatile private var tlsEnabled = false
    @Volatile private var tlsFingerprint = ""

    private var lastReportedClientCount = -1

    // Detection-event snapshot source: the latest rendered M-JPEG frame,
    // retained here because StreamingServer owns no latest-frame getter — the
    // one frame DetectionCoordinator can reach without a fresh camera capture.
    @Volatile private var latestWebJpeg: ByteArray? = null

    // ── Eco idle-fps mode (battery-powered idle sessions) ──
    // The persisted toggle arrives through [setEcoIdleFpsEnabled] (the
    // Settings Applier); the verdicts are the pure
    // [com.raulshma.lenscast.core.EcoIdlePolicy] over charging
    // (PowerManager), consumer counts (MJPEG/RTSP/WS clients, HLS fetches,
    // RTMP push), and thermal — evaluated on a short poll plus an immediate
    // re-check whenever the toggle or the user frame rate moves. Eco only
    // applies while thermal is NORMAL: thermal escalation always restores,
    // so the two ladders never stack.

    private val ecoIdleEnabled = AtomicBoolean(false)
    @Volatile private var ecoIdleActive = false
    @Volatile private var userFrameRate = StreamDefaults.STREAM_FPS
    private var ecoIdleState = EcoIdlePolicy.State()
    private val ecoIdleLock = Any()

    // Both periodic monitors (eco idle-fps, encoded adaptive bitrate) share
    // one scope; each poll loop lives only while its toggle is on, so a
    // disabled mode costs no wakeups and no loop outlives its job.
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ecoIdleMonitorJob: Job? = null

    /** The app-scoped PowerManager, resolved lazily like the auth settings store. */
    private val powerDelegate: com.raulshma.lenscast.core.PowerManager by lazy {
        (context.applicationContext as MainApplication).powerManager
    }

    /**
     * The persisted eco toggle. Arms the poll loop once and re-evaluates
     * immediately, so a disable lands on the next tick at the latest.
     */
    fun setEcoIdleFpsEnabled(enabled: Boolean) {
        val changed = ecoIdleEnabled.getAndSet(enabled) != enabled
        if (changed) {
            Log.d(TAG, "Eco idle-fps mode ${if (enabled) "enabled" else "disabled"}")
        }
        if (enabled) {
            startEcoIdleMonitor()
        } else {
            ecoIdleMonitorJob?.cancel()
        }
        evaluateEcoIdle(System.currentTimeMillis())
    }

    private fun startEcoIdleMonitor() {
        if (ecoIdleMonitorJob?.isActive == true) return
        ecoIdleMonitorJob = monitorScope.launch {
            while (isActive) {
                delay(EcoIdlePolicy.EVALUATION_INTERVAL_MS)
                evaluateEcoIdle(System.currentTimeMillis())
            }
        }
    }

    /** One evaluation: policy verdict in, applied side effects out. */
    private fun evaluateEcoIdle(nowMs: Long) {
        synchronized(ecoIdleLock) {
            val decision = EcoIdlePolicy.evaluate(
                enabled = ecoIdleEnabled.get(),
                charging = powerDelegate.isChargingNow(),
                hasConsumers = liveConsumerCount() > 0,
                thermalNormal = thermalMonitor.thermalState.value == ThermalState.NORMAL,
                nowMs = nowMs,
                state = ecoIdleState,
            )
            ecoIdleState = decision.nextState
            when (decision.verdict) {
                EcoIdlePolicy.Verdict.Drop -> {
                    ecoIdleActive = true
                    applyEffectiveFrameRate()
                    // No consumers by definition: pause the live mic capture
                    // and let the hub's audioWanted verdict stop the encoder.
                    if (_isAudioStreaming.value) {
                        audioStreamingManager.stop()
                        clearWebAudioState()
                    }
                    encodedHub.refresh()
                    Log.d(TAG, "Eco idle: dropped to ${EcoIdlePolicy.floorFps(userFrameRate)} fps (audio paused)")
                }
                EcoIdlePolicy.Verdict.Restore -> {
                    if (ecoIdleActive) {
                        ecoIdleActive = false
                        applyEffectiveFrameRate()
                        refreshAudioStreamingState()
                        encodedHub.refresh()
                        Log.d(TAG, "Eco idle: restored ${userFrameRate} fps")
                    }
                }
                EcoIdlePolicy.Verdict.Hold -> Unit
            }
        }
    }

    /**
     * Every live stream consumer the eco verdict must respect: MJPEG and WS
     * clients, RTSP playing clients, HLS fetches (a hot ring means a player
     * is pulling), and the RTMP and WHIP pushes (their remote servers are
     * consumers even though they never appear as local clients).
     */
    private fun liveConsumerCount(): Int {
        val http = try {
            server.getClientCount()
        } catch (_: Exception) {
            0
        }
        val rtsp = try {
            getRtspClientCount()
        } catch (_: Exception) {
            0
        }
        val ws = wsMediaServer?.videoClientCount() ?: 0
        val hls = if (HlsManager.isHot()) 1 else 0
        val rtmp = if (rtmpOutput.isActive()) 1 else 0
        val whip = if (whipOutput.isActive()) 1 else 0
        return http + rtsp + ws + hls + rtmp + whip
    }

    init {
        framePipeline.setListener { jpeg ->
            latestWebJpeg = jpeg
            server.updateFrame(jpeg)
        }
        // The encoded sinks' aggregate send tap: RTSP RTP fan-out, WS video
        // fan-out (per server) and the HLS segment writer all report
        // bytes/time here, feeding the adaptive encoded-bitrate lane.
        HlsManager.encodedSendTap = ::onEncodedSinkSend
        // The gate reads the API-token settings live through this provider —
        // no snapshot and no re-apply: a token (or the enable toggle) saved
        // over /api/settings authorizes on the very next request. Installed
        // at the composition root, which owns both the gate and the store.
        webAuthGate.setApiTokenProvider {
            WebAuthGate.ApiTokenConfig(
                enabled = authSettingsStore.apiTokenEnabled.value,
                hash = authSettingsStore.apiTokenHash.value,
            )
        }
    }

    /**
     * The one encoded-sink send seam: bytes actually handed to consumers over
     * the measured wall time, aggregated into the monitor's encoded lane.
     * 0-byte/0-time samples are dropped at the monitor.
     */
    private fun onEncodedSinkSend(bytes: Int, durationMs: Long) {
        networkQualityMonitor.recordEncodedSend(bytes, durationMs)
    }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming

    private val _isWebStreamingActive = MutableStateFlow(false)
    val isWebStreamingActive: StateFlow<Boolean> = _isWebStreamingActive

    private val _isWebEnabled = MutableStateFlow(true)
    val isWebEnabled: StateFlow<Boolean> = _isWebEnabled

    private val _isRtspEnabled = MutableStateFlow(false)
    val isRtspEnabled: StateFlow<Boolean> = _isRtspEnabled

    private val _streamUrl = MutableStateFlow("")
    val streamUrl: StateFlow<String> = _streamUrl

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning

    private val _audioStreamUrl = MutableStateFlow("")
    val audioStreamUrl: StateFlow<String> = _audioStreamUrl

    private val _isAudioStreaming = MutableStateFlow(false)
    val isAudioStreaming: StateFlow<Boolean> = _isAudioStreaming

    private val _rtspUrl = MutableStateFlow("")
    val rtspUrl: StateFlow<String> = _rtspUrl

    private val _isRtspRunning = MutableStateFlow(false)
    val isRtspRunning: StateFlow<Boolean> = _isRtspRunning

    private val _isRtmpEnabled = MutableStateFlow(false)
    val isRtmpEnabled: StateFlow<Boolean> = _isRtmpEnabled

    /** The RTMP push output's lifecycle state — idle/connecting/connected/error(+message). */
    private val _rtmpStatus = MutableStateFlow<RtmpStatus>(RtmpStatus.Idle)
    val rtmpStatus: StateFlow<RtmpStatus> = _rtmpStatus

    private val _isWhipEnabled = MutableStateFlow(false)
    val isWhipEnabled: StateFlow<Boolean> = _isWhipEnabled

    /** The WHIP push output's lifecycle state — idle/connecting/connected/error(+message). */
    private val _whipStatus = MutableStateFlow<WhipStatus>(WhipStatus.Idle)
    val whipStatus: StateFlow<WhipStatus> = _whipStatus

    private val _isSrtEnabled = MutableStateFlow(false)
    val isSrtEnabled: StateFlow<Boolean> = _isSrtEnabled

    /** The SRT push output's lifecycle state — idle/connecting/connected/error(+message). */
    private val _srtStatus = MutableStateFlow<SrtStatus>(SrtStatus.Idle)
    val srtStatus: StateFlow<SrtStatus> = _srtStatus

    val droppedFrames: StateFlow<Int> = framePipeline.droppedFrames

    val processedFrames: StateFlow<Int> = framePipeline.processedFrames

    val adaptiveBitrateState: StateFlow<AdaptiveBitrateController.AdaptiveState> = adaptiveBitrateController.state

    fun getNetworkStatsSnapshot(): NetworkQualityMonitor.NetworkStatsSnapshot = networkQualityMonitor.getStatsSnapshot()

    /**
     * True while the RTSP output is live with its audio track wanted (toggle
     * on, mic not claimed by recording) — the RTSP half of the
     * foreground-service microphone verdict. The web half is
     * [isAudioStreaming]; either one capturing means the service must carry
     * the MICROPHONE type.
     */
    fun isRtspAudioActive(): Boolean = rtspOutput.isActive() && rtspOutput.isAudioWanted()

    /** RTSP health for the watchdog — playing clients + encoder counters. */
    fun getRtspHealth(): RtspHealth = rtspOutput.healthSnapshot()

    fun getRtspClientCount(): Int = getRtspHealth().playingClients

    fun isRtspServerHealthy(): Boolean = if (rtspOutput.isActive()) getRtspHealth().healthy else true

    /** Connect bundle for the native sheet + Web API: every URL a viewer can type or scan. */
    fun getConnectInfo(): ConnectInfo {
        val httpIp = NetworkUtils.getLocalIpAddress()
        return ConnectInfo(
            httpUrl = buildVideoUrl(),
            audioUrl = _audioStreamUrl.value.ifBlank { buildAudioUrl() },
            hlsUrl = NetworkUtils.getHlsPlaylistUrl(currentPort) ?: "${if (tlsEnabled) "https" else "http"}://localhost:$currentPort/hls/playlist.m3u8",
            rtspUrl = rtspOutput.url().ifBlank {
                val host = NetworkUtils.formatHostForUrl(httpIp ?: "localhost")
                "rtsp://$host:${rtspOutput.port()}/${RtspUriPolicy.DEFAULT_STREAM_PATH}"
            },
            httpClients = try {
                server.getClientCount()
            } catch (_: Exception) {
                _clientCount.value
            },
            rtspClients = getRtspClientCount(),
        )
    }

    fun getHttpClientIds(): List<String> = try {
        server.httpClientIds()
    } catch (_: Exception) {
        emptyList()
    }

    // ── Detection events: each detector hands its event to its typed listener ──
    private val motionDetector = com.raulshma.lenscast.capture.MotionDetector(
        onMotion = { delta, zones -> motionListener?.invoke(delta, zones) },
    )
    private val soundDetector = com.raulshma.lenscast.capture.SoundDetector(
        listener = { rms -> soundListener?.invoke(rms) },
    )
    @Volatile private var motionListener: ((delta: Double, zones: List<String>) -> Unit)? = null
    @Volatile private var soundListener: ((rmsPercent: Double) -> Unit)? = null

    /**
     * The newest camera frame, retained as the ML object-detection gate's
     * analysis input. No copy: the gate consumes it synchronously inside the
     * motion-verdict call stack ([pushFrame] → detector → listener →
     * coordinator), copying before any async inference, so the buffer can
     * never outlive the frame that produced it.
     */
    @Volatile private var latestAnalysisFrame: com.raulshma.lenscast.capture.ml.AnalysisFrame? = null

    /** The frame the ML gate classifies; only meaningful on the motion-verdict call stack. */
    fun latestAnalysisFrame(): com.raulshma.lenscast.capture.ml.AnalysisFrame? = latestAnalysisFrame

    /** Application context for app-scoped collaborators resolved through the manager. */
    fun appContext(): android.content.Context = context.applicationContext

    /**
     * Continuous NVR-style loop recording: hosted here (constructed + started
     * at manager construction — the app-lifetime composition point) because
     * the composition root's wiring is fixed; everything the loop decides
     * lives in [com.raulshma.lenscast.capture.model.ContinuousRecordingPolicy]
     * and the controller only observes the store plus the RecordingController.
     */
    private val continuousRecordingController: com.raulshma.lenscast.capture.ContinuousRecordingController by lazy {
        val app = context.applicationContext as MainApplication
        com.raulshma.lenscast.capture.ContinuousRecordingController(
            settingsDataStore = app.settingsDataStore,
            recordingController = app.recordingController,
        )
    }

    init {
        audioStreamingManager.setChunkListener { pcm16 -> soundDetector.feed(pcm16) }
        continuousRecordingController.start()
    }

    fun setMotionDetectionEnabled(on: Boolean) {
        motionDetector.enabled = on
        if (on) motionDetector.reset()
    }

    fun setMotionSensitivity(sensitivity01: Float) {
        motionDetector.sensitivity = sensitivity01
    }

    /** The persisted motion-event cooldown, in seconds (the store clamps). */
    fun setMotionCooldownSeconds(seconds: Int) {
        motionDetector.cooldownMs = seconds * 1_000L
    }

    fun setMotionZones(zones: List<com.raulshma.lenscast.camera.model.MotionZone>) {
        motionDetector.zones = zones.filter { it.enabled }.map {
            com.raulshma.lenscast.camera.model.MotionZone.normalized(it)
        }
    }

    fun setAudioDeviceId(id: String) {
        audioStreamingManager.setPreferredDeviceId(id)
    }

    fun audioInputDevices(): List<Pair<Int, String>> = audioStreamingManager.inputDevices()

    fun setSoundDetection(
        enabled: Boolean,
        thresholdPercent: Int,
        adaptiveNoiseFloor: Boolean = soundDetector.adaptiveNoiseFloor,
    ) {
        soundDetector.enabled = enabled
        soundDetector.thresholdPercent = thresholdPercent
        soundDetector.adaptiveNoiseFloor = adaptiveNoiseFloor
    }

    /** The persisted sound-event cooldown, in seconds (the store clamps). */
    fun setSoundCooldownSeconds(seconds: Int) {
        soundDetector.cooldownMs = seconds * 1_000L
    }

    /**
     * Installs the YAMNet classifier's tap on the sound detector's PCM feed
     * (composition-root wiring, like the listener seams below); the detector
     * owns the fail-open contract — the tap sees the same chunks, before the
     * RMS gate, and can never affect or throw onto the audio path.
     */
    fun setSoundClassificationTap(tap: ((pcm16: ByteArray) -> Unit)?) {
        soundDetector.audioTap = tap
    }

    /** The live mic-capture rate the PCM chunks carry (the classifier resamples from it). */
    fun audioCaptureSampleRateHz(): Int = try {
        audioStreamingManager.getSampleRateHz()
    } catch (_: Exception) {
        com.raulshma.lenscast.core.StreamDefaults.AUDIO_SAMPLE_RATE_HZ
    }

    /** The live mic-capture channel count the PCM chunks carry. */
    fun audioCaptureChannelCount(): Int = try {
        audioStreamingManager.getChannelCount()
    } catch (_: Exception) {
        1
    }

    /** The detector seams; wired once at the composition root. */
    fun setMotionListener(listener: ((delta: Double, zones: List<String>) -> Unit)?) {
        motionListener = listener
    }

    fun setSoundListener(listener: ((rmsPercent: Double) -> Unit)?) {
        soundListener = listener
    }

    /** The shared siren for the web toggle and detection automation — one audio owner. */
    fun sirenController(): SirenPlayer = sirenPlayer

    /** Latest rendered M-JPEG frame for detection-event snapshots; null before the first frame. */
    fun latestWebFrame(): ByteArray? = latestWebJpeg

    /** True kick: closes the MJPEG stream so the socket drops. */
    fun kickHttpClient(clientId: String): Boolean = try {
        server.kickHttpClient(clientId)
    } catch (_: Exception) {
        false
    }

    data class ConnectInfo(
        val httpUrl: String,
        val audioUrl: String,
        val hlsUrl: String,
        val rtspUrl: String,
        val httpClients: Int,
        val rtspClients: Int,
    )

    /** True when thermal CRITICAL asks the frame path to pause encoding. */
    fun isThermallyPaused(): Boolean = thermalMonitor.throttlingResult.value.shouldPause

    /** Per-client measured throughput/fps read seam for Web API handlers. */
    fun getFramesPerSecond(clientId: String): Double = networkQualityMonitor.getFramesPerSecond(clientId)

    fun isLiveStreaming(): Boolean =
        webStreamingActive.get() || rtspOutput.isActive() || rtmpOutput.isActive() ||
            whipOutput.isActive() || srtOutput.isActive()

    fun isWebStreamActive(): Boolean = webStreamingActive.get()

    private fun updateStreamingState() {
        val anyActive =
            webStreamingActive.get() || rtspOutput.isActive() || rtmpOutput.isActive() ||
                whipOutput.isActive() || srtOutput.isActive()
        _isStreaming.value = anyActive
        _isWebStreamingActive.value = webStreamingActive.get()
    }

    fun setPort(port: Int) {
        if (isLiveStreaming()) {
            Log.w(TAG, "Cannot change port while streaming")
            return
        }
        if (port != currentPort) {
            val restarted = recreateServerIfRunning {
                currentPort = port
                server = createServer(port)
                _streamUrl.value = buildVideoUrl()
                if (_isAudioStreaming.value) {
                    _audioStreamUrl.value = buildAudioUrl()
                }
            }
            if (restarted == false) {
                Log.e(TAG, "Failed to restart streaming server on new port $port")
            }
            Log.d(TAG, "Streaming port set to $port")
        }
    }

    /**
     * The stop → recreate → start cycle shared by port and TLS changes: only
     * bounces the transport when it was already serving. Null means it was
     * not running (nothing restarted); otherwise the restart outcome.
     */
    private inline fun recreateServerIfRunning(recreate: () -> Unit): Boolean? {
        val wasRunning = _isServerRunning.value
        if (wasRunning) {
            stopTransport()
            _isServerRunning.value = false
        }
        recreate()
        if (!wasRunning) return null
        val restarted = server.startServer()
        _isServerRunning.value = restarted
        return restarted
    }

    fun setAdaptiveBitrateEnabled(enabled: Boolean) {
        adaptiveBitrateController.setEnabled(enabled)
        Log.d(TAG, "Adaptive bitrate ${if (enabled) "enabled" else "disabled"}")
    }

    // ── Adaptive encoded-video bitrate ──
    // The MJPEG adaptive ladder's twin for the encoded sinks: measured
    // encoded-sink throughput (the monitor's encoded lane) + thermal scale
    // the CONFIGURED bitrate down and back up, through the pure
    // [EncodedBitratePolicy], applied live via the hub's setVideoBitrate
    // hot-swap. Opt-in (default off, matching the MJPEG adaptive toggle);
    // a short poll re-evaluates while the encoded pipeline runs.

    private val encodedAdaptiveEnabled = AtomicBoolean(false)
    private var encodedAdaptiveMonitorJob: Job? = null

    /**
     * The adaptation ceiling — the configured bitrate the ladder starts from
     * and falls back to. No persisted bitrate setting exists yet, so this is
     * the StreamDefaults default; the ladder can lower the live value but
     * never above this.
     */
    @Volatile private var configuredVideoBitrate = StreamDefaults.RTSP_VIDEO_BITRATE

    /** The persisted encoded-adaptive toggle (Settings Applier). */
    fun setEncodedAdaptiveBitrateEnabled(enabled: Boolean) {
        val changed = encodedAdaptiveEnabled.getAndSet(enabled) != enabled
        if (changed) {
            Log.d(TAG, "Encoded adaptive bitrate ${if (enabled) "enabled" else "disabled"}")
        }
        if (enabled) {
            startEncodedAdaptiveMonitor()
        } else {
            encodedAdaptiveMonitorJob?.cancel()
        }
        if (!enabled) {
            restoreConfiguredVideoBitrate()
        } else {
            evaluateEncodedBitrate()
        }
    }

    fun isEncodedAdaptiveBitrateEnabled(): Boolean = encodedAdaptiveEnabled.get()

    private fun startEncodedAdaptiveMonitor() {
        if (encodedAdaptiveMonitorJob?.isActive == true) return
        encodedAdaptiveMonitorJob = monitorScope.launch {
            while (isActive) {
                delay(ENCODED_ADAPTIVE_INTERVAL_MS)
                evaluateEncodedBitrate()
            }
        }
    }

    /** One evaluation: policy verdict in, live encoder hot-swap out. */
    private fun evaluateEncodedBitrate() {
        if (!encodedHub.isRunning()) return
        val target = EncodedBitratePolicy.targetBitrate(
            enabled = encodedAdaptiveEnabled.get(),
            level = networkQualityMonitor.getEncodedQualityLevel(),
            thermal = thermalMonitor.thermalState.value,
            configuredBitrate = configuredVideoBitrate,
            currentBitrate = encodedHub.currentVideoBitrate(),
        )
        if (target != null) {
            encodedHub.setVideoBitrate(target)
            Log.d(
                TAG,
                "Encoded adaptive bitrate → $target bps " +
                    "(level=${networkQualityMonitor.getEncodedQualityLevel()}, " +
                    "thermal=${thermalMonitor.thermalState.value})",
            )
        }
    }

    private fun restoreConfiguredVideoBitrate() {
        if (encodedHub.currentVideoBitrate() != configuredVideoBitrate) {
            encodedHub.setVideoBitrate(configuredVideoBitrate)
            Log.d(TAG, "Encoded bitrate restored to configured $configuredVideoBitrate bps")
        }
    }

    // ── RTSP sub-stream (NVR detect role) ──

    /**
     * The opt-in low-res sub-stream: lands the toggle on the hub (a refresh
     * starts/stops its H.264 encoder) and — while the RTSP output is live —
     * /sub immediately serves or answers 404.
     */
    fun setRtspSubStreamEnabled(enabled: Boolean) {
        if (encodedHub.isSubStreamEnabled() == enabled) return
        encodedHub.setSubStreamEnabled(enabled)
        encodedHub.refresh()
        Log.d(TAG, "RTSP sub-stream ${if (enabled) "enabled" else "disabled"}")
    }

    /** The connected RTSP sessions for the clients list. */
    fun getRtspClients(): List<RtspClientDescriptor> = rtspOutput.clientSnapshot()

    /** True kick of one RTSP session by id (the MJPEG kick's RTSP twin). */
    fun kickRtspClient(clientId: String): Boolean = rtspOutput.kickClient(clientId)

    /** The HLS DVR window, in segments (0 = sliding live window). */
    fun setHlsDvrSegments(segments: Int) {
        HlsManager.setDvrSegments(segments)
    }

    fun ensureServerRunning(): Boolean {
        if (_isServerRunning.value) {
            if (_streamUrl.value.isBlank()) {
                _streamUrl.value = buildVideoUrl()
            }
            return true
        }

        val started = server.startServer()
        if (!started) {
            return false
        }
        startWsSidecar()
        // The WHEP endpoint rides the web transport: its reap loop arms with
        // the server (sessions are created on demand by viewers).
        runCatching { whepServer.start() }

        _isServerRunning.value = true
        _streamUrl.value = buildVideoUrl()
        Log.d(TAG, "Streaming server ready at ${_streamUrl.value}")
        return true
    }

    /** The WS sidecar rides the main server's lifecycle; failure is non-fatal. */
    private fun startWsSidecar() {
        if (wsMediaServer == null) {
            val sidecar = com.raulshma.lenscast.streaming.ws.WsMediaServer(
                currentPort + WS_PORT_OFFSET,
                audioStreamingManager,
                webAuthGate,
                encodedSendTap = ::onEncodedSinkSend,
            )
            if (tlsEnabled) {
                runCatching {
                    val app = context.applicationContext as MainApplication
                    sidecar.tlsServerSocketFactory = app.tlsCertManager.identity(localIpsSafe()).serverSocketFactory
                }
            }
            wsMediaServer = sidecar
        }
        wsMediaServer?.startServer()
    }

    private fun stopWsSidecar() {
        runCatching { wsMediaServer?.stopServer() }
        wsMediaServer = null
    }

    /** One lever for "both transports stop": the HTTP server plus the WS sidecar riding its lifecycle. */
    private fun stopTransport() {
        server.stopServer()
        stopWsSidecar()
        // WHEP sessions live on the web transport: every viewer's peer
        // connection dies with it (the WS sidecar's rule).
        runCatching { whepServer.stop() }
    }

    /** Every LAN address, best effort — the TLS identity covers all of them, falling back to the single local one. */
    private fun localIpsSafe(): List<String> =
        runCatching { NetworkUtils.getAllLocalIpAddresses() }
            .getOrDefault(listOfNotNull(NetworkUtils.getLocalIpAddress()))

    fun startStreaming(): Boolean {
        if (!webStreamingEnabled.get() && !rtspOutput.isEnabled() && !rtmpOutput.isEnabled() &&
            !whipOutput.isEnabled() && !srtOutput.isEnabled()
        ) {
            Log.w(TAG, "Cannot start streaming: every output is disabled")
            return false
        }

        if (webStreamingEnabled.get()) {
            if (!startWebStreaming()) return false
        }
        if (rtspOutput.isEnabled()) {
            startRtspStreaming()
        }
        if (rtmpOutput.isEnabled()) {
            startRtmpStreaming()
        }
        if (whipOutput.isEnabled()) {
            startWhipStreaming()
        }
        if (srtOutput.isEnabled()) {
            startSrtStreaming()
        }

        Log.d(TAG, "Streaming started at ${_streamUrl.value}")
        return true
    }

    fun stopStreaming() {
        stopWebStreaming()
        stopRtspStreaming()
        stopRtmpStreaming()
        stopWhipStreaming()
        stopSrtStreaming()
        stopTransport()
        unregisterMdnsService()
        _isServerRunning.value = false
        Log.d(TAG, "Streaming stopped")
    }

    fun pauseStreaming() {
        stopWebStreaming()
        stopRtspStreaming()
        stopRtmpStreaming()
        stopWhipStreaming()
        stopSrtStreaming()
        Log.d(TAG, "Live streaming paused (server still running)")
    }

    fun startWebStreaming(): Boolean {
        if (!webStreamingEnabled.get()) {
            Log.w(TAG, "Cannot start web streaming: web streaming is disabled")
            return false
        }
        if (webStreamingActive.getAndSet(true)) return true

        if (!ensureServerRunning()) {
            webStreamingActive.set(false)
            updateStreamingState()
            return false
        }

        HlsManager.setEnabled(true)

        refreshAudioStreamingState()
        // The capture settle lands first, then the hub taps it for the AAC
        // track — the hub's start runs the policy verdict immediately, so
        // HLS/WS video have an encoded source before the first client asks.
        encodedHub.refresh()
        if (mdnsEnabled.get()) {
            registerMdnsService(currentPort)
        }

        updateStreamingState()
        Log.d(TAG, "Web streaming started at ${_streamUrl.value}")
        return true
    }

    fun stopWebStreaming() {
        if (!webStreamingActive.getAndSet(false)) return
        audioStreamingManager.stop()
        HlsManager.setEnabled(false)
        encodedHub.refresh()
        clearWebAudioState()
        _streamUrl.value = ""
        _clientCount.value = 0
        lastReportedClientCount = -1
        unregisterMdnsService()
        updateStreamingState()
        Log.d(TAG, "Web streaming stopped")
    }

    fun startRtspStreaming(): Boolean {
        if (!rtspOutput.isEnabled()) {
            Log.w(TAG, "Cannot start RTSP streaming: RTSP is disabled")
            return false
        }
        if (rtspOutput.isActive()) return true
        rtspOutput.start()
        encodedHub.refresh()
        updateStreamingState()
        Log.d(TAG, "RTSP streaming started")
        return true
    }

    fun stopRtspStreaming() {
        if (!rtspOutput.isActive()) return
        rtspOutput.stop()
        encodedHub.refresh()
        updateStreamingState()
        Log.d(TAG, "RTSP streaming stopped")
    }

    // ── RTMP push output: the RtspOutput twins with an async connect ──

    /**
     * Starts the RTMP push output. The validation ladder (enabled gate, URL
     * parse, H.264 codec) runs synchronously in the output and a refusal
     * lands on [rtmpStatus] as an Error with the readable message; the
     * connect itself is asynchronous (Connecting → Connected, auto-reconnect
     * with capped backoff while active). False means refused — read
     * [rtmpStatus] for the reason.
     */
    fun startRtmpStreaming(): Boolean {
        if (!rtmpOutput.isEnabled()) {
            Log.w(TAG, "Cannot start RTMP push: RTMP is disabled")
            return false
        }
        if (rtmpOutput.isActive()) return true
        when (rtmpOutput.start()) {
            is RtmpOutput.StartResult.Started -> Unit
            is RtmpOutput.StartResult.Rejected -> {
                encodedHub.refresh()
                updateStreamingState()
                return false
            }
        }
        encodedHub.refresh()
        updateStreamingState()
        Log.d(TAG, "RTMP push started")
        return true
    }

    fun stopRtmpStreaming() {
        if (!rtmpOutput.isActive()) return
        rtmpOutput.stop()
        encodedHub.refresh()
        updateStreamingState()
        Log.d(TAG, "RTMP push stopped")
    }

    /** True while the RTMP output is live with the stream-audio toggle on — the mic verdict's RTMP half. */
    fun isRtmpAudioActive(): Boolean =
        rtmpOutput.isActive() && streamAudioEnabled.get() && !recordingAudioCaptureActive

    fun setRtmpEnabled(enabled: Boolean) {
        if (!rtmpOutput.setEnabled(enabled)) return
        _isRtmpEnabled.value = enabled
        if (!enabled) {
            stopRtmpStreaming()
        }
    }

    /**
     * The push URL from settings: lands in the output's retained value (so
     * the next start picks them up), and a live output restarts on it through
     * the output's own URL-change path.
     */
    fun setRtmpUrl(url: String) {
        rtmpOutput.setUrl(url)
    }

    /**
     * True while the RTMP push output is live — from a passing start until
     * stop, including connecting/reconnecting gaps (the [rtmpStatus] carries
     * which of the two is happening). The status snapshot's RTMP activity bit.
     */
    fun isRtmpActive(): Boolean = rtmpOutput.isActive()

    // ── SRT push output: the RTMP twins, MPEG-TS over UDP ──

    /**
     * Starts the SRT push output. The validation ladder (enabled gate, URL
     * parse, H.264 codec) runs synchronously in the output and a refusal
     * lands on [srtStatus] as an Error with the readable message; the
     * handshake itself is asynchronous (Connecting → Connected,
     * auto-reconnect with capped backoff while active). False means refused
     * — read [srtStatus] for the reason.
     */
    fun startSrtStreaming(): Boolean {
        if (!srtOutput.isEnabled()) {
            Log.w(TAG, "Cannot start SRT push: SRT is disabled")
            return false
        }
        if (srtOutput.isActive()) return true
        when (srtOutput.start()) {
            is SrtOutput.StartResult.Started -> Unit
            is SrtOutput.StartResult.Rejected -> {
                encodedHub.refresh()
                updateStreamingState()
                return false
            }
        }
        encodedHub.refresh()
        updateStreamingState()
        Log.d(TAG, "SRT push started")
        return true
    }

    fun stopSrtStreaming() {
        if (!srtOutput.isActive()) return
        srtOutput.stop()
        encodedHub.refresh()
        updateStreamingState()
        Log.d(TAG, "SRT push stopped")
    }

    fun setSrtEnabled(enabled: Boolean) {
        if (!srtOutput.setEnabled(enabled)) return
        _isSrtEnabled.value = enabled
        if (!enabled) {
            stopSrtStreaming()
        }
    }

    /**
     * The push URL from settings: lands in the output's retained value (so
     * the next start picks it up), and a live output restarts on it through
     * the output's own URL-change path.
     */
    fun setSrtUrl(url: String) {
        srtOutput.setUrl(url)
    }

    /**
     * True while the SRT push output is live — from a passing start until
     * stop, including connecting/reconnecting gaps (the [srtStatus] carries
     * which of the two is happening). The status snapshot's SRT activity bit.
     */
    fun isSrtActive(): Boolean = srtOutput.isActive()

    /** The SRT output's live wire stats (RTT, loss counts) for the status surfaces. */
    fun srtStats(): SrtStats = srtOutput.stats()

    // ── WHIP push output: the RTMP twins, on the NV21 analysis tap ──

    /**
     * Starts the WHIP push output. The validation ladder (enabled gate, URL
     * parse — no codec gate, libwebrtc encodes its own H.264) runs
     * synchronously in the output and a refusal lands on [whipStatus] as an
     * Error with the readable message; the session itself is asynchronous
     * (Connecting → Connected, auto-reconnect with capped backoff while
     * active). False means refused — read [whipStatus] for the reason.
     */
    fun startWhipStreaming(): Boolean {
        if (!whipOutput.isEnabled()) {
            Log.w(TAG, "Cannot start WHIP push: WHIP is disabled")
            return false
        }
        if (whipOutput.isActive()) return true
        when (whipOutput.start()) {
            is WhipOutput.StartResult.Started -> Unit
            is WhipOutput.StartResult.Rejected -> {
                updateStreamingState()
                return false
            }
        }
        updateStreamingState()
        Log.d(TAG, "WHIP push started")
        return true
    }

    fun stopWhipStreaming() {
        if (!whipOutput.isActive()) return
        whipOutput.stop()
        updateStreamingState()
        Log.d(TAG, "WHIP push stopped")
    }

    /**
     * True while the WHIP push output is live — from a passing start until
     * stop, including connecting/reconnecting gaps (the [whipStatus] carries
     * which of the two is happening). The status snapshot's WHIP activity bit.
     */
    fun isWhipActive(): Boolean = whipOutput.isActive()

    /** True while the WHIP output is live with the stream-audio toggle on — the mic verdict's WHIP half. */
    fun isWhipAudioActive(): Boolean = whipOutput.isActive() && whipAudioAllowed()

    fun setWhipEnabled(enabled: Boolean) {
        if (!whipOutput.setEnabled(enabled)) return
        _isWhipEnabled.value = enabled
        if (!enabled) {
            stopWhipStreaming()
        }
    }

    /**
     * The endpoint/token/STUN from settings: land in the output's retained
     * values (so the next start picks them up), and a live output restarts
     * through its own config-change path — the publisher's connect parameters
     * are per-attempt, so nothing can hot-swap.
     */
    fun setWhipUrl(url: String) {
        whipOutput.setUrl(url)
    }

    fun setWhipToken(token: String) {
        whipOutput.setToken(token)
    }

    /**
     * The retained WHIP STUN setting, so it can be handed to the WHEP
     * endpoint's ICE configuration too (one STUN setting serves both
     * WebRTC ends; blank = host candidates only).
     */
    @Volatile private var whipStunSetting: String = StreamDefaults.WHIP_STUN_SERVER

    fun setWhipStunServer(server: String) {
        whipStunSetting = server
        whipOutput.setStunServer(server)
    }

    /** The live WHEP viewer count — the status snapshot's `whepClients` value. */
    fun whepClientCount(): Int = try {
        whepServer.sessionCount()
    } catch (_: Exception) {
        0
    }

    /**
     * The mic-arbitration verdict the WHIP publisher is built with: audio on
     * only when the stream-audio toggle is on, no recording capture, no eco
     * idle, and the shared live capture (web talkback, RTSP/RTMP audio) is not
     * already running — the WHIP publisher owns a dedicated AudioRecord, so a
     * busy shared capture means it runs video-only. Evaluated at
     * publisher-construction time; the output re-arbitrates on every (re)start.
     */
    private fun whipAudioAllowed(): Boolean =
        streamAudioEnabled.get() &&
            !recordingAudioCaptureActive &&
            !ecoIdleActive &&
            !audioStreamingManager.isRunning()

    /**
     * Re-evaluates the WHIP audio verdict after one of its inputs moved: a
     * live output whose verdict flipped restarts so the next session drops or
     * picks the audio track up. Called from the mutation sites
     * ([setStreamAudioEnabled], [setAudioConfig],
     * [setRecordingAudioCaptureActive]) with the verdict read beforehand.
     */
    private fun rearmWhipAudioArbitration(verdictBefore: Boolean) {
        if (whipAudioAllowed() != verdictBefore) {
            whipOutput.onMicVerdictChanged()
        }
    }

    /**
     * One camera frame fans out to every consumer — the M-JPEG web pipeline,
     * the shared encoded-stream hub (RTSP RTP, HLS, WS video), and the WHIP
     * push's libwebrtc video source. The web pipeline no-ops while inactive;
     * the hub runs its policy verdict per frame and encodes only while some
     * encoded sink is active; the WHIP publisher no-ops while stopped.
     */
    fun pushFrame(yuvData: ByteArray, width: Int, height: Int, rotation: Int = 0) {
        // Thermal CRITICAL pauses encoding on both outputs — the pipeline's
        // Long.MAX_VALUE delay alone would stall MJPEG while the encoded
        // sinks kept burning CPU.
        if (isThermallyPaused()) return
        // Motion runs on sampled luma even with zero viewers (surveillance).
        // The frame reference is freshened first so the ML gate (reached
        // synchronously inside the detector's fire callback) reads exactly
        // the frame that fired the verdict.
        latestAnalysisFrame = com.raulshma.lenscast.capture.ml.AnalysisFrame(yuvData, width, height)
        try {
            motionDetector.feed(yuvData, width, height)
        } catch (_: Exception) {
        }
        pushFrameToWeb(yuvData, width, height, rotation)
        encodedHub.pushFrame(yuvData, width, height, rotation)
        // The WHIP publisher taps the same NV21 analysis frame (it encodes its
        // own H.264 from it) and no-ops internally while stopped.
        whipOutput.feedVideoFrame(yuvData, width, height, rotation)
        // The WHEP endpoint taps it too — one shared libwebrtc VideoSource
        // fans the frame to every viewer's hardware encoder. No-ops with no
        // viewers.
        whepServer.feedVideoFrame(yuvData, width, height, rotation)
    }

    private fun pushFrameToWeb(yuvData: ByteArray, width: Int, height: Int, rotation: Int) {
        if (!webStreamingActive.get()) return

        val clientCount = server.getClientCount()
        // Report before gating so the count falls back to 0 when the last
        // client disconnects mid-stream.
        if (clientCount != lastReportedClientCount) {
            lastReportedClientCount = clientCount
            _clientCount.value = clientCount
            // An MJPEG viewer arriving (or the last one leaving) is an eco
            // verdict input — re-check now instead of waiting out the poll.
            if (ecoIdleEnabled.get()) {
                evaluateEcoIdle(System.currentTimeMillis())
            }
        }
        if (clientCount == 0) return

        framePipeline.push(yuvData, width, height, rotation, currentOverlaySettings, clientCount)
    }

    fun setJpegQuality(quality: Int) {
        framePipeline.setJpegQuality(quality.coerceIn(StreamDefaults.JPEG_QUALITY_MIN, StreamDefaults.JPEG_QUALITY_MAX))
    }

    /**
     * One user-facing frame rate fans out to every subsystem that throttles
     * or encodes by it: the M-JPEG frame interval, the adaptive-bitrate
     * default, and the encoded-stream hub (RTP increment follows via the
     * RTSP output's own retained config). The value is remembered as the
     * user's rate — the eco idle mode overrides the *effective* rate
     * ([applyEffectiveFrameRate]) without losing this one.
     */
    fun setFrameRate(fps: Int) {
        userFrameRate = fps
        applyEffectiveFrameRate()
    }

    /** The effective fan-out: the eco floor while eco idle is dropped in, else the user's rate. */
    private fun applyEffectiveFrameRate() {
        val fps = if (ecoIdleActive) EcoIdlePolicy.floorFps(userFrameRate) else userFrameRate
        setStreamFrameRate(fps)
        setAdaptiveDefaultFrameRate(fps)
        rtspOutput.setFrameRate(fps)
        encodedHub.setFrameRate(fps)
        whipOutput.setFrameRate(fps)
        whepServer.setFrameRate(fps)
    }

    private fun setStreamFrameRate(fps: Int) {
        framePipeline.setFrameRate(fps)
    }

    private fun setAdaptiveDefaultFrameRate(fps: Int) {
        adaptiveBitrateController.setDefaultFrameRate(fps)
    }

    /**
     * Last audio config applied through [setAudioConfig] — the change
     * detector that keeps a persisted-settings re-emission from churning the
     * capture and restarting the RTSP output when nothing actually moved.
     * Mirrors the [streamAudioEnabled]/bitrate/channels/echoCancellation
     * atomics above, which stay the live source for [audioConfig].
     */
    private data class AppliedAudioConfig(
        val enabled: Boolean,
        val bitrateKbps: Int,
        val channels: Int,
        val echoCancellation: Boolean,
    )

    @Volatile
    private var appliedAudioConfig = AppliedAudioConfig(
        enabled = true,
        bitrateKbps = StreamDefaults.AUDIO_BITRATE_KBPS,
        channels = StreamDefaults.AUDIO_CHANNELS,
        echoCancellation = true,
    )

    /**
     * The coalesced stream-audio entry: one call lands
     * enabled/bitrate/channels/echo, refreshes the web capture once, and
     * routes a single restart decision through the RTSP output. No-op when
     * nothing moved — the old four-setter sequence restarted a live output
     * up to four times per settings emission and could wedge the native
     * capture mid-storm (RTSP audio wedged silent with no error logged).
     */
    fun setAudioConfig(enabled: Boolean, bitrateKbps: Int, channels: Int, echoCancellation: Boolean) {
        val whipVerdictBefore = whipAudioAllowed()
        streamAudioEnabled.set(enabled)
        val coercedBitrate = bitrateKbps.coerceIn(StreamDefaults.AUDIO_BITRATE_MIN_KBPS, StreamDefaults.AUDIO_BITRATE_MAX_KBPS)
        streamAudioBitrateKbps.set(coercedBitrate)
        val coercedChannels = channels.coerceIn(StreamDefaults.AUDIO_CHANNELS_MIN, StreamDefaults.AUDIO_CHANNELS_MAX)
        streamAudioChannels.set(coercedChannels)
        streamAudioEchoCancellation.set(echoCancellation)
        val next = AppliedAudioConfig(enabled, coercedBitrate, coercedChannels, echoCancellation)
        val prev = appliedAudioConfig
        appliedAudioConfig = next
        if (next == prev) return
        onWebAudioChanged()
        // One routing decision for the whole change (see
        // [RtspOutput.setAudioConfig]): a wanted flip restarts whenever live,
        // otherwise the audio restart ladder decides.
        rtspOutput.setAudioConfig(enabled, coercedBitrate)
        // The enable bit is the only part of this config the WHIP audio
        // verdict reads — re-arbitrate a live push around it.
        rearmWhipAudioArbitration(whipVerdictBefore)
    }

    fun setStreamAudioEnabled(enabled: Boolean) {
        val whipVerdictBefore = whipAudioAllowed()
        streamAudioEnabled.set(enabled)
        appliedAudioConfig = appliedAudioConfig.copy(enabled = enabled)
        onWebAudioChanged()
        // The one audio change that restarts even when turning the track
        // off: a toggle changes the RTSP audio track either way. Kept
        // forceful (no change detection): the mic-permission-grant path calls
        // this with an already-true value to pick the microphone back up.
        rtspOutput.setAudioWanted(enabled)
        rearmWhipAudioArbitration(whipVerdictBefore)
    }

    fun setWebStreamingEnabled(enabled: Boolean) {
        val changed = webStreamingEnabled.getAndSet(enabled) != enabled
        if (!changed) return

        _isWebEnabled.value = enabled
        server.setWebStreamingEnabled(enabled)

        if (!enabled && webStreamingActive.get()) {
            stopWebStreaming()
        }
    }

    /**
     * The audio bitrate is a NeedsRestart change in the RTSP verdict
     * ([RtspConfigDiff]): the AAC encoder only reads its bitrate at its next
     * start, so applying it to a live server would silently no-op. The value
     * is retained by the RTSP output and enforced by its restart ladder —
     * the change actually takes effect instead of doing nothing.
     */
    fun setStreamAudioBitrateKbps(bitrateKbps: Int) {
        val coerced = bitrateKbps.coerceIn(StreamDefaults.AUDIO_BITRATE_MIN_KBPS, StreamDefaults.AUDIO_BITRATE_MAX_KBPS)
        streamAudioBitrateKbps.set(coerced)
        appliedAudioConfig = appliedAudioConfig.copy(bitrateKbps = coerced)
        onWebAudioChanged()
        rtspOutput.setAudioBitrate(streamAudioBitrateKbps.get())
    }

    fun setStreamAudioChannels(channels: Int) {
        val coerced = channels.coerceIn(StreamDefaults.AUDIO_CHANNELS_MIN, StreamDefaults.AUDIO_CHANNELS_MAX)
        streamAudioChannels.set(coerced)
        appliedAudioConfig = appliedAudioConfig.copy(channels = coerced)
        onWebAudioChanged()
        // Channel count is an encoder config the AAC encoder reads at start;
        // RTSP restart required.
        rtspOutput.restartForAudioConfigChange()
    }

    fun setStreamAudioEchoCancellation(enabled: Boolean) {
        streamAudioEchoCancellation.set(enabled)
        appliedAudioConfig = appliedAudioConfig.copy(echoCancellation = enabled)
        onWebAudioChanged()
        // Echo cancellation is an audio-capture config applied at capture
        // start; RTSP restart required.
        rtspOutput.restartForAudioConfigChange()
    }

    // ── Stream-audio change policy: one decision point for every audio setting ──

    private fun onWebAudioChanged() {
        if (webStreamingActive.get()) {
            refreshAudioStreamingState()
        } else {
            clearWebAudioState()
        }
    }

    private fun clearWebAudioState() {
        _isAudioStreaming.value = false
        _audioStreamUrl.value = ""
    }

    private fun audioConfig(): AudioStreamingManager.Config {
        return AudioStreamingManager.Config(
            bitrateKbps = streamAudioBitrateKbps.get(),
            channelCount = streamAudioChannels.get(),
            echoCancellation = streamAudioEchoCancellation.get(),
        )
    }

    fun setRecordingAudioCaptureActive(active: Boolean) {
        val wasActive = recordingAudioCaptureActive
        val whipVerdictBefore = whipAudioAllowed()
        recordingAudioCaptureActive = active
        // The RTSP output's mic-arbitration input: while recording captures,
        // its next start opens no audio track.
        rtspOutput.setRecordingCaptureActive(active)
        // The WHIP twin: a live push whose verdict flipped restarts video-only
        // (or with audio back) from its next session.
        rearmWhipAudioArbitration(whipVerdictBefore)

        when {
            active && !wasActive -> {
                if (_isAudioStreaming.value) {
                    audioStreamingManager.stop()
                    _isAudioStreaming.value = false
                    _audioStreamUrl.value = ""
                    Log.d(TAG, "Paused live audio streaming so recording can capture the microphone")
                }
            }
            !active && wasActive -> {
                refreshAudioStreamingState()
                Log.d(TAG, "Recording microphone capture finished; refreshed live audio streaming state")
            }
        }
    }

    fun updateAuthSettings(settings: StreamAuthSettings) {
        // The gate is the one live holder of the web credentials and is
        // shared by every StreamingServer — there is nothing to re-apply on
        // recreation. The viewer pair rides the same live swap.
        webAuthGate.setCredentials(
            if (settings.enabled) settings.username else null,
            if (settings.enabled) settings.passwordHash else null,
        )
        webAuthGate.setViewerCredentials(settings.viewerUsername, settings.viewerPasswordHash)
        // The RTSP authorizer reads the (possibly restarted) auth spec live —
        // a hot-swap through the output, no restart owed.
        rtspOutput.setAuth()
    }

    fun setOverlaySettings(settings: OverlaySettings) {
        currentOverlaySettings = settings
        Log.d(TAG, "Overlay settings updated: enabled=${settings.enabled}, position=${settings.position}")
    }

    fun setRtspEnabled(enabled: Boolean) {
        if (!rtspOutput.setEnabled(enabled)) return
        _isRtspEnabled.value = enabled
        if (!enabled) {
            stopRtspStreaming()
        }
    }

    fun setRtspPort(port: Int) {
        rtspOutput.setPort(port)
    }

    fun setRtspInputFormat(format: RtspInputFormat) {
        rtspOutput.setInputFormat(format)
        encodedHub.setInputFormat(format)
    }

    /**
     * The persisted RTSP resolution fans out to both sides of the encoded
     * path: the output's retained [com.raulshma.lenscast.streaming.rtsp.RtspConfig]
     * (whose diff restarts a live server — a dimension change cannot hot-swap)
     * and the hub's retained encoder dimensions for its next configure.
     */
    fun setRtspResolution(resolution: RtspResolution) {
        rtspOutput.setResolution(resolution)
        encodedHub.setVideoResolution(resolution.width, resolution.height)
    }

    /**
     * Fans the RTSP video codec (H264/H265, persisted as `rtsp_video_codec`
     * in [com.raulshma.lenscast.data.SettingsDataStore] and applied by the
     * settings applier) out to the same two seams [setRtspResolution] fans
     * resolution out to. Both sinks dedupe on their own live value, so a
     * repeat apply with the same codec is a no-op. The output's retained
     * [com.raulshma.lenscast.streaming.rtsp.RtspConfig] carries it (its diff
     * classifies the swap NEEDS_RESTART, and a live output restarts via the
     * WHILE_ACTIVE ladder — new SDP + packetizer); the encoded-stream hub
     * reconfigures stop → (new codec's) encoder → start + black frame. On
     * H265 the hub's fan-out feeds the RTSP sink only (HLS/WS stay
     * H.264-only).
     */
    fun setRtspVideoCodec(codec: RtspVideoCodec) {
        rtspOutput.setVideoCodec(codec)
        encodedHub.setVideoCodec(codec)
        // The HLS TS muxer's PMT must declare the elementary stream actually
        // in the segments: flip the stream type with the codec and drop the
        // ring so segments of both codecs never mix (an H.265 HLS is
        // Safari-grade; the WS path answers with an hvcC config message).
        TsPacketizer.setVideoStreamType(
            if (codec == RtspVideoCodec.H265) TsPacketizer.STREAM_TYPE_HEVC else TsPacketizer.STREAM_TYPE_H264,
        )
        HlsManager.reset()
        // RTMP has no H.265 mapping: a live push under a codec flip to H265
        // goes dark silently otherwise — stop it with the readable reason.
        // (A flip back to H264 needs no help; the user restarts the push.)
        if (codec == RtspVideoCodec.H265 && rtmpOutput.isActive()) {
            rtmpOutput.stop()
            _rtmpStatus.value = RtmpStatus.Error(
                "RTMP push stopped: the video codec changed to H.265, which RTMP cannot carry — " +
                    "switch back to H.264 and restart the push",
            )
            encodedHub.refresh()
            updateStreamingState()
        }
        // The SRT push is the same H.264-pinned consumer — stop it the same
        // way, with its own readable reason on its own status line.
        if (codec == RtspVideoCodec.H265 && srtOutput.isActive()) {
            srtOutput.stop()
            _srtStatus.value = SrtStatus.Error(
                "SRT push stopped: the video codec changed to H.265, which this SRT push cannot carry — " +
                    "switch back to H.264 and restart the push",
            )
            encodedHub.refresh()
            updateStreamingState()
        }
    }

    /** The encoded pipeline's live target bitrate — the adaptive controller's current value, not the static default. */
    fun currentVideoBitrate(): Int = encodedHub.currentVideoBitrate()

    fun setMdnsEnabled(enabled: Boolean) {
        val changed = mdnsEnabled.getAndSet(enabled) != enabled
        if (!changed) return

        if (enabled && webStreamingActive.get() && webStreamingEnabled.get()) {
            registerMdnsService(currentPort)
        } else {
            unregisterMdnsService()
        }
        Log.d(TAG, "mDNS service discovery ${if (enabled) "enabled" else "disabled"}")
    }

    private fun registerMdnsService(port: Int) {
        serviceDiscoveryManager.registerService(
            port = port,
            rtsp = ServiceDiscoveryManager.RtspAdvert(
                port = rtspOutput.port(),
                path = RtspUriPolicy.DEFAULT_STREAM_PATH,
                authRequired = webAuthGate.isEnabled,
            ),
        )
    }

    private fun unregisterMdnsService() {
        serviceDiscoveryManager.unregisterService()
    }

    private fun createServer(port: Int): StreamingServer {
        // Auth needs no re-application here: the filter reads the shared,
        // manager-owned gate live, and the RTSP spec comes from a provider.
        val factory = if (tlsEnabled) {
            runCatching {
                val app = context.applicationContext as MainApplication
                app.tlsCertManager.identity(localIpsSafe())
                    .also { tlsFingerprint = it.fingerprint }.serverSocketFactory
            }.onFailure { Log.w(TAG, "TLS identity unavailable; serving plain HTTP", it) }
                .getOrNull()
                .also { if (it == null) tlsEnabled = false }
        } else {
            null
        }
        return StreamingServer(
            port, context, audioStreamingManager, webApiStack, networkQualityMonitor, webAuthGate,
            encodedStreamActive = { encodedHub.isRunning() },
            tlsServerSocketFactory = factory,
            whepServer = whepServer,
        ).also {
            it.setWebStreamingEnabled(webStreamingEnabled.get())
        }
    }

    /**
     * Switch the server between plain HTTP and HTTPS (self-signed). Like a
     * port change, this is a stop → recreate → start cycle; the shared auth
     * gate survives, so dashboards stay logged in. The RTSP listener follows
     * the same toggle — a live output restarts so the next accept is TLS
     * (clients use rtsps:// on the same port) or plain rtsp again.
     */
    fun setTlsEnabled(enabled: Boolean) {
        if (tlsEnabled == enabled) return
        tlsEnabled = enabled
        val restarted = recreateServerIfRunning {
            server = createServer(currentPort)
        }
        if (restarted != null) {
            Log.d(TAG, "TLS ${if (enabled) "enabled" else "disabled"}; server restart=$restarted")
        }
        if (rtspOutput.isActive()) {
            rtspOutput.stop()
            rtspOutput.start()
        }
    }

    /** The RTSP listener's TLS factory while HTTPS mode is on; null keeps it plain. */
    private fun rtspTlsFactory(): javax.net.ssl.SSLServerSocketFactory? {
        if (!tlsEnabled) return null
        return runCatching {
            val app = context.applicationContext as MainApplication
            app.tlsCertManager.identity(localIpsSafe()).serverSocketFactory
        }.onFailure { Log.w(TAG, "TLS identity unavailable; serving plain RTSP", it) }
            .getOrNull()
    }

    fun tlsCertificateFingerprint(): String = tlsFingerprint


    /**
     * Composition root for the Web API: one handler module per domain, each
     * receiving only the services it needs. Evaluation is lazy — request-time
     * only — so capturing `this` here is safe during construction.
     */
    private fun buildWebApiStack(): WebApiStack {
        val app = context.applicationContext as MainApplication
        val gallery = GalleryWebHandler(context, app.captureHistoryStore)
        val deterrence = DeterrenceWebHandler(sirenPlayer)
        val detectionEvents = DetectionEventsWebHandler(
            app.detectionEventStore,
        )
        val authHandler = AuthWebHandler(app.settingsDataStore, webAuthGate)
        val auditLog = AuditLog(
            file = java.io.File(context.filesDir, AUDIT_LOG_FILE),
        )
        val auditHandler = AuditWebHandler(auditLog)
        val detectionTest = DetectionTestWebHandler(
            coordinator = { app.detectionCoordinator },
        )
        val statusHandler = StatusWebHandler(
            streamingManager = this,
            thermalMonitor = thermalMonitor,
            powerManager = app.powerManager,
            cameraService = app.cameraService,
            streamWatchdog = app.streamWatchdog,
            settingsDataStore = app.settingsDataStore,
        )
        val systemHandler = SystemWebHandler(
            context = context,
            powerManager = app.powerManager,
            captureHistoryStore = app.captureHistoryStore,
        )
        val pushHandler = PushWebHandler(
            subscriptions = app.pushSubscriptionStore,
            vapidKeys = app.vapidKeys,
        )
        return WebApiStack(
            router = ApiRouter(
                settings = SettingsWebHandler(
                    settingsDataStore = app.settingsDataStore,
                    detectionModelStore = app.detectionModelStore,
                    audioModelStore = app.audioModelStore,
                ),
                status = statusHandler,
                stream = StreamWebHandler(this, app.streamingSession),
                capture = CaptureWebHandler(app.photoCaptureManager),
                lens = LensWebHandler(app.cameraService),
                interval = IntervalCaptureWebHandler(context),
                recording = RecordingWebHandler(app.recordingController),
                recordingSessions = com.raulshma.lenscast.streaming.web.RecordingSessionsWebHandler(
                    captureHistoryStore = app.captureHistoryStore,
                    eventStore = app.detectionEventStore,
                ),
                gallery = gallery,
                deterrence = deterrence,
                detectionEvents = detectionEvents,
                auth = authHandler,
                audit = auditHandler,
                detectionTest = detectionTest,
                auditLog = auditLog,
                system = systemHandler,
                push = pushHandler,
            ),
            gallery = gallery,
            status = statusHandler,
            capture = app.photoCaptureManager,
            deterrence = deterrence,
            auth = authHandler,
            detectionEvents = detectionEvents,
            audit = auditHandler,
            auditLog = auditLog,
            detectionTest = detectionTest,
            system = systemHandler,
            push = pushHandler,
        )
    }

    /** Null when auth is off or incomplete — the server treats null as "auth off". */
    private fun rtspAuthSpec(settings: StreamAuthSettings): RtspAuthSpec? {
        if (!settings.enabled || settings.username.isEmpty() || settings.passwordHash.isEmpty()) return null
        return RtspAuthSpec(settings.username, settings.passwordHash, settings.rtspDigestHa1)
    }

    fun applyBatteryOptimization(result: com.raulshma.lenscast.core.BatteryOptimizationResult?) {
        if (result == null) return
        // The battery suggestion becomes the policy's base quality — thermal
        // still clamps it and the network ladder still scales it per push.
        setJpegQuality(result.suggestedJpegQuality)
        Log.d(TAG, "Battery optimization applied: quality=${result.suggestedJpegQuality} (${result.message})")
    }

    fun release() {
        framePipeline.release()
        encodedHub.stop()
        audioStreamingManager.release()
        stopStreaming()
        // The periodic monitors die with the manager, not just with their
        // toggles — a released manager keeps no polling loop alive.
        ecoIdleMonitorJob?.cancel()
        encodedAdaptiveMonitorJob?.cancel()
        monitorScope.cancel()
    }
    private fun refreshAudioStreamingState() {
        audioStreamingManager.stop()

        // Eco idle keeps the capture down: no consumers by definition, so the
        // mic (and every AAC encoder fed from it) stays off until a restore.
        if (!webStreamingActive.get() || !webStreamingEnabled.get() || !streamAudioEnabled.get() || recordingAudioCaptureActive || ecoIdleActive) {
            clearWebAudioState()
            return
        }

        val audioStarted = audioStreamingManager.start(audioConfig())
        _isAudioStreaming.value = audioStarted
        _audioStreamUrl.value = if (audioStarted) buildAudioUrl() else ""
    }

    private fun buildVideoUrl(): String {
        return NetworkUtils.getStreamingUrl(currentPort) ?: "${if (tlsEnabled) "https" else "http"}://localhost:$currentPort/stream"
    }

    private fun buildAudioUrl(): String {
        return NetworkUtils.getAudioUrl(currentPort) ?: "${if (tlsEnabled) "https" else "http"}://localhost:$currentPort/audio"
    }

    /**
     * Invoked by [RtspOutput] when a stop releases the audio stream it
     * opened: if the web output is not streaming, nobody needs the capture —
     * stop it and clear the web audio state.
     */
    private fun releaseRtspOwnedAudio() {
        if (!webStreamingActive.get()) {
            audioStreamingManager.stop()
            clearWebAudioState()
        }
    }

    companion object {
        private const val TAG = "StreamingManager"
        private const val WS_PORT_OFFSET = 1

        /** Poll cadence of the adaptive encoded-bitrate evaluation loop. */
        private const val ENCODED_ADAPTIVE_INTERVAL_MS = 2_000L

        /** The Web API audit trail, inside app-private files. */
        private const val AUDIT_LOG_FILE = "audit_log.json"
    }
}
