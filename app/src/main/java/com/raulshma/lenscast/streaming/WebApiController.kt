package com.raulshma.lenscast.streaming

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.capture.IntervalCaptureScheduler
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.capture.IntervalCaptureWorker
import com.raulshma.lenscast.capture.PhotoCaptureHelper
import com.raulshma.lenscast.capture.RecordingService

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class WebApiController(private val context: Context) {

    private val app: MainApplication
        get() = context.applicationContext as MainApplication

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedSettings = CameraSettings()
    @Volatile
    private var cachedPort = 8080
    @Volatile
    private var cachedJpegQuality = 70
    @Volatile
    private var cachedShowPreview = true
    @Volatile
    private var cachedStreamAudioEnabled = true
    @Volatile
    private var cachedStreamAudioBitrateKbps = 128
    @Volatile
    private var cachedStreamAudioChannels = 1
    @Volatile
    private var cachedStreamAudioEchoCancellation = true
    @Volatile
    private var cachedRecordingAudioEnabled = true

    init {
        scope.launch { app.settingsDataStore.settings.collect { cachedSettings = it } }
        scope.launch { app.settingsDataStore.streamingPort.collect { cachedPort = it } }
        scope.launch { app.settingsDataStore.jpegQuality.collect { cachedJpegQuality = it } }
        scope.launch { app.settingsDataStore.showPreview.collect { cachedShowPreview = it } }
        scope.launch { app.settingsDataStore.streamAudioEnabled.collect { cachedStreamAudioEnabled = it } }
        scope.launch { app.settingsDataStore.streamAudioBitrateKbps.collect { cachedStreamAudioBitrateKbps = it } }
        scope.launch { app.settingsDataStore.streamAudioChannels.collect { cachedStreamAudioChannels = it } }
        scope.launch { app.settingsDataStore.streamAudioEchoCancellation.collect { cachedStreamAudioEchoCancellation = it } }
        scope.launch { app.settingsDataStore.recordingAudioEnabled.collect { cachedRecordingAudioEnabled = it } }
    }

    @Volatile
    private var isRecording = false

    @Volatile
    private var recordingStartTime: Long = 0

    private val moshi by lazy {
        com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
    }
    private val recordingConfigAdapter by lazy {
        moshi.adapter(com.raulshma.lenscast.capture.model.RecordingConfig::class.java)
    }
    private val intervalConfigAdapter by lazy {
        moshi.adapter(com.raulshma.lenscast.capture.model.IntervalCaptureConfig::class.java)
    }

    fun handleGetSettings(): String {
        return try {
            val settings = cachedSettings
            val json = JSONObject()

            val camera = JSONObject().apply {
                put("exposureCompensation", settings.exposureCompensation)
                put("iso", settings.iso ?: JSONObject.NULL)
                put("exposureTime", settings.exposureTime ?: JSONObject.NULL)
                put("focusMode", settings.focusMode.name)
                put("focusDistance", settings.focusDistance ?: JSONObject.NULL)
                put("whiteBalance", settings.whiteBalance.name)
                put("colorTemperature", settings.colorTemperature ?: JSONObject.NULL)
                put("zoomRatio", settings.zoomRatio.toDouble())
                put("frameRate", settings.frameRate)
                put("resolution", settings.resolution.name)
                put("stabilization", settings.stabilization)
                put("hdrMode", settings.hdrMode.name)
                put("sceneMode", settings.sceneMode ?: JSONObject.NULL)
            }

            val streaming = JSONObject().apply {
                put("port", cachedPort)
                put("jpegQuality", cachedJpegQuality)
                put("showPreview", cachedShowPreview)
                put("streamAudioEnabled", cachedStreamAudioEnabled)
                put("streamAudioBitrateKbps", cachedStreamAudioBitrateKbps)
                put("streamAudioChannels", cachedStreamAudioChannels)
                put("streamAudioEchoCancellation", cachedStreamAudioEchoCancellation)
                put("recordingAudioEnabled", cachedRecordingAudioEnabled)
            }

            json.put("camera", camera)
            json.put("streaming", streaming)

            json.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get settings", e)
            errorJson(e)
        }
    }

    fun handlePutSettings(body: String): String {
        return try {
            val json = JSONObject(body)

            if (json.has("camera")) {
                val cam = json.getJSONObject("camera")
                val current = cachedSettings
                val newSettings = CameraSettings(
                    exposureCompensation = cam.optInt("exposureCompensation", current.exposureCompensation),
                    iso = when {
                        !cam.has("iso") -> current.iso
                        cam.isNull("iso") -> null
                        else -> cam.getInt("iso")
                    },
                    exposureTime = when {
                        !cam.has("exposureTime") -> current.exposureTime
                        cam.isNull("exposureTime") -> null
                        else -> cam.getLong("exposureTime")
                    },
                    focusMode = try {
                        FocusMode.valueOf(cam.optString("focusMode", current.focusMode.name))
                    } catch (_: Exception) {
                        current.focusMode
                    },
                    focusDistance = when {
                        !cam.has("focusDistance") -> current.focusDistance
                        cam.isNull("focusDistance") -> null
                        else -> cam.getDouble("focusDistance").toFloat()
                    },
                    whiteBalance = try {
                        WhiteBalance.valueOf(cam.optString("whiteBalance", current.whiteBalance.name))
                    } catch (_: Exception) {
                        current.whiteBalance
                    },
                    colorTemperature = when {
                        !cam.has("colorTemperature") -> current.colorTemperature
                        cam.isNull("colorTemperature") -> null
                        else -> cam.getInt("colorTemperature")
                    },
                    zoomRatio = cam.optDouble("zoomRatio", current.zoomRatio.toDouble()).toFloat(),
                    frameRate = cam.optInt("frameRate", current.frameRate),
                    resolution = try {
                        Resolution.valueOf(cam.optString("resolution", current.resolution.name))
                    } catch (_: Exception) {
                        current.resolution
                    },
                    stabilization = cam.optBoolean("stabilization", current.stabilization),
                    hdrMode = try {
                        HdrMode.valueOf(cam.optString("hdrMode", current.hdrMode.name))
                    } catch (_: Exception) {
                        current.hdrMode
                    },
                    sceneMode = when {
                        !cam.has("sceneMode") -> current.sceneMode
                        cam.isNull("sceneMode") -> null
                        else -> cam.getString("sceneMode").takeIf { it.isNotEmpty() }
                    },
                )
                cachedSettings = newSettings
                scope.launch {
                    app.settingsDataStore.saveSettings(newSettings)
                    withContext(Dispatchers.Main) {
                        app.cameraService.applySettings(newSettings)
                    }
                }
            }

            if (json.has("streaming")) {
                val stream = json.getJSONObject("streaming")
                stream.optInt("port", -1).takeIf { it > 0 }?.let {
                    cachedPort = it
                    scope.launch { app.settingsDataStore.saveStreamingPort(it) }
                }
                stream.optInt("jpegQuality", -1).takeIf { it > 0 }?.let {
                    cachedJpegQuality = it
                    scope.launch {
                        app.settingsDataStore.saveJpegQuality(it)
                        app.streamingManager.setJpegQuality(it)
                    }
                }
                if (stream.has("showPreview")) {
                    val show = stream.getBoolean("showPreview")
                    cachedShowPreview = show
                    scope.launch { app.settingsDataStore.saveShowPreview(show) }
                }
                if (stream.has("streamAudioEnabled")) {
                    val enabled = stream.getBoolean("streamAudioEnabled")
                    cachedStreamAudioEnabled = enabled
                    scope.launch {
                        app.settingsDataStore.saveStreamAudioEnabled(enabled)
                        app.streamingManager.setStreamAudioEnabled(enabled)
                    }
                }
                stream.optInt("streamAudioBitrateKbps", -1).takeIf { it > 0 }?.let {
                    cachedStreamAudioBitrateKbps = it
                    scope.launch {
                        app.settingsDataStore.saveStreamAudioBitrateKbps(it)
                        app.streamingManager.setStreamAudioBitrateKbps(it)
                    }
                }
                stream.optInt("streamAudioChannels", -1).takeIf { it > 0 }?.let {
                    cachedStreamAudioChannels = it
                    scope.launch {
                        app.settingsDataStore.saveStreamAudioChannels(it)
                        app.streamingManager.setStreamAudioChannels(it)
                    }
                }
                if (stream.has("streamAudioEchoCancellation")) {
                    val enabled = stream.getBoolean("streamAudioEchoCancellation")
                    cachedStreamAudioEchoCancellation = enabled
                    scope.launch {
                        app.settingsDataStore.saveStreamAudioEchoCancellation(enabled)
                        app.streamingManager.setStreamAudioEchoCancellation(enabled)
                    }
                }
                if (stream.has("recordingAudioEnabled")) {
                    val enabled = stream.getBoolean("recordingAudioEnabled")
                    cachedRecordingAudioEnabled = enabled
                    scope.launch { app.settingsDataStore.saveRecordingAudioEnabled(enabled) }
                }
            }

            """{"success":true}"""
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

            val json = JSONObject()
            val streaming = JSONObject().apply {
                put("isActive", isLiveStreaming)
                put("url", streamUrl)
                put("clientCount", clientCount)
                put("audioEnabled", isAudioStreaming)
                put("audioUrl", audioUrl)
            }
            json.put("streaming", streaming)
            json.put("thermal", thermal.name)
            json.put("camera", app.cameraService.cameraState.value.toString())

            val batteryJson = JSONObject().apply {
                put("level", battery)
                put("isCharging", isCharging)
                put("isPowerSaveMode", isPowerSave)
            }
            json.put("battery", batteryJson)

            json.toString()
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
                    return """{"success":false,"error":"Failed to start streaming server"}"""
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
            """{"success":true,"isActive":true,"url":"${safeJsonString(streamUrl)}"}"""
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

            """{"success":true,"isActive":false}"""
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
                """{"success":false,"error":"Camera not available"}"""
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
                """{"success":true,"fileName":"${safeJsonString(fileName)}"}"""
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

            val array = org.json.JSONArray()
            for ((index, lens) in lenses.withIndex()) {
                val obj = JSONObject().apply {
                    put("index", index)
                    put("id", lens.id)
                    put("label", lens.label)
                    put("focalLength", lens.focalLength.toDouble())
                    put(
                        "isFront",
                        lens.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                    )
                    put("selected", index == selectedIndex)
                }
                array.put(obj)
            }

            JSONObject().apply {
                put("lenses", array)
                put("selectedIndex", selectedIndex)
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get lenses", e)
            errorJson(e)
        }
    }

    fun handleSelectLens(body: String): String {
        return try {
            val json = JSONObject(body)
            val index = json.getInt("index")
            runBlocking {
                withContext(Dispatchers.Main) {
                    app.cameraService.selectLens(index)
                }
            }
            """{"success":true}"""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select lens", e)
            errorJson(e)
        }
    }

    fun handleGetIntervalCaptureStatus(): String {
        return try {
            val snapshot = IntervalCaptureScheduler.getStatus(context)

            JSONObject().apply {
                put("isRunning", snapshot.isRunning)
                put("completedCaptures", snapshot.completedCaptures)
            }.toString()
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
            """{"success":true}"""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start interval capture", e)
            errorJson(e)
        }
    }

    fun handleStopIntervalCapture(): String {
        return try {
            IntervalCaptureScheduler.stop(context)
            Log.d(TAG, "Interval capture stopped")
            """{"success":true}"""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop interval capture", e)
            errorJson(e)
        }
    }

    fun handleGetRecordingStatus(): String {
        return try {
            val elapsed = if (isRecording && recordingStartTime > 0) {
                ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
            } else {
                0
            }
            JSONObject().apply {
                put("isRecording", isRecording)
                put("elapsedSeconds", elapsed)
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recording status", e)
            errorJson(e)
        }
    }

    fun handleStartRecording(body: String): String {
        return try {
            val configJson = if (body.isNotEmpty()) body else "{}"
            val intent = Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_CONFIG, configJson)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            Log.d(TAG, "Recording started")
            """{"success":true}"""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            errorJson(e)
        }
    }

    fun handleStopRecording(): String {
        return try {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
            context.startService(intent)
            isRecording = false
            recordingStartTime = 0
            Log.d(TAG, "Recording stopped")
            """{"success":true}"""
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
            val array = org.json.JSONArray()
            for (entry in filtered) {
                val obj = JSONObject().apply {
                    put("id", entry.id)
                    put("type", entry.type.name)
                    put("fileName", entry.fileName)
                    put("timestamp", entry.timestamp)
                    put("fileSizeBytes", entry.fileSizeBytes)
                    put("durationMs", entry.durationMs)
                    put("thumbnailUrl", "/api/media/${entry.id}")
                    put("downloadUrl", "/api/media/${entry.id}?download=1")
                }
                array.put(obj)
            }
            JSONObject().apply {
                put("items", array)
                put("total", filtered.size)
            }.toString()
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
                """{"success":false,"error":"Media not found"}"""
            } else {
                app.captureHistoryStore.deleteMedia(id)
                """{"success":true}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete media", e)
            errorJson(e)
        }
    }

    fun handleBatchDeleteMedia(body: String): String {
        return try {
            val json = org.json.JSONObject(body)
            val ids = json.getJSONArray("ids")
            val idList = (0 until ids.length()).map { ids.getString(it) }
            val deleted = app.captureHistoryStore.deleteMediaBatch(idList)
            val deletedArr = org.json.JSONArray(deleted)
            """{"success":true,"deleted":$deletedArr}"""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch delete media", e)
            errorJson(e)
        }
    }

    fun resolveMediaFile(id: String): Pair<java.io.InputStream, String>? {
        val history = app.captureHistoryStore.history.value
        val entry = history.find { it.id == id } ?: return null
        val mimeType = when (entry.type) {
            com.raulshma.lenscast.capture.model.CaptureType.PHOTO -> "image/jpeg"
            com.raulshma.lenscast.capture.model.CaptureType.VIDEO -> "video/mp4"
        }
        return try {
            val uri = android.net.Uri.parse(entry.filePath)
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) return Pair(inputStream, mimeType)
            val file = java.io.File(entry.filePath)
            if (file.exists()) Pair(file.inputStream(), mimeType) else null
        } catch (_: Exception) {
            try {
                val file = java.io.File(entry.filePath)
                if (file.exists()) Pair(file.inputStream(), mimeType) else null
            } catch (_: Exception) { null }
        }
    }

    private fun safeJsonString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", "")
            .replace("\t", " ")
    }

    private fun errorJson(e: Exception): String {
        val msg = e.message?.take(200)?.let { safeJsonString(it) } ?: "Internal error"
        return """{"success":false,"error":"$msg"}"""
    }

    companion object {
        private const val TAG = "WebApiController"
    }
}
