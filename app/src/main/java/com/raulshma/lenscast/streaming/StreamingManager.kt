package com.raulshma.lenscast.streaming

import android.content.Context
import android.util.Log
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.web.ApiRouter
import com.raulshma.lenscast.streaming.web.CaptureWebHandler
import com.raulshma.lenscast.streaming.web.GalleryWebHandler
import com.raulshma.lenscast.streaming.web.IntervalCaptureWebHandler
import com.raulshma.lenscast.streaming.web.LensWebHandler
import com.raulshma.lenscast.streaming.web.RecordingWebHandler
import com.raulshma.lenscast.streaming.web.SettingsWebHandler
import com.raulshma.lenscast.streaming.web.StatusWebHandler
import com.raulshma.lenscast.streaming.web.StreamWebHandler
import com.raulshma.lenscast.streaming.rtsp.RtspAuthSpec
import com.raulshma.lenscast.streaming.rtsp.RtspConfig
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat
import com.raulshma.lenscast.streaming.rtsp.RtspServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StreamingManager(
    private val context: Context,
    private val thermalMonitor: ThermalMonitor,
) {

    private val audioStreamingManager = AudioStreamingManager(context)
    private val serviceDiscoveryManager = ServiceDiscoveryManager(context)
    private val webStreamingEnabled = AtomicBoolean(true)
    private val mdnsEnabled = AtomicBoolean(true)
    @Volatile
    private var currentAuthSettings = StreamAuthSettings()
    @Volatile
    private var currentOverlaySettings = OverlaySettings()
    private val networkQualityMonitor = NetworkQualityMonitor()
    private val adaptiveBitrateController = AdaptiveBitrateController(networkQualityMonitor)
    private val framePipeline = FramePipeline(thermalMonitor, adaptiveBitrateController)
    private val webApiStack: WebApiStack by lazy { buildWebApiStack() }
    private var server: StreamingServer = createServer(StreamDefaults.WEB_PORT)
    private val webStreamingActive = AtomicBoolean(false)
    private val rtspStreamingActive = AtomicBoolean(false)
    private val jpegQuality = AtomicInteger(StreamDefaults.JPEG_QUALITY)
    private val streamAudioEnabled = AtomicBoolean(true)
    private val streamAudioBitrateKbps = AtomicInteger(StreamDefaults.AUDIO_BITRATE_KBPS)
    private val streamAudioChannels = AtomicInteger(StreamDefaults.AUDIO_CHANNELS)
    private val streamAudioEchoCancellation = AtomicBoolean(true)
    @Volatile
    private var recordingAudioCaptureActive = false
    private var currentPort: Int = StreamDefaults.WEB_PORT

    private val rtspEnabled = AtomicBoolean(false)
    @Volatile
    private var currentRtspPort: Int = RtspServer.DEFAULT_PORT
    // One retained config for the RTSP output; every RTSP setting lands here
    // even while RTSP is not running, so the next start picks it all up.
    @Volatile
    private var rtspConfig = RtspConfig()
    private var rtspServer: RtspServer? = null
    @Volatile
    private var rtspAudioStream: InputStream? = null

    private var lastReportedClientCount = -1

    init {
        framePipeline.setListener { jpeg -> server.updateFrame(jpeg) }
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

    // One gate for the app's web server: owned here so sessions survive a
    // server recreation (e.g. a port change).
    private val webAuthGate = WebAuthGate()

    val adaptiveBitrateState: StateFlow<AdaptiveBitrateController.AdaptiveState> = adaptiveBitrateController.state

    fun getNetworkStatsSnapshot(): NetworkQualityMonitor.NetworkStatsSnapshot = networkQualityMonitor.getStatsSnapshot()

    /** Per-client measured throughput/fps read seam for Web API handlers. */
    fun getFramesPerSecond(clientId: String): Double = networkQualityMonitor.getFramesPerSecond(clientId)

    fun isLiveStreaming(): Boolean = webStreamingActive.get() || rtspStreamingActive.get()

    fun isWebStreamingEnabled(): Boolean = webStreamingEnabled.get()

    fun isWebStreamActive(): Boolean = webStreamingActive.get()

    private fun updateStreamingState() {
        val anyActive = webStreamingActive.get() || rtspStreamingActive.get()
        _isStreaming.value = anyActive
        _isWebStreamingActive.value = webStreamingActive.get()
    }

    fun setPort(port: Int) {
        if (isLiveStreaming()) {
            Log.w(TAG, "Cannot change port while streaming")
            return
        }
        if (port != currentPort) {
            val wasServerRunning = _isServerRunning.value
            if (wasServerRunning) {
                server.stopServer()
                _isServerRunning.value = false
            }
            currentPort = port
            server = createServer(port)
            _streamUrl.value = buildVideoUrl()
            if (_isAudioStreaming.value) {
                _audioStreamUrl.value = buildAudioUrl()
            }
            if (wasServerRunning) {
                val restarted = server.startServer()
                _isServerRunning.value = restarted
                if (!restarted) {
                    Log.e(TAG, "Failed to restart streaming server on new port $port")
                }
            }
            Log.d(TAG, "Streaming port set to $port")
        }
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

        _isServerRunning.value = true
        _streamUrl.value = buildVideoUrl()
        Log.d(TAG, "Streaming server ready at ${_streamUrl.value}")
        return true
    }

    fun startStreaming(): Boolean {
        if (!webStreamingEnabled.get() && !rtspEnabled.get()) {
            Log.w(TAG, "Cannot start streaming: both web and RTSP outputs are disabled")
            return false
        }

        if (webStreamingEnabled.get()) {
            if (!startWebStreaming()) return false
        }
        if (rtspEnabled.get()) {
            startRtspStreaming()
        }

        Log.d(TAG, "Streaming started at ${_streamUrl.value}")
        return true
    }

    fun stopStreaming() {
        stopWebStreaming()
        stopRtspStreaming()
        server.stopServer()
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

        refreshAudioStreamingState()
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
        clearWebAudioState()
        _streamUrl.value = ""
        _clientCount.value = 0
        lastReportedClientCount = -1
        unregisterMdnsService()
        updateStreamingState()
        Log.d(TAG, "Web streaming stopped")
    }

    fun startRtspStreaming(): Boolean {
        if (!rtspEnabled.get()) {
            Log.w(TAG, "Cannot start RTSP streaming: RTSP is disabled")
            return false
        }
        if (rtspStreamingActive.getAndSet(true)) return true
        startRtspServer()
        updateStreamingState()
        Log.d(TAG, "RTSP streaming started")
        return true
    }

    fun stopRtspStreaming() {
        if (!rtspStreamingActive.getAndSet(false)) return
        stopRtspServer()
        updateStreamingState()
        Log.d(TAG, "RTSP streaming stopped")
    }

    /**
     * One camera frame fans out to every active output — the M-JPEG web
     * pipeline and the RTSP encoder — mirroring [setFrameRate]'s internal
     * fan-out. Each output no-ops while it is inactive.
     */
    fun pushFrame(yuvData: ByteArray, width: Int, height: Int, rotation: Int = 0) {
        pushFrameToWeb(yuvData, width, height, rotation)
        pushFrameToRtsp(yuvData, width, height, rotation)
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

    private fun pushFrameToRtsp(yuvData: ByteArray, width: Int, height: Int, rotation: Int) {
        if (!rtspStreamingActive.get()) return
        val rtsp = rtspServer ?: return
        rtsp.pushFrame(yuvData, width, height, rotation)
    }

    fun setJpegQuality(quality: Int) {
        framePipeline.setJpegQuality(quality.coerceIn(StreamDefaults.JPEG_QUALITY_MIN, StreamDefaults.JPEG_QUALITY_MAX))
    }

    /**
     * One user-facing frame rate fans out to every subsystem that throttles or
     * encodes by it: the M-JPEG frame interval, the adaptive-bitrate default,
     * and the RTSP server.
     */
    fun setFrameRate(fps: Int) {
        setStreamFrameRate(fps)
        setAdaptiveDefaultFrameRate(fps)
        setRtspFrameRate(fps)
    }

    private fun setStreamFrameRate(fps: Int) {
        framePipeline.setFrameRate(fps)
    }

    private fun setAdaptiveDefaultFrameRate(fps: Int) {
        adaptiveBitrateController.setDefaultFrameRate(fps)
    }

    fun setStreamAudioEnabled(enabled: Boolean) {
        streamAudioEnabled.set(enabled)
        onWebAudioChanged()
        // The only exception to the onRtspAudioChanged ladder: a toggle changes
        // the RTSP audio track either way, so restart even when turning off.
        if (rtspStreamingActive.get()) {
            restartRtspServer()
        }
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

    fun setStreamAudioBitrateKbps(bitrateKbps: Int) {
        streamAudioBitrateKbps.set(
            bitrateKbps.coerceIn(StreamDefaults.AUDIO_BITRATE_MIN_KBPS, StreamDefaults.AUDIO_BITRATE_MAX_KBPS)
        )
        onWebAudioChanged()
        rtspConfig = rtspConfig.copy(audioBitrateKbps = streamAudioBitrateKbps.get())
        // RTSP supports live bitrate updates; no restart needed.
        onRtspAudioChanged(liveUpdate = { rtspServer?.apply(rtspConfig) })
    }

    fun setStreamAudioChannels(channels: Int) {
        streamAudioChannels.set(
            channels.coerceIn(StreamDefaults.AUDIO_CHANNELS_MIN, StreamDefaults.AUDIO_CHANNELS_MAX)
        )
        onWebAudioChanged()
        // Channel count is an encoder config; RTSP restart required.
        onRtspAudioChanged()
    }

    fun setStreamAudioEchoCancellation(enabled: Boolean) {
        streamAudioEchoCancellation.set(enabled)
        onWebAudioChanged()
        // Echo cancellation is an audio-capture config; RTSP restart required.
        onRtspAudioChanged()
    }

    // ── Stream-audio change policy: one decision point for every audio setting ──

    private fun onWebAudioChanged() {
        if (webStreamingActive.get()) {
            refreshAudioStreamingState()
        } else {
            clearWebAudioState()
        }
    }

    /**
     * React to an audio setting that affects the RTSP track. [liveUpdate] is
     * used when the running server can apply the change without a restart;
     * otherwise the server restarts so the encoder picks the new config up.
     */
    private fun onRtspAudioChanged(liveUpdate: (() -> Unit)? = null) {
        if (!rtspStreamingActive.get() || !streamAudioEnabled.get()) return
        if (liveUpdate != null) {
            liveUpdate()
        } else {
            restartRtspServer()
        }
    }

    private fun restartRtspServer() {
        stopRtspServer()
        startRtspServer()
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
        currentAuthSettings = settings
        applyAuthSettings(settings)
        rtspConfig = rtspConfig.copy(auth = rtspAuthSpec(settings))
        rtspServer?.apply(rtspConfig)
    }

    fun setOverlaySettings(settings: OverlaySettings) {
        currentOverlaySettings = settings
        Log.d(TAG, "Overlay settings updated: enabled=${settings.enabled}, position=${settings.position}")
    }

    fun setRtspEnabled(enabled: Boolean) {
        val changed = rtspEnabled.getAndSet(enabled) != enabled
        if (!changed) return
        _isRtspEnabled.value = enabled
        if (!enabled && rtspStreamingActive.get()) {
            stopRtspStreaming()
        }
    }

    fun setRtspPort(port: Int) {
        if (port == currentRtspPort) return
        currentRtspPort = port
        if (rtspEnabled.get() && rtspServer != null) {
            stopRtspServer()
            startRtspServer()
        }
    }

    fun setRtspInputFormat(format: RtspInputFormat) {
        if (format == rtspConfig.inputFormat) return
        rtspConfig = rtspConfig.copy(inputFormat = format)
        rtspServer?.apply(rtspConfig)
    }

    private fun setRtspFrameRate(fps: Int) {
        // Retained even when RTSP is not running — every RTSP setting is
        // retained, no silent drops.
        rtspConfig = rtspConfig.copy(videoFrameRate = fps)
        rtspServer?.apply(rtspConfig)
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
        serviceDiscoveryManager.registerService(port = port)
    }

    private fun unregisterMdnsService() {
        serviceDiscoveryManager.unregisterService()
    }

    private fun createServer(port: Int): StreamingServer {
        return StreamingServer(port, context, audioStreamingManager, webApiStack, networkQualityMonitor, webAuthGate).also {
            applyAuthSettings(currentAuthSettings)
            it.setWebStreamingEnabled(webStreamingEnabled.get())
        }
    }

    /**
     * Composition root for the Web API: one handler module per domain, each
     * receiving only the services it needs. Evaluation is lazy — request-time
     * only — so capturing `this` here is safe during construction.
     */
    private fun buildWebApiStack(): WebApiStack {
        val app = context.applicationContext as MainApplication
        val gallery = GalleryWebHandler(context, app.captureHistoryStore)
        return WebApiStack(
            router = ApiRouter(
                settings = SettingsWebHandler(app.settingsDataStore),
                status = StatusWebHandler(
                    streamingManager = this,
                    thermalMonitor = thermalMonitor,
                    powerManager = app.powerManager,
                    cameraService = app.cameraService,
                    streamWatchdog = app.streamWatchdog,
                    settingsDataStore = app.settingsDataStore,
                ),
                stream = StreamWebHandler(this, app.streamingSession),
                capture = CaptureWebHandler(app.photoCaptureManager),
                lens = LensWebHandler(app.cameraService),
                interval = IntervalCaptureWebHandler(context),
                recording = RecordingWebHandler(app.recordingController),
                gallery = gallery,
            ),
            gallery = gallery,
            capture = app.photoCaptureManager,
        )
    }

    private fun applyAuthSettings(settings: StreamAuthSettings) {
        webAuthGate.setCredentials(
            if (settings.enabled) settings.username else null,
            if (settings.enabled) settings.passwordHash else null,
        )
    }

    /** Null when auth is off or incomplete — the server treats null as "auth off". */
    private fun rtspAuthSpec(settings: StreamAuthSettings): RtspAuthSpec? {
        if (!settings.enabled || settings.username.isEmpty() || settings.passwordHash.isEmpty()) return null
        return RtspAuthSpec(settings.username, settings.passwordHash, settings.rtspDigestHa1)
    }

    fun applyBatteryOptimization(result: com.raulshma.lenscast.core.BatteryOptimizationResult?) {
        if (result == null) return
        setJpegQuality(result.suggestedJpegQuality)
        Log.d(TAG, "Battery optimization applied: quality=${result.suggestedJpegQuality} (${result.message})")
    }

    fun release() {
        framePipeline.release()
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
        return NetworkUtils.getStreamingUrl(currentPort) ?: "http://localhost:$currentPort/stream"
    }

    private fun buildAudioUrl(): String {
        return NetworkUtils.getAudioUrl(currentPort) ?: "http://localhost:$currentPort/audio"
    }

    private fun buildRtspUrl(): String {
        val ip = NetworkUtils.getLocalIpAddress() ?: "localhost"
        return "rtsp://$ip:$currentRtspPort/${RtspServer.DEFAULT_STREAM_PATH}"
    }

    private fun startRtspServer() {
        if (rtspServer != null) return
        val server = RtspServer(currentRtspPort)

        val audioWanted = streamAudioEnabled.get() && !recordingAudioCaptureActive
        var audioStream: java.io.InputStream? = null
        if (audioWanted) {
            // Ensure audio capture is running
            if (!audioStreamingManager.isRunning()) {
                audioStreamingManager.start(audioConfig())
            }
            if (audioStreamingManager.isRunning()) {
                audioStream = audioStreamingManager.openStream()
                rtspAudioStream = audioStream
            }
        }

        rtspConfig = rtspConfig.copy(
            audioEnabled = audioStream != null,
            audioSampleRateHz = audioStreamingManager.getSampleRateHz(),
            audioChannelCount = audioStreamingManager.getChannelCount(),
            audioBitrateKbps = streamAudioBitrateKbps.get(),
            auth = rtspAuthSpec(currentAuthSettings),
        )

        if (server.start(rtspConfig, audioStream)) {
            rtspServer = server
            _rtspUrl.value = buildRtspUrl()
            _isRtspRunning.value = true
            Log.d(TAG, "RTSP server started on port $currentRtspPort (audio=${audioStream != null})")
        } else {
            rtspAudioStream?.close()
            rtspAudioStream = null
            Log.e(TAG, "Failed to start RTSP server on port $currentRtspPort")
        }
    }

    private fun stopRtspServer() {
        rtspServer?.stop()
        rtspServer = null
        rtspAudioStream?.close()
        rtspAudioStream = null
        // If audio was started only for RTSP and web streaming is not active, stop it
        if (!webStreamingActive.get()) {
            audioStreamingManager.stop()
            clearWebAudioState()
        }
        _rtspUrl.value = ""
        _isRtspRunning.value = false
    }

    companion object {
        private const val TAG = "StreamingManager"
    }
}
