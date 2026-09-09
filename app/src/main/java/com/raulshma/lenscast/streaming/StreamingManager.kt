package com.raulshma.lenscast.streaming

import android.content.Context
import android.util.Log
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.web.ApiRouter
import com.raulshma.lenscast.streaming.web.AuthWebHandler
import com.raulshma.lenscast.streaming.web.CaptureWebHandler
import com.raulshma.lenscast.streaming.web.DetectionEventsWebHandler
import com.raulshma.lenscast.streaming.web.DeterrenceWebHandler
import com.raulshma.lenscast.streaming.web.GalleryWebHandler
import com.raulshma.lenscast.streaming.web.IntervalCaptureWebHandler
import com.raulshma.lenscast.streaming.web.LensWebHandler
import com.raulshma.lenscast.streaming.web.RecordingWebHandler
import com.raulshma.lenscast.streaming.web.SettingsWebHandler
import com.raulshma.lenscast.streaming.web.StatusWebHandler
import com.raulshma.lenscast.streaming.web.StreamWebHandler
import com.raulshma.lenscast.streaming.hls.HlsManager
import com.raulshma.lenscast.streaming.rtsp.RtspAuthSpec
import com.raulshma.lenscast.streaming.rtsp.RtspConfigDiff
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspServer
import com.raulshma.lenscast.streaming.rtsp.RtspUriPolicy
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val wsVideoSink: (List<com.raulshma.lenscast.streaming.rtsp.H264Encoder.EncodedNalUnit>) -> Unit = { nalUnits ->
        wsMediaServer?.feedVideo(nalUnits)
    }

    // The shared H.264/AAC encode pipeline: started whenever any encoded sink
    // is active (RTSP output, HLS ring, WS video clients — the
    // [EncodedStreamPolicy] verdict), and fanning its encoded access units
    // out to every sink. Its rtsp sink forwards to whatever server instance
    // the RTSP output currently holds, so the output stays a sink consumer —
    // never the only encode trigger.
    private val encodedHub: EncodedStreamHub = EncodedStreamHub(
        policyInputs = ::encodedStreamInputs,
        audio = audioStreamingManager,
        audioConfig = ::audioConfig,
        audioWanted = { streamAudioEnabled.get() && !recordingAudioCaptureActive },
        audioBitrateKbps = { streamAudioBitrateKbps.get() },
        rtspSink = object : EncodedSink {
            override fun feedVideo(nalUnits: List<com.raulshma.lenscast.streaming.rtsp.H264Encoder.EncodedNalUnit>) {
                rtspOutput.feedEncodedVideo(nalUnits)
            }

            override fun feedAudio(aacData: ByteArray) {
                rtspOutput.feedEncodedAudio(aacData)
            }
        },
        hlsSink = HlsManager,
        wsVideoSink = wsVideoSink,
    )

    /** The sink-activity snapshot the hub's policy verdicts read. */
    private fun encodedStreamInputs(): EncodedStreamPolicy.Inputs = EncodedStreamPolicy.Inputs(
        webActive = webStreamingActive.get(),
        rtspActive = rtspOutput.isActive(),
        hlsRequested = HlsManager.isHot(),
        wsVideoClients = wsMediaServer?.videoClientCount() ?: 0,
    )

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
        serverFactory = { port -> RtspServer(port, encodedHub) },
    )
    private val serviceDiscoveryManager = ServiceDiscoveryManager(context)
    private val sirenPlayer = com.raulshma.lenscast.core.SirenPlayer()

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

    init {
        framePipeline.setListener { jpeg ->
            latestWebJpeg = jpeg
            server.updateFrame(jpeg)
        }
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

    // ── Detection events: motion + sound funnel into one typed listener seam ──
    private val motionDetector = com.raulshma.lenscast.capture.MotionDetector(
        onMotion = { delta ->
            detectionListener?.invoke(com.raulshma.lenscast.capture.DetectionCoordinator.EVENT_TYPE_MOTION, delta)
        },
    )
    private val soundDetector = com.raulshma.lenscast.capture.SoundDetector(
        listener = { rms ->
            detectionListener?.invoke(com.raulshma.lenscast.capture.DetectionCoordinator.EVENT_TYPE_SOUND, rms)
        },
    )
    @Volatile private var detectionListener: ((type: String, value: Double) -> Unit)? = null

    init {
        audioStreamingManager.setChunkListener { pcm16 -> soundDetector.feed(pcm16) }
    }

    fun setMotionDetectionEnabled(on: Boolean) {
        motionDetector.enabled = on
        if (on) motionDetector.reset()
    }

    fun setMotionSensitivity(sensitivity01: Float) {
        motionDetector.sensitivity = sensitivity01
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

    fun setSoundDetection(enabled: Boolean, thresholdPercent: Int) {
        soundDetector.enabled = enabled
        soundDetector.thresholdPercent = thresholdPercent
    }

    /** One event vocabulary for motion and sound; wired once at the composition root. */
    fun setDetectionListener(listener: ((type: String, value: Double) -> Unit)?) {
        detectionListener = listener
    }

    /** The shared siren for the web toggle and detection automation — one audio owner. */
    fun sirenController(): com.raulshma.lenscast.core.SirenPlayer = sirenPlayer

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

    fun isLiveStreaming(): Boolean = webStreamingActive.get() || rtspOutput.isActive()

    fun isWebStreamActive(): Boolean = webStreamingActive.get()

    private fun updateStreamingState() {
        val anyActive = webStreamingActive.get() || rtspOutput.isActive()
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
    }

    /** Every LAN address, best effort — the TLS identity covers all of them, falling back to the single local one. */
    private fun localIpsSafe(): List<String> =
        runCatching { NetworkUtils.getAllLocalIpAddresses() }
            .getOrDefault(listOfNotNull(NetworkUtils.getLocalIpAddress()))

    fun startStreaming(): Boolean {
        if (!webStreamingEnabled.get() && !rtspOutput.isEnabled()) {
            Log.w(TAG, "Cannot start streaming: both web and RTSP outputs are disabled")
            return false
        }

        if (webStreamingEnabled.get()) {
            if (!startWebStreaming()) return false
        }
        if (rtspOutput.isEnabled()) {
            startRtspStreaming()
        }

        Log.d(TAG, "Streaming started at ${_streamUrl.value}")
        return true
    }

    fun stopStreaming() {
        stopWebStreaming()
        stopRtspStreaming()
        stopTransport()
        unregisterMdnsService()
        _isServerRunning.value = false
        Log.d(TAG, "Streaming stopped")
    }

    fun pauseStreaming() {
        stopWebStreaming()
        stopRtspStreaming()
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

    /**
     * One camera frame fans out to every consumer — the M-JPEG web pipeline
     * and the shared encoded-stream hub (RTSP RTP, HLS, WS video). The web
     * pipeline no-ops while inactive; the hub runs its policy verdict per
     * frame and encodes only while some encoded sink is active.
     */
    fun pushFrame(yuvData: ByteArray, width: Int, height: Int, rotation: Int = 0) {
        // Thermal CRITICAL pauses encoding on both outputs — the pipeline's
        // Long.MAX_VALUE delay alone would stall MJPEG while the encoded
        // sinks kept burning CPU.
        if (isThermallyPaused()) return
        // Motion runs on sampled luma even with zero viewers (surveillance).
        try {
            motionDetector.feed(yuvData, width, height)
        } catch (_: Exception) {
        }
        pushFrameToWeb(yuvData, width, height, rotation)
        encodedHub.pushFrame(yuvData, width, height, rotation)
    }

    private fun pushFrameToWeb(yuvData: ByteArray, width: Int, height: Int, rotation: Int) {
        if (!webStreamingActive.get()) return

        val clientCount = server.getClientCount()
        // Report before gating so the count falls back to 0 when the last
        // client disconnects mid-stream.
        if (clientCount != lastReportedClientCount) {
            lastReportedClientCount = clientCount
            _clientCount.value = clientCount
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
     * RTSP output's own retained config).
     */
    fun setFrameRate(fps: Int) {
        setStreamFrameRate(fps)
        setAdaptiveDefaultFrameRate(fps)
        rtspOutput.setFrameRate(fps)
        encodedHub.setFrameRate(fps)
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
    }

    fun setStreamAudioEnabled(enabled: Boolean) {
        streamAudioEnabled.set(enabled)
        appliedAudioConfig = appliedAudioConfig.copy(enabled = enabled)
        onWebAudioChanged()
        // The one audio change that restarts even when turning the track
        // off: a toggle changes the RTSP audio track either way. Kept
        // forceful (no change detection): the mic-permission-grant path calls
        // this with an already-true value to pick the microphone back up.
        rtspOutput.setAudioWanted(enabled)
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
        recordingAudioCaptureActive = active
        // The RTSP output's mic-arbitration input: while recording captures,
        // its next start opens no audio track.
        rtspOutput.setRecordingCaptureActive(active)

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
        // recreation.
        webAuthGate.setCredentials(
            if (settings.enabled) settings.username else null,
            if (settings.enabled) settings.passwordHash else null,
        )
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
        ).also {
            it.setWebStreamingEnabled(webStreamingEnabled.get())
        }
    }

    /**
     * Switch the server between plain HTTP and HTTPS (self-signed). Like a
     * port change, this is a stop → recreate → start cycle; the shared auth
     * gate survives, so dashboards stay logged in.
     */
    fun setTlsEnabled(enabled: Boolean) {
        if (tlsEnabled == enabled) return
        val restarted = recreateServerIfRunning {
            tlsEnabled = enabled
            server = createServer(currentPort)
        }
        if (restarted != null) {
            Log.d(TAG, "TLS ${if (enabled) "enabled" else "disabled"}; server restart=$restarted")
        }
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
        val statusHandler = StatusWebHandler(
            streamingManager = this,
            thermalMonitor = thermalMonitor,
            powerManager = app.powerManager,
            cameraService = app.cameraService,
            streamWatchdog = app.streamWatchdog,
            settingsDataStore = app.settingsDataStore,
        )
        return WebApiStack(
            router = ApiRouter(
                settings = SettingsWebHandler(app.settingsDataStore),
                status = statusHandler,
                stream = StreamWebHandler(this, app.streamingSession),
                capture = CaptureWebHandler(app.photoCaptureManager),
                lens = LensWebHandler(app.cameraService),
                interval = IntervalCaptureWebHandler(context),
                recording = RecordingWebHandler(app.recordingController),
                gallery = gallery,
                deterrence = deterrence,
                detectionEvents = detectionEvents,
                auth = authHandler,
            ),
            gallery = gallery,
            status = statusHandler,
            capture = app.photoCaptureManager,
            deterrence = deterrence,
            auth = authHandler,
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
    }

    private fun refreshAudioStreamingState() {
        audioStreamingManager.stop()

        if (!webStreamingActive.get() || !webStreamingEnabled.get() || !streamAudioEnabled.get() || recordingAudioCaptureActive) {
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
    }
}
