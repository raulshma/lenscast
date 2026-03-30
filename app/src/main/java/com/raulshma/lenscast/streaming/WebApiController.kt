package com.raulshma.lenscast.streaming

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.capture.IntervalCaptureWorker
import com.raulshma.lenscast.capture.RecordingService

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class WebApiController(private val context: Context) {

    private val app: MainApplication
        get() = context.applicationContext as MainApplication

    @Volatile
    private var intervalCaptureRunning = false

    @Volatile
    private var intervalCaptureCompleted = 0

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

    fun handleGetSettings(): String {
        return try {
            runBlocking {
                val settings = app.settingsDataStore.settings.first()
                val port = app.settingsDataStore.streamingPort.first()
                val quality = app.settingsDataStore.jpegQuality.first()
                val showPreview = app.settingsDataStore.showPreview.first()
                val streamAudioEnabled = app.settingsDataStore.streamAudioEnabled.first()
                val streamAudioBitrateKbps = app.settingsDataStore.streamAudioBitrateKbps.first()
                val streamAudioChannels = app.settingsDataStore.streamAudioChannels.first()
                val recordingAudioEnabled = app.settingsDataStore.recordingAudioEnabled.first()
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
                    put("port", port)
                    put("jpegQuality", quality)
                    put("showPreview", showPreview)
                    put("streamAudioEnabled", streamAudioEnabled)
                    put("streamAudioBitrateKbps", streamAudioBitrateKbps)
                    put("streamAudioChannels", streamAudioChannels)
                    put("recordingAudioEnabled", recordingAudioEnabled)
                }

                json.put("camera", camera)
                json.put("streaming", streaming)

                json.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get settings", e)
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    fun handlePutSettings(body: String): String {
        return try {
            runBlocking {
                val json = JSONObject(body)

                if (json.has("camera")) {
                    val cam = json.getJSONObject("camera")
                    val current = app.settingsDataStore.settings.first()
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
                    app.settingsDataStore.saveSettings(newSettings)
                    app.cameraService.applySettings(newSettings)
                }

                if (json.has("streaming")) {
                    val stream = json.getJSONObject("streaming")
                    stream.optInt("port", -1).takeIf { it > 0 }?.let {
                        app.settingsDataStore.saveStreamingPort(it)
                    }
                    stream.optInt("jpegQuality", -1).takeIf { it > 0 }?.let {
                        app.settingsDataStore.saveJpegQuality(it)
                        app.streamingManager.setJpegQuality(it)
                    }
                    if (stream.has("showPreview")) {
                        app.settingsDataStore.saveShowPreview(stream.getBoolean("showPreview"))
                    }
                    if (stream.has("streamAudioEnabled")) {
                        val enabled = stream.getBoolean("streamAudioEnabled")
                        app.settingsDataStore.saveStreamAudioEnabled(enabled)
                        app.streamingManager.setStreamAudioEnabled(enabled)
                    }
                    stream.optInt("streamAudioBitrateKbps", -1).takeIf { it > 0 }?.let {
                        app.settingsDataStore.saveStreamAudioBitrateKbps(it)
                        app.streamingManager.setStreamAudioBitrateKbps(it)
                    }
                    stream.optInt("streamAudioChannels", -1).takeIf { it > 0 }?.let {
                        app.settingsDataStore.saveStreamAudioChannels(it)
                        app.streamingManager.setStreamAudioChannels(it)
                    }
                    if (stream.has("recordingAudioEnabled")) {
                        app.settingsDataStore.saveRecordingAudioEnabled(
                            stream.getBoolean("recordingAudioEnabled")
                        )
                    }
                }

                """{"success":true}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update settings", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    fun handleGetStatus(): String {
        return try {
            runBlocking {
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get status", e)
            """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
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
                    withContext(Dispatchers.Main) {
                        app.cameraService.acquireKeepAlive()
                        app.cameraService.rebindUseCases()
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
            """{"success":true,"isActive":true,"url":"${streamUrl.replace("\"", "\\\"")}"}"""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start stream", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
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
                    withContext(Dispatchers.Main) {
                        app.cameraService.releaseKeepAlive()
                        app.cameraService.rebindUseCases()
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
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    fun handleCapture(): String {
        return try {
            val imageCapture = runBlocking {
                withContext(Dispatchers.Main) {
                    app.cameraService.acquirePhotoCapture()
                }
            }
            if (imageCapture == null) {
                """{"success":false,"error":"Camera not available"}"""
            } else {
                val fileName = "IMG_${DATE_FORMAT.format(Date())}.jpg"

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/LensCast"
                        )
                    }
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(
                        context.contentResolver,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    ).build()
                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val entry = app.captureHistoryStore.createPhotoEntry(
                                    fileName = fileName,
                                    filePath = output.savedUri?.toString() ?: "",
                                    fileSizeBytes = 0,
                                )
                                app.captureHistoryStore.add(entry)
                                app.cameraService.releasePhotoCapture()
                                Log.d(TAG, "Photo captured via web: $fileName")
                            }

                            override fun onError(exc: ImageCaptureException) {
                                app.cameraService.releasePhotoCapture()
                                Log.e(TAG, "Web capture failed", exc)
                            }
                        },
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "LensCast"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val outputFile = File(dir, fileName)
                    val outputOptions =
                        ImageCapture.OutputFileOptions.Builder(outputFile).build()
                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val entry = app.captureHistoryStore.createPhotoEntry(
                                    fileName = fileName,
                                    filePath = outputFile.absolutePath,
                                    fileSizeBytes = outputFile.length(),
                                )
                                app.captureHistoryStore.add(entry)
                                app.cameraService.releasePhotoCapture()
                                Log.d(TAG, "Photo captured via web: $fileName")
                            }

                            override fun onError(exc: ImageCaptureException) {
                                app.cameraService.releasePhotoCapture()
                                Log.e(TAG, "Web capture failed", exc)
                            }
                        },
                    )
                }
                """{"success":true,"fileName":"$fileName"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
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
            """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
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
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    fun handleGetIntervalCaptureStatus(): String {
        return try {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork(WORK_NAME_INTERVAL).get()
            val isRunning = workInfos.any { !it.state.isFinished }
            if (!isRunning) intervalCaptureRunning = false

            JSONObject().apply {
                put("isRunning", isRunning || intervalCaptureRunning)
                put("completedCaptures", intervalCaptureCompleted)
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get interval capture status", e)
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    fun handleStartIntervalCapture(body: String): String {
        return try {
            val json = JSONObject(body)
            val intervalSeconds = json.optLong("intervalSeconds", 5)
            val totalCaptures = json.optInt("totalCaptures", 100)
            val imageQuality = json.optInt("imageQuality", 90)

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<IntervalCaptureWorker>(
                intervalSeconds.coerceIn(1, 3600), TimeUnit.SECONDS
            )
                .setInputData(
                    Data.Builder()
                        .putLong(IntervalCaptureWorker.KEY_INTERVAL_SECONDS, intervalSeconds)
                        .putInt(IntervalCaptureWorker.KEY_TOTAL_CAPTURES, totalCaptures)
                        .putInt(IntervalCaptureWorker.KEY_IMAGE_QUALITY, imageQuality)
                        .putInt(IntervalCaptureWorker.KEY_COMPLETED_CAPTURES, intervalCaptureCompleted)
                        .build()
                )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_INTERVAL,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            intervalCaptureRunning = true
            intervalCaptureCompleted = 0

            Log.d(TAG, "Interval capture started: every ${intervalSeconds}s, total=$totalCaptures")
            """{"success":true}"""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start interval capture", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    fun handleStopIntervalCapture(): String {
        return try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_INTERVAL)
            intervalCaptureRunning = false
            Log.d(TAG, "Interval capture stopped")
            """{"success":true}"""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop interval capture", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
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
            """{"error":"${e.message?.replace("\"", "'")}"}"""
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
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
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
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
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
            """{"error":"${e.message?.replace("\"", "'")}"}"""
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
            """{"success":false,"error":"${e.message?.replace("\"", "'")}"}"""
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
            """{"success":false,"error":"${e.message?.replace("\"", "'")}"}"""
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

    companion object {
        private const val TAG = "WebApiController"
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private const val WORK_NAME_INTERVAL = "interval_capture"
    }
}
