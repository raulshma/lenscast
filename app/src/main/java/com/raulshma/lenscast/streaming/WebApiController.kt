package com.raulshma.lenscast.streaming

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.camera.core.CameraSelector
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.capture.IntervalCaptureScheduler
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
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
                    exposureCompensation = cam.exposureCompensation,
                    iso = cam.iso,
                    exposureTime = cam.exposureTime,
                    focusMode = try {
                        FocusMode.valueOf(cam.focusMode)
                    } catch (_: Exception) {
                        current.focusMode
                    },
                    focusDistance = cam.focusDistance,
                    whiteBalance = try {
                        WhiteBalance.valueOf(cam.whiteBalance)
                    } catch (_: Exception) {
                        current.whiteBalance
                    },
                    colorTemperature = cam.colorTemperature,
                    zoomRatio = cam.zoomRatio.toFloat(),
                    frameRate = cam.frameRate,
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
                if (stream.port in 1024..65535) {
                    scope.launch { app.settingsDataStore.saveStreamingPort(stream.port) }
                }
                scope.launch {
                    app.settingsDataStore.saveWebStreamingEnabled(stream.webStreamingEnabled)
                    app.streamingManager.setWebStreamingEnabled(stream.webStreamingEnabled)
                }
                if (stream.jpegQuality > 0) {
                    scope.launch {
                        app.settingsDataStore.saveJpegQuality(stream.jpegQuality)
                        app.streamingManager.setJpegQuality(stream.jpegQuality)
                    }
                }
                scope.launch { app.settingsDataStore.saveShowPreview(stream.showPreview) }
                scope.launch {
                    app.settingsDataStore.saveStreamAudioEnabled(stream.streamAudioEnabled)
                    app.streamingManager.setStreamAudioEnabled(stream.streamAudioEnabled)
                }
                if (stream.streamAudioBitrateKbps > 0) {
                    scope.launch {
                        app.settingsDataStore.saveStreamAudioBitrateKbps(stream.streamAudioBitrateKbps)
                        app.streamingManager.setStreamAudioBitrateKbps(stream.streamAudioBitrateKbps)
                    }
                }
                if (stream.streamAudioChannels > 0) {
                    scope.launch {
                        app.settingsDataStore.saveStreamAudioChannels(stream.streamAudioChannels)
                        app.streamingManager.setStreamAudioChannels(stream.streamAudioChannels)
                    }
                }
                scope.launch {
                    app.settingsDataStore.saveStreamAudioEchoCancellation(stream.streamAudioEchoCancellation)
                    app.streamingManager.setStreamAudioEchoCancellation(stream.streamAudioEchoCancellation)
                }
                scope.launch { app.settingsDataStore.saveRecordingAudioEnabled(stream.recordingAudioEnabled) }
                scope.launch {
                    app.settingsDataStore.saveRtspEnabled(stream.rtspEnabled)
                    app.streamingManager.setRtspEnabled(stream.rtspEnabled)
                }
                if (stream.rtspPort in 1024..65535) {
                    scope.launch {
                        app.settingsDataStore.saveRtspPort(stream.rtspPort)
                        app.streamingManager.setRtspPort(stream.rtspPort)
                    }
                }
                if (stream.rtspInputFormat.isNotBlank()) {
                    val format = runCatching { RtspInputFormat.valueOf(stream.rtspInputFormat) }.getOrNull()
                    if (format != null) {
                        scope.launch {
                            app.settingsDataStore.saveRtspInputFormat(format)
                            app.streamingManager.setRtspInputFormat(format)
                        }
                    }
                }
                scope.launch {
                    app.settingsDataStore.saveAdaptiveBitrateEnabled(stream.adaptiveBitrateEnabled)
                    app.streamingManager.setAdaptiveBitrateEnabled(stream.adaptiveBitrateEnabled)
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
            val isLiveStreaming = app.streamingManager.isWebStreamingActive()
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

            val response = StatusResponseDto(
                streaming = StreamingStatusDto(
                    isActive = isLiveStreaming,
                    url = streamUrl,
                    webStreamingEnabled = webStreamingEnabledFlow.value,
                    clientCount = clientCount,
                    audioEnabled = isAudioStreaming,
                    audioUrl = audioUrl,
                    rtspEnabled = app.streamingManager.isRtspRunning.value,
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
            )
            statusResponseAdapter.toJson(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get status", e)
            errorJson(e)
        }
    }

    fun handleStartStream(): String {
        return try {
            if (!webStreamingEnabledFlow.value) {
                return streamActionAdapter.toJson(
                    StreamActionResponse(success = false, error = "Web streaming is disabled")
                )
            }

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

            val streamUrl = app.streamingManager.streamUrl.value
            streamActionAdapter.toJson(StreamActionResponse(success = true, isActive = true, url = streamUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start stream", e)
            errorJson(e)
        }
    }

    fun handleStopStream(): String {
        return try {
            val wasLiveStreaming = app.streamingManager.isLiveStreaming()
            if (wasLiveStreaming) {
                app.streamingManager.pauseStreaming()
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

    fun handleGetGallery(type: String?): String {
        return try {
            app.captureHistoryStore.refreshFromMediaStore()
            val history = app.captureHistoryStore.history.value
            val filtered = when (type?.uppercase()) {
                "PHOTO" -> history.filter { it.type == com.raulshma.lenscast.capture.model.CaptureType.PHOTO }
                "VIDEO" -> history.filter { it.type == com.raulshma.lenscast.capture.model.CaptureType.VIDEO }
                else -> history
            }
            val items = filtered.map { entry ->
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
            galleryAdapter.toJson(GalleryResponseDto(items = items, total = filtered.size))
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
    }
}
