package com.raulshma.lenscast.streaming

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.capture.IntervalCaptureScheduler
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.MaskingType
import com.raulshma.lenscast.camera.model.MaskingZone
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.OverlayPosition
import com.raulshma.lenscast.camera.model.OverlaySettings
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.capture.PhotoCaptureHelper
import com.raulshma.lenscast.capture.RecordingService
import com.raulshma.lenscast.streaming.model.*
import com.raulshma.lenscast.streaming.rtsp.RtspInputFormat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class WebApiController(private val context: Context) {

    private val app: MainApplication
        get() = context.applicationContext as MainApplication

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Issue 2.1 fix: Unified Moshi instance for all JSON serialization ──
    private val moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    // Adapters for request/response DTOs
    private val settingsResponseAdapter by lazy { moshi.adapter(SettingsResponseDto::class.java) }
    private val settingsUpdateAdapter by lazy { moshi.adapter(SettingsUpdateRequestDto::class.java) }
    private val statusResponseAdapter by lazy { moshi.adapter(StatusResponseDto::class.java) }
    private val successAdapter by lazy { moshi.adapter(SuccessResponse::class.java) }
    private val streamActionAdapter by lazy { moshi.adapter(StreamActionResponse::class.java) }
    private val captureResponseAdapter by lazy { moshi.adapter(CaptureResponse::class.java) }
    private val lensesAdapter by lazy { moshi.adapter(LensesResponseDto::class.java) }
    private val lensSelectAdapter by lazy { moshi.adapter(LensSelectRequest::class.java) }
    private val intervalStatusAdapter by lazy { moshi.adapter(IntervalCaptureStatusDto::class.java) }
    private val intervalConfigAdapter by lazy {
        moshi.adapter(com.raulshma.lenscast.capture.model.IntervalCaptureConfig::class.java)
    }
    private val recordingStatusAdapter by lazy { moshi.adapter(RecordingStatusDto::class.java) }
    private val recordingConfigAdapter by lazy {
        moshi.adapter(com.raulshma.lenscast.capture.model.RecordingConfig::class.java)
    }
    private val galleryAdapter by lazy { moshi.adapter(GalleryResponseDto::class.java) }
    private val batchDeleteRequestAdapter by lazy { moshi.adapter(BatchDeleteRequest::class.java) }
    private val batchDeleteResponseAdapter by lazy { moshi.adapter(BatchDeleteResponse::class.java) }
    private val errorAdapter by lazy { moshi.adapter(ErrorResponse::class.java) }

    // ── Issue 2.2 fix: Replace @Volatile cached fields with StateFlow.value ──
    // StateFlow inherently holds the latest value synchronously, eliminating duplication.
    private val settingsFlow: StateFlow<CameraSettings> =
        app.settingsDataStore.settings.stateIn(scope, SharingStarted.Eagerly, CameraSettings())

    private val portFlow: StateFlow<Int> =
        app.settingsDataStore.streamingPort.stateIn(scope, SharingStarted.Eagerly, StreamingServer.DEFAULT_PORT)

    private val jpegQualityFlow: StateFlow<Int> =
        app.settingsDataStore.jpegQuality.stateIn(scope, SharingStarted.Eagerly, StreamingSettingsDto.DEFAULT_JPEG_QUALITY)

    private val webStreamingEnabledFlow: StateFlow<Boolean> =
        app.settingsDataStore.webStreamingEnabled.stateIn(scope, SharingStarted.Eagerly, true)

    private val showPreviewFlow: StateFlow<Boolean> =
        app.settingsDataStore.showPreview.stateIn(scope, SharingStarted.Eagerly, true)

    private val streamAudioEnabledFlow: StateFlow<Boolean> =
        app.settingsDataStore.streamAudioEnabled.stateIn(scope, SharingStarted.Eagerly, true)

    private val streamAudioBitrateFlow: StateFlow<Int> =
        app.settingsDataStore.streamAudioBitrateKbps.stateIn(scope, SharingStarted.Eagerly, StreamingSettingsDto.DEFAULT_AUDIO_BITRATE_KBPS)

    private val streamAudioChannelsFlow: StateFlow<Int> =
        app.settingsDataStore.streamAudioChannels.stateIn(scope, SharingStarted.Eagerly, 1)

    private val streamAudioEchoCancellationFlow: StateFlow<Boolean> =
        app.settingsDataStore.streamAudioEchoCancellation.stateIn(scope, SharingStarted.Eagerly, true)

    private val recordingAudioEnabledFlow: StateFlow<Boolean> =
        app.settingsDataStore.recordingAudioEnabled.stateIn(scope, SharingStarted.Eagerly, true)

    private val rtspEnabledFlow: StateFlow<Boolean> =
        app.settingsDataStore.rtspEnabled.stateIn(scope, SharingStarted.Eagerly, false)

    private val rtspPortFlow: StateFlow<Int> =
        app.settingsDataStore.rtspPort.stateIn(scope, SharingStarted.Eagerly, StreamingSettingsDto.DEFAULT_RTSP_PORT)

    private val rtspInputFormatFlow: StateFlow<RtspInputFormat> =
        app.settingsDataStore.rtspInputFormat.stateIn(scope, SharingStarted.Eagerly, RtspInputFormat.AUTO)

    private val adaptiveBitrateEnabledFlow: StateFlow<Boolean> =
        app.settingsDataStore.adaptiveBitrateEnabled.stateIn(scope, SharingStarted.Eagerly, false)

    private val overlaySettingsFlow: StateFlow<OverlaySettings> =
        app.settingsDataStore.overlaySettings.stateIn(scope, SharingStarted.Eagerly, OverlaySettings())

    private val watchdogEnabledFlow: StateFlow<Boolean> =
        app.settingsDataStore.watchdogEnabled.stateIn(scope, SharingStarted.Eagerly, false)

    private val watchdogMaxRetriesFlow: StateFlow<Int> =
        app.settingsDataStore.watchdogMaxRetries.stateIn(scope, SharingStarted.Eagerly, 5)

    private val watchdogCheckIntervalFlow: StateFlow<Int> =
        app.settingsDataStore.watchdogCheckIntervalSeconds.stateIn(scope, SharingStarted.Eagerly, 5)

    private var scheduledRecordingJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var scheduledStartTimeMs: Long? = null

    // ── Issue 2.1 fix: Type-safe settings serialization via DTOs ──

    fun handleGetSettings(): String {
        return try {
            val settings = settingsFlow.value
            val response = SettingsResponseDto(
                camera = CameraSettingsDto(
                    exposureCompensation = settings.exposureCompensation,
                    iso = settings.iso,
                    exposureTime = settings.exposureTime,
                    focusMode = settings.focusMode.name,
                    focusDistance = settings.focusDistance,
                    whiteBalance = settings.whiteBalance.name,
                    colorTemperature = settings.colorTemperature,
                    zoomRatio = settings.zoomRatio.toDouble(),
                    frameRate = settings.frameRate,
                    resolution = settings.resolution.name,
                    stabilization = settings.stabilization,
                    hdrMode = settings.hdrMode.name,
                    sceneMode = settings.sceneMode,
                    nightVisionMode = settings.nightVisionMode.name,
                ),
                streaming = StreamingSettingsDto(
                    port = portFlow.value,
                    webStreamingEnabled = webStreamingEnabledFlow.value,
                    jpegQuality = jpegQualityFlow.value,
                    showPreview = showPreviewFlow.value,
                    streamAudioEnabled = streamAudioEnabledFlow.value,
                    streamAudioBitrateKbps = streamAudioBitrateFlow.value,
                    streamAudioChannels = streamAudioChannelsFlow.value,
                    streamAudioEchoCancellation = streamAudioEchoCancellationFlow.value,
                    recordingAudioEnabled = recordingAudioEnabledFlow.value,
                    rtspEnabled = rtspEnabledFlow.value,
                    rtspPort = rtspPortFlow.value,
                    rtspInputFormat = rtspInputFormatFlow.value.name,
                    adaptiveBitrateEnabled = adaptiveBitrateEnabledFlow.value,
                    overlayEnabled = overlaySettingsFlow.value.enabled,
                    showTimestamp = overlaySettingsFlow.value.showTimestamp,
                    timestampFormat = overlaySettingsFlow.value.timestampFormat,
                    showBranding = overlaySettingsFlow.value.showBranding,
                    brandingText = overlaySettingsFlow.value.brandingText,
                    showStatus = overlaySettingsFlow.value.showStatus,
                    showCustomText = overlaySettingsFlow.value.showCustomText,
                    customText = overlaySettingsFlow.value.customText,
                    overlayPosition = overlaySettingsFlow.value.position.name,
                    overlayFontSize = overlaySettingsFlow.value.fontSize,
                    overlayTextColor = overlaySettingsFlow.value.textColor,
                    overlayBackgroundColor = overlaySettingsFlow.value.backgroundColor,
                    overlayPadding = overlaySettingsFlow.value.padding,
                    overlayLineHeight = overlaySettingsFlow.value.lineHeight,
                    maskingEnabled = overlaySettingsFlow.value.maskingEnabled,
                    maskingZones = overlaySettingsFlow.value.maskingZones.map { zone ->
                        com.raulshma.lenscast.streaming.model.MaskingZoneDto(
                            id = zone.id,
                            label = zone.label,
                            enabled = zone.enabled,
                            type = zone.type.name,
                            x = zone.x.toDouble(),
                            y = zone.y.toDouble(),
                            width = zone.width.toDouble(),
                            height = zone.height.toDouble(),
                            pixelateSize = zone.pixelateSize,
                            blurRadius = zone.blurRadius.toDouble(),
                        )
                    },
                    watchdogEnabled = watchdogEnabledFlow.value,
                    watchdogMaxRetries = watchdogMaxRetriesFlow.value,
                    watchdogCheckIntervalSeconds = watchdogCheckIntervalFlow.value,
                ),
            )
            settingsResponseAdapter.toJson(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get settings", e)
            errorJson(e)
        }
    }

    fun handlePutSettings(body: String): String {
        return try {
            val request = settingsUpdateAdapter.fromJson(body)
                ?: return errorJson(IllegalArgumentException("Invalid settings JSON"))

            if (request.camera != null) {
                val cam = request.camera
                val current = settingsFlow.value
                val newSettings = CameraSettings(
                    exposureCompensation = cam.exposureCompensation.coerceIn(-12, 12),
                    iso = cam.iso?.let { if (it > 0) it else null },
                    exposureTime = cam.exposureTime?.let { if (it > 0) it else null },
                    focusMode = try {
                        FocusMode.valueOf(cam.focusMode)
                    } catch (_: Exception) {
                        current.focusMode
                    },
                    focusDistance = cam.focusDistance?.coerceIn(0f, 20f),
                    whiteBalance = try {
                        WhiteBalance.valueOf(cam.whiteBalance)
                    } catch (_: Exception) {
                        current.whiteBalance
                    },
                    colorTemperature = cam.colorTemperature?.coerceIn(1000, 15000),
                    zoomRatio = cam.zoomRatio.toFloat().coerceIn(0.1f, 10f),
                    frameRate = cam.frameRate.coerceIn(1, 120),
                    resolution = try {
                        Resolution.valueOf(cam.resolution)
                    } catch (_: Exception) {
                        current.resolution
                    },
                    stabilization = cam.stabilization,
                    hdrMode = try {
                        HdrMode.valueOf(cam.hdrMode)
                    } catch (_: Exception) {
                        current.hdrMode
                    },
                    sceneMode = cam.sceneMode,
                    nightVisionMode = try {
                        NightVisionMode.valueOf(cam.nightVisionMode)
                    } catch (_: Exception) {
                        current.nightVisionMode
                    },
                )
                scope.launch {
                    app.settingsDataStore.saveSettings(newSettings)
                    withContext(Dispatchers.Main) {
                        app.cameraService.applySettings(newSettings)
                    }
                }
            }

            if (request.streaming != null) {
                val stream = request.streaming
                scope.launch {
                    if (stream.port in 1024..65535) {
                        app.settingsDataStore.saveStreamingPort(stream.port)
                    }
                    app.settingsDataStore.saveWebStreamingEnabled(stream.webStreamingEnabled)
                    app.streamingManager.setWebStreamingEnabled(stream.webStreamingEnabled)
                    if (stream.jpegQuality > 0) {
                        app.settingsDataStore.saveJpegQuality(stream.jpegQuality)
                        app.streamingManager.setJpegQuality(stream.jpegQuality)
                    }
                    app.settingsDataStore.saveShowPreview(stream.showPreview)
                    app.settingsDataStore.saveStreamAudioEnabled(stream.streamAudioEnabled)
                    app.streamingManager.setStreamAudioEnabled(stream.streamAudioEnabled)
                    if (stream.streamAudioBitrateKbps > 0) {
                        app.settingsDataStore.saveStreamAudioBitrateKbps(stream.streamAudioBitrateKbps)
                        app.streamingManager.setStreamAudioBitrateKbps(stream.streamAudioBitrateKbps)
                    }
                    if (stream.streamAudioChannels > 0) {
                        app.settingsDataStore.saveStreamAudioChannels(stream.streamAudioChannels)
                        app.streamingManager.setStreamAudioChannels(stream.streamAudioChannels)
                    }
                    app.settingsDataStore.saveStreamAudioEchoCancellation(stream.streamAudioEchoCancellation)
                    app.streamingManager.setStreamAudioEchoCancellation(stream.streamAudioEchoCancellation)
                    app.settingsDataStore.saveRecordingAudioEnabled(stream.recordingAudioEnabled)
                    app.settingsDataStore.saveRtspEnabled(stream.rtspEnabled)
                    app.streamingManager.setRtspEnabled(stream.rtspEnabled)
                    if (stream.rtspPort in 1024..65535) {
                        app.settingsDataStore.saveRtspPort(stream.rtspPort)
                        app.streamingManager.setRtspPort(stream.rtspPort)
                    }
                    if (stream.rtspInputFormat.isNotBlank()) {
                        val format = runCatching { RtspInputFormat.valueOf(stream.rtspInputFormat) }.getOrNull()
                        if (format != null) {
                            app.settingsDataStore.saveRtspInputFormat(format)
                            app.streamingManager.setRtspInputFormat(format)
                        }
                    }
                    app.settingsDataStore.saveAdaptiveBitrateEnabled(stream.adaptiveBitrateEnabled)
                    app.streamingManager.setAdaptiveBitrateEnabled(stream.adaptiveBitrateEnabled)

                    val currentOverlay = overlaySettingsFlow.value
                    val maskingZones = stream.maskingZones.map { dto ->
                        MaskingZone(
                            id = dto.id.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                            label = dto.label,
                            enabled = dto.enabled,
                            type = runCatching { MaskingType.valueOf(dto.type) }.getOrDefault(MaskingType.BLACKOUT),
                            x = dto.x.toFloat().coerceIn(0f, 1f),
                            y = dto.y.toFloat().coerceIn(0f, 1f),
                            width = dto.width.toFloat().coerceIn(0.01f, 1f),
                            height = dto.height.toFloat().coerceIn(0.01f, 1f),
                            pixelateSize = dto.pixelateSize.coerceIn(4, 64),
                            blurRadius = dto.blurRadius.toFloat().coerceIn(1f, 50f),
                        )
                    }
                    val newOverlay = OverlaySettings(
                        enabled = stream.overlayEnabled,
                        showTimestamp = stream.showTimestamp,
                        timestampFormat = stream.timestampFormat.takeIf { it.isNotBlank() } ?: currentOverlay.timestampFormat,
                        showBranding = stream.showBranding,
                        brandingText = stream.brandingText,
                        showStatus = stream.showStatus,
                        showCustomText = stream.showCustomText,
                        customText = stream.customText,
                        position = runCatching { OverlayPosition.valueOf(stream.overlayPosition) }.getOrDefault(currentOverlay.position),
                        fontSize = stream.overlayFontSize.coerceIn(8, 120),
                        textColor = stream.overlayTextColor.takeIf { it.isNotBlank() } ?: currentOverlay.textColor,
                        backgroundColor = stream.overlayBackgroundColor.takeIf { it.isNotBlank() } ?: currentOverlay.backgroundColor,
                        padding = stream.overlayPadding.coerceIn(0, 48),
                        lineHeight = stream.overlayLineHeight.coerceIn(0, 32),
                        maskingEnabled = stream.maskingEnabled,
                        maskingZones = maskingZones,
                    )
                    app.settingsDataStore.saveOverlaySettings(newOverlay)
                    app.streamingManager.setOverlaySettings(newOverlay)

                    // Watchdog settings
                    app.settingsDataStore.saveWatchdogEnabled(stream.watchdogEnabled)
                    app.streamWatchdog.setEnabled(stream.watchdogEnabled)
                    app.settingsDataStore.saveWatchdogMaxRetries(stream.watchdogMaxRetries)
                    app.streamWatchdog.setMaxRetries(stream.watchdogMaxRetries)
                    app.settingsDataStore.saveWatchdogCheckIntervalSeconds(stream.watchdogCheckIntervalSeconds)
                    app.streamWatchdog.setCheckIntervalSeconds(stream.watchdogCheckIntervalSeconds)
                }
            }

            successAdapter.toJson(SuccessResponse())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update settings", e)
            errorJson(e)
        }
    }

    fun handleGetStatus(): String {
        return try {
            val thermal = app.thermalMonitor.thermalState.value
            val battery = app.powerManager.batteryLevel.value
            val isCharging = app.powerManager.isCharging.value
            val isPowerSave = app.powerManager.isPowerSaveMode.value
            val clientCount = app.streamingManager.clientCount.value
            val isLiveStreaming = app.streamingManager.isLiveStreaming()
            val streamUrl = app.streamingManager.streamUrl.value
            val isAudioStreaming = app.streamingManager.isAudioStreaming.value
            val audioUrl = app.streamingManager.audioStreamUrl.value

            val adaptiveState = app.streamingManager.adaptiveBitrateState.value
            val adaptiveBitrateDto = if (adaptiveState.enabled) {
                AdaptiveBitrateStatusDto(
                    enabled = adaptiveState.enabled,
                    qualityLevel = adaptiveState.qualityLevel.name,
                    currentQuality = adaptiveState.currentQuality,
                    targetQuality = adaptiveState.targetQuality,
                    currentFps = adaptiveState.currentFps,
                    targetFps = adaptiveState.targetFps,
                    estimatedBandwidthKbps = adaptiveState.estimatedBandwidthKbps,
                    minClientThroughputKbps = adaptiveState.minClientThroughputKbps,
                    activeClients = adaptiveState.activeClients,
                    adjustmentCount = adaptiveState.adjustmentCount,
                )
            } else {
                null
            }

            val networkStats = app.streamingManager.getNetworkStatsSnapshot()
            val connectionQualityDto = if (app.streamingManager.isLiveStreaming()) {
                val clientDetailDtos = networkStats.clientDetails.mapValues { (_, detail) ->
                    ClientConnectionDetailDto(
                        framesSent = detail.framesSent,
                        bytesSent = detail.bytesSent,
                        avgThroughputKbps = detail.avgThroughputKbps,
                        lastFrameSizeBytes = detail.lastFrameSizeBytes,
                        lastSendDurationMs = detail.lastSendDurationMs,
                    )
                }
                ConnectionQualityStatusDto(
                    qualityLevel = networkStats.qualityLevel.name,
                    estimatedBandwidthKbps = networkStats.estimatedBandwidthKbps,
                    avgThroughputKbps = networkStats.avgThroughputKbps,
                    minThroughputKbps = networkStats.minThroughputKbps,
                    worstLatencyMs = networkStats.worstLatencyMs,
                    avgFrameSizeBytes = networkStats.avgFrameSizeBytes,
                    totalBytesSent = networkStats.totalBytesSent,
                    activeClients = networkStats.activeClients,
                    framesPerSecond = if (networkStats.activeClients > 0 && networkStats.clientDetails.isNotEmpty()) {
                        app.streamingManager.networkQualityMonitor.getFramesPerSecond(networkStats.clientDetails.keys.first())
                    } else {
                        0.0
                    },
                    clientDetails = clientDetailDtos,
                )
            } else {
                null
            }

            val response = StatusResponseDto(
                streaming = StreamingStatusDto(
                    isActive = isLiveStreaming,
                    url = streamUrl,
                    webStreamingEnabled = webStreamingEnabledFlow.value,
                    webStreamingActive = app.streamingManager.isWebStreamActive(),
                    clientCount = clientCount,
                    audioEnabled = isAudioStreaming,
                    audioUrl = audioUrl,
                    rtspEnabled = app.streamingManager.isRtspEnabled.value,
                    rtspStreamingActive = app.streamingManager.isRtspRunning.value,
                    rtspUrl = app.streamingManager.rtspUrl.value,
                ),
                thermal = thermal.name,
                camera = app.cameraService.cameraState.value.toString(),
                battery = BatteryStatusDto(
                    level = battery,
                    isCharging = isCharging,
                    isPowerSaveMode = isPowerSave,
                ),
                adaptiveBitrate = adaptiveBitrateDto,
                connectionQuality = connectionQualityDto,
                watchdog = run {
                    val wdState = app.streamWatchdog.state.value
                    WatchdogStatusDto(
                        enabled = wdState.enabled,
                        status = wdState.status.name,
                        consecutiveFailures = wdState.consecutiveFailures,
                        totalRecoveries = wdState.totalRecoveries,
                        lastRecoveryTimestamp = wdState.lastRecoveryTimestamp,
                        lastFailureReason = wdState.lastFailureReason,
                    )
                },
            )
            statusResponseAdapter.toJson(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get status", e)
            errorJson(e)
        }
    }

    fun handleStartStream(): String {
        return try {
            val wasLiveStreaming = app.streamingManager.isLiveStreaming()
            val wasServerRunning = app.streamingManager.isServerRunning.value
            if (!wasLiveStreaming) {
                app.powerManager.refreshBatteryState()
                app.powerManager.acquireWakeLock()
                app.thermalMonitor.startMonitoring()
                app.streamingManager.thermalMonitor = app.thermalMonitor
                app.streamingManager.applyBatteryOptimization(app.powerManager.optimizationResult.value)

                val success = app.streamingManager.startStreaming()
                if (!success) {
                    return streamActionAdapter.toJson(
                        StreamActionResponse(success = false, error = "Failed to start streaming server")
                    )
                }

                runBlocking {
                    withTimeoutOrNull(2000L) {
                        withContext(Dispatchers.Main) {
                            app.cameraService.acquireKeepAlive()
                            app.cameraService.rebindUseCases()
                        }
                    }
                }

                val intent = Intent(context, StreamingService::class.java).apply {
                    action = StreamingService.ACTION_START
                    putExtra(StreamingService.EXTRA_URL, app.streamingManager.streamUrl.value)
                    putExtra(StreamingService.EXTRA_AUDIO_ACTIVE, app.streamingManager.isAudioStreaming.value)
                }
                val shouldStartForeground = !wasServerRunning
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && shouldStartForeground) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }

            // Start watchdog if enabled
            app.streamWatchdog.startMonitoring()

            val streamUrl = app.streamingManager.streamUrl.value
            streamActionAdapter.toJson(StreamActionResponse(success = true, isActive = true, url = streamUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start stream", e)
            errorJson(e)
        }
    }

    private fun ensureCameraAndForeground() {
        runBlocking {
            withTimeoutOrNull(2000L) {
                withContext(Dispatchers.Main) {
                    app.cameraService.acquireKeepAlive()
                    app.cameraService.rebindUseCases()
                }
            }
        }

        val wasServerRunning = app.streamingManager.isServerRunning.value
        val intent = Intent(context, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_URL, app.streamingManager.streamUrl.value)
            putExtra(StreamingService.EXTRA_AUDIO_ACTIVE, app.streamingManager.isAudioStreaming.value)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !wasServerRunning) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        // Start watchdog if enabled
        app.streamWatchdog.startMonitoring()
    }

    private fun releaseCameraIfNeeded() {
        if (!app.streamingManager.isLiveStreaming()) {
            app.streamWatchdog.stopMonitoring()
            app.powerManager.releaseWakeLock()
            app.thermalMonitor.stopMonitoring()

            runBlocking {
                withTimeoutOrNull(2000L) {
                    withContext(Dispatchers.Main) {
                        app.cameraService.releaseKeepAlive()
                        app.cameraService.rebindUseCases()
                    }
                }
            }

            val intent = Intent(context, StreamingService::class.java).apply {
                action = StreamingService.ACTION_PAUSE
                putExtra(StreamingService.EXTRA_URL, app.streamingManager.streamUrl.value)
            }
            context.startService(intent)
        }
    }

    fun handleStartWebStream(): String {
        return try {
            if (!app.streamingManager.isWebEnabled.value) {
                return streamActionAdapter.toJson(
                    StreamActionResponse(success = false, error = "Web streaming is disabled")
                )
            }

            if (!app.streamingManager.isLiveStreaming()) {
                app.powerManager.refreshBatteryState()
                app.powerManager.acquireWakeLock()
                app.thermalMonitor.startMonitoring()
                app.streamingManager.thermalMonitor = app.thermalMonitor
                app.streamingManager.applyBatteryOptimization(app.powerManager.optimizationResult.value)
            }

            val success = app.streamingManager.startWebStreaming()
            if (!success) {
                return streamActionAdapter.toJson(
                    StreamActionResponse(success = false, error = "Failed to start web streaming")
                )
            }

            ensureCameraAndForeground()

            streamActionAdapter.toJson(StreamActionResponse(
                success = true,
                isActive = app.streamingManager.isLiveStreaming(),
                url = app.streamingManager.streamUrl.value,
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web stream", e)
            errorJson(e)
        }
    }

    fun handleStopWebStream(): String {
        return try {
            app.streamingManager.stopWebStreaming()
            releaseCameraIfNeeded()
            streamActionAdapter.toJson(StreamActionResponse(
                success = true,
                isActive = app.streamingManager.isLiveStreaming(),
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop web stream", e)
            errorJson(e)
        }
    }

    fun handleStartRtspStream(): String {
        return try {
            if (!app.streamingManager.isRtspEnabled.value) {
                return streamActionAdapter.toJson(
                    StreamActionResponse(success = false, error = "RTSP streaming is disabled")
                )
            }

            if (!app.streamingManager.isLiveStreaming()) {
                app.powerManager.refreshBatteryState()
                app.powerManager.acquireWakeLock()
                app.thermalMonitor.startMonitoring()
                app.streamingManager.thermalMonitor = app.thermalMonitor
                app.streamingManager.applyBatteryOptimization(app.powerManager.optimizationResult.value)
            }

            val success = app.streamingManager.startRtspStreaming()
            if (!success) {
                return streamActionAdapter.toJson(
                    StreamActionResponse(success = false, error = "Failed to start RTSP streaming")
                )
            }

            ensureCameraAndForeground()

            streamActionAdapter.toJson(StreamActionResponse(
                success = true,
                isActive = app.streamingManager.isLiveStreaming(),
                url = app.streamingManager.rtspUrl.value,
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP stream", e)
            errorJson(e)
        }
    }

    fun handleStopRtspStream(): String {
        return try {
            app.streamingManager.stopRtspStreaming()
            releaseCameraIfNeeded()
            streamActionAdapter.toJson(StreamActionResponse(
                success = true,
                isActive = app.streamingManager.isLiveStreaming(),
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop RTSP stream", e)
            errorJson(e)
        }
    }

    fun handleStopStream(): String {
        return try {
            app.streamingManager.pauseStreaming()
            releaseCameraIfNeeded()
            streamActionAdapter.toJson(StreamActionResponse(success = true, isActive = false))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop stream", e)
            errorJson(e)
        }
    }

    fun handleCapture(): String {
        return try {
            val imageCapture = runBlocking {
                withTimeoutOrNull(2000L) {
                    withContext(Dispatchers.Main) {
                        app.cameraService.acquirePhotoCapture()
                    }
                }
            }
            if (imageCapture == null) {
                captureResponseAdapter.toJson(CaptureResponse(success = false, error = "Camera not available"))
            } else {
                val fileName = PhotoCaptureHelper.generateFileName()
                PhotoCaptureHelper.takePhoto(
                    context, imageCapture, fileName,
                    onSaved = { filePath, fileSizeBytes ->
                        val entry = app.captureHistoryStore.createPhotoEntry(
                            fileName = fileName,
                            filePath = filePath,
                            fileSizeBytes = fileSizeBytes,
                        )
                        app.captureHistoryStore.add(entry)
                        app.cameraService.releasePhotoCapture()
                        Log.d(TAG, "Photo captured via web: $fileName")
                    },
                    onError = { exc ->
                        app.cameraService.releasePhotoCapture()
                        Log.e(TAG, "Web capture failed", exc)
                    },
                )
                captureResponseAdapter.toJson(CaptureResponse(success = true, fileName = fileName))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            errorJson(e)
        }
    }

    fun handleGetLenses(): String {
        return try {
            val lenses = app.cameraService.availableLenses.value
            val selectedIndex = app.cameraService.selectedLensIndex.value

            val lensDtos = lenses.mapIndexed { index, lens ->
                LensDto(
                    index = index,
                    id = lens.id,
                    label = lens.label,
                    focalLength = lens.focalLength.toDouble(),
                    isFront = lens.lensFacing == CameraSelector.LENS_FACING_FRONT,
                    selected = index == selectedIndex,
                )
            }

            lensesAdapter.toJson(LensesResponseDto(lenses = lensDtos, selectedIndex = selectedIndex))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get lenses", e)
            errorJson(e)
        }
    }

    fun handleSelectLens(body: String): String {
        return try {
            val request = lensSelectAdapter.fromJson(body)
                ?: return errorJson(IllegalArgumentException("Invalid lens selection JSON"))
            runBlocking {
                withContext(Dispatchers.Main) {
                    app.cameraService.selectLens(request.index)
                }
            }
            successAdapter.toJson(SuccessResponse())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select lens", e)
            errorJson(e)
        }
    }

    fun handleGetIntervalCaptureStatus(): String {
        return try {
            val snapshot = IntervalCaptureScheduler.getStatus(context)
            intervalStatusAdapter.toJson(
                IntervalCaptureStatusDto(
                    isRunning = snapshot.isRunning,
                    completedCaptures = snapshot.completedCaptures,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get interval capture status", e)
            errorJson(e)
        }
    }

    fun handleStartIntervalCapture(body: String): String {
        return try {
            val config = intervalConfigAdapter.fromJson(if (body.isNotEmpty()) body else "{}")
                ?: com.raulshma.lenscast.capture.model.IntervalCaptureConfig()
            val intervalSeconds = config.intervalSeconds
            val totalCaptures = config.totalCaptures
            val imageQuality = config.imageQuality

            IntervalCaptureScheduler.start(
                context = context,
                intervalSeconds = intervalSeconds.coerceIn(1, 3600),
                totalCaptures = totalCaptures,
                imageQuality = imageQuality,
                flashMode = config.flashMode.name,
                completedCaptures = 0,
            )

            Log.d(
                TAG,
                "Interval capture started: every ${intervalSeconds}s, total=$totalCaptures, flash=${config.flashMode}"
            )
            successAdapter.toJson(SuccessResponse())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start interval capture", e)
            errorJson(e)
        }
    }

    fun handleStopIntervalCapture(): String {
        return try {
            IntervalCaptureScheduler.stop(context)
            Log.d(TAG, "Interval capture stopped")
            successAdapter.toJson(SuccessResponse())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop interval capture", e)
            errorJson(e)
        }
    }

    fun handleGetRecordingStatus(): String {
        return try {
            val isRecording = RecordingService.isRecordingActive()
            val recordingStartTime = RecordingService.recordingStartTimeMs()
            if (isRecording && scheduledStartTimeMs != null) {
                scheduledStartTimeMs = null
            }
            val elapsed = if (isRecording && recordingStartTime > 0L) {
                ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
            } else {
                0
            }
            recordingStatusAdapter.toJson(
                RecordingStatusDto(
                    isRecording = isRecording, 
                    elapsedSeconds = elapsed,
                    isScheduled = !isRecording && scheduledStartTimeMs != null,
                    scheduledStartTimeMs = scheduledStartTimeMs
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recording status", e)
            errorJson(e)
        }
    }

    fun handleStartRecording(body: String): String {
        return try {
            val configJson = if (body.isNotEmpty()) body else "{}"
            val config = recordingConfigAdapter.fromJson(configJson)
                ?: com.raulshma.lenscast.capture.model.RecordingConfig()
            
            scheduledRecordingJob?.cancel()
            scheduledRecordingJob = null
            scheduledStartTimeMs = null

            val delayMs = if (config.startTimeMs != null && config.startTimeMs > System.currentTimeMillis()) {
                config.startTimeMs - System.currentTimeMillis()
            } else 0L

            if (delayMs > 0) {
                scheduledStartTimeMs = config.startTimeMs
                scheduledRecordingJob = scope.launch {
                    kotlinx.coroutines.delay(delayMs)
                    val intent = Intent(context, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_START
                        putExtra(RecordingService.EXTRA_CONFIG, configJson)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    scheduledStartTimeMs = null
                    Log.d(TAG, "Scheduled recording started")
                }
                Log.d(TAG, "Recording scheduled in $delayMs ms")
                successAdapter.toJson(SuccessResponse())
            } else {
                val intent = Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_START
                    putExtra(RecordingService.EXTRA_CONFIG, configJson)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Recording started")
                successAdapter.toJson(SuccessResponse())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            errorJson(e)
        }
    }

    fun handleStopRecording(): String {
        return try {
            scheduledRecordingJob?.cancel()
            scheduledRecordingJob = null
            scheduledStartTimeMs = null

            val intent = Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
            context.startService(intent)
            Log.d(TAG, "Recording stopped")
            successAdapter.toJson(SuccessResponse())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            errorJson(e)
        }
    }

    fun handleTapFocus(body: String): String {
        return try {
            val request = moshi.adapter(TapFocusRequest::class.java).fromJson(body)
                ?: return errorJson(IllegalArgumentException("Invalid tap focus JSON"))

            val x = request.x.toFloat().coerceIn(0f, 1f)
            val y = request.y.toFloat().coerceIn(0f, 1f)

            runBlocking {
                withContext(Dispatchers.Main) {
                    app.cameraService.tapToFocus(x, y)
                }
            }

            Log.d(TAG, "Tap focus: x=$x, y=$y")
            successAdapter.toJson(SuccessResponse())
        } catch (e: Exception) {
            Log.e(TAG, "Tap focus failed", e)
            errorJson(e)
        }
    }

    fun handleGetGallery(type: String?, page: Int = 0, pageSize: Int = 0): String {
        return try {
            app.captureHistoryStore.refreshFromMediaStore()
            val history = app.captureHistoryStore.history.value
            val filtered = when (type?.uppercase()) {
                "PHOTO" -> history.filter { it.type == com.raulshma.lenscast.capture.model.CaptureType.PHOTO }
                "VIDEO" -> history.filter { it.type == com.raulshma.lenscast.capture.model.CaptureType.VIDEO }
                else -> history
            }

            val effectivePageSize = if (pageSize > 0) pageSize else DEFAULT_GALLERY_PAGE_SIZE
            val hasMore = if (effectivePageSize > 0) {
                page * effectivePageSize + effectivePageSize < filtered.size
            } else false

            val paged = if (effectivePageSize > 0 && page >= 0) {
                filtered.drop(page * effectivePageSize).take(effectivePageSize)
            } else {
                filtered
            }

            val items = paged.map { entry ->
                val isVideo = entry.type == com.raulshma.lenscast.capture.model.CaptureType.VIDEO
                GalleryItemDto(
                    id = entry.id,
                    type = entry.type.name,
                    fileName = entry.fileName,
                    timestamp = entry.timestamp,
                    fileSizeBytes = entry.fileSizeBytes,
                    durationMs = entry.durationMs,
                    thumbnailUrl = if (isVideo) "/api/media/${entry.id}/thumbnail" else "/api/media/${entry.id}",
                    downloadUrl = "/api/media/${entry.id}?download=1",
                )
            }
            galleryAdapter.toJson(GalleryResponseDto(items = items, total = filtered.size, page = page, pageSize = effectivePageSize, hasMore = hasMore))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get gallery", e)
            errorJson(e)
        }
    }

    fun handleDeleteMedia(id: String): String {
        return try {
            val history = app.captureHistoryStore.history.value
            val entry = history.find { it.id == id }
            if (entry == null) {
                errorAdapter.toJson(ErrorResponse(error = "Media not found"))
            } else {
                app.captureHistoryStore.deleteMedia(id)
                successAdapter.toJson(SuccessResponse())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete media", e)
            errorJson(e)
        }
    }

    fun handleHighResSnapshot(saveToDisk: Boolean = false): SnapshotResult {
        return try {
            val imageCapture = runBlocking {
                withTimeoutOrNull(2000L) {
                    withContext(Dispatchers.Main) {
                        app.cameraService.acquirePhotoCapture()
                    }
                }
            }
            if (imageCapture == null) {
                SnapshotResult.Error("Camera not available")
            } else {
                val snapshotLatch = java.util.concurrent.CountDownLatch(1)
                var snapshotBytes: ByteArray? = null
                var snapshotError: Exception? = null
                var savedFilePath: String? = null

                val executor = ContextCompat.getMainExecutor(context)

                runBlocking {
                    withContext(Dispatchers.Main) {
                        if (saveToDisk) {
                            val fileName = PhotoCaptureHelper.generateFileName()
                            PhotoCaptureHelper.takePhoto(
                                context, imageCapture, fileName,
                                onSaved = { filePath, fileSizeBytes ->
                                    val entry = app.captureHistoryStore.createPhotoEntry(
                                        fileName = fileName,
                                        filePath = filePath,
                                        fileSizeBytes = fileSizeBytes,
                                    )
                                    app.captureHistoryStore.add(entry)
                                    savedFilePath = filePath
                                    loadCapturedBytes(filePath, context) { bytes ->
                                        snapshotBytes = bytes
                                        snapshotLatch.countDown()
                                    }
                                },
                                onError = { exc ->
                                    snapshotError = exc
                                    snapshotLatch.countDown()
                                    app.cameraService.releasePhotoCapture()
                                },
                            )
                        } else {
                            val tempFile = java.io.File.createTempFile("lenscast_snapshot_", ".jpg", context.cacheDir)
                            val outputOptions = androidx.camera.core.ImageCapture.OutputFileOptions.Builder(tempFile).build()
                            imageCapture.takePicture(
                                outputOptions, executor,
                                object : androidx.camera.core.ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: androidx.camera.core.ImageCapture.OutputFileResults) {
                                        snapshotBytes = tempFile.readBytes()
                                        tempFile.delete()
                                        snapshotLatch.countDown()
                                    }
                                    override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                                        snapshotError = exception
                                        tempFile.delete()
                                        snapshotLatch.countDown()
                                    }
                                }
                            )
                        }
                    }
                }

                val acquired = snapshotLatch.await(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                app.cameraService.releasePhotoCapture()

                if (!acquired) {
                    SnapshotResult.Error("Snapshot timed out")
                } else if (snapshotError != null) {
                    SnapshotResult.Error("Snapshot failed: ${snapshotError?.message}")
                } else if (snapshotBytes != null) {
                    SnapshotResult.Success(snapshotBytes!!, savedFilePath)
                } else {
                    SnapshotResult.Error("No image data returned")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "High-res snapshot failed", e)
            SnapshotResult.Error("Snapshot error: ${e.message}")
        }
    }

    private fun loadCapturedBytes(filePath: String, context: Context, callback: (ByteArray?) -> Unit) {
        try {
            if (filePath.startsWith("content://")) {
                val uri = android.net.Uri.parse(filePath)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    callback(input.readBytes())
                } ?: callback(null)
            } else {
                val file = java.io.File(filePath)
                callback(if (file.exists()) file.readBytes() else null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load captured bytes", e)
            callback(null)
        }
    }

    sealed class SnapshotResult {
        data class Success(val data: ByteArray, val savedPath: String? = null) : SnapshotResult()
        data class Error(val message: String) : SnapshotResult()
    }

    fun handleBatchDeleteMedia(body: String): String {
        return try {
            val request = batchDeleteRequestAdapter.fromJson(body)
                ?: return errorJson(IllegalArgumentException("Invalid batch delete JSON"))
            val deleted = app.captureHistoryStore.deleteMediaBatch(request.ids)
            batchDeleteResponseAdapter.toJson(BatchDeleteResponse(deleted = deleted))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch delete media", e)
            errorJson(e)
        }
    }

    fun resolveMediaFile(id: String): Triple<java.io.InputStream, String, Long>? {
        val history = app.captureHistoryStore.history.value
        val entry = history.find { it.id == id } ?: return null
        val mimeType = when (entry.type) {
            com.raulshma.lenscast.capture.model.CaptureType.PHOTO -> "image/jpeg"
            com.raulshma.lenscast.capture.model.CaptureType.VIDEO -> "video/mp4"
        }
        val size = entry.fileSizeBytes
        return try {
            val uri = android.net.Uri.parse(entry.filePath)
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) return Triple(inputStream, mimeType, size)
            val file = java.io.File(entry.filePath)
            if (file.exists()) Triple(file.inputStream(), mimeType, file.length()) else null
        } catch (_: Exception) {
            try {
                val file = java.io.File(entry.filePath)
                if (file.exists()) Triple(file.inputStream(), mimeType, file.length()) else null
            } catch (_: Exception) { null }
        }
    }

    fun resolveVideoThumbnail(id: String): ByteArray? {
        val history = app.captureHistoryStore.history.value
        val entry = history.find { it.id == id } ?: return null
        if (entry.type != com.raulshma.lenscast.capture.model.CaptureType.VIDEO) {
            return null
        }
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                val uri = android.net.Uri.parse(entry.filePath)
                if (entry.filePath.startsWith("content://")) {
                    retriever.setDataSource(context, uri)
                } else {
                    val file = java.io.File(entry.filePath)
                    if (file.exists()) {
                        retriever.setDataSource(file.absolutePath)
                    } else {
                        retriever.setDataSource(context, uri)
                    }
                }
                val bitmap = retriever.getFrameAtTime(
                    1_000_000, // 1 second in microseconds
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: retriever.getFrameAtTime(0)
                if (bitmap != null) {
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream)
                    bitmap.recycle()
                    stream.toByteArray()
                } else {
                    null
                }
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate video thumbnail for $id", e)
            null
        }
    }

    private fun errorJson(e: Exception): String {
        val msg = e.message?.take(200)?.replace('\n', ' ') ?: "Internal error"
        return errorAdapter.toJson(ErrorResponse(error = msg))
    }

    companion object {
        private const val TAG = "WebApiController"
        private const val DEFAULT_GALLERY_PAGE_SIZE = 50
    }
}
