package com.raulshma.lenscast.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.MainActivity
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    private var isRecording = false
    private var startTimeMs: Long = 0
    private var recordingConfig: RecordingConfig? = null
    private var isFinalizingRecording = false

    private val moshi by lazy {
        com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
    }
    private val recordingConfigAdapter by lazy {
        moshi.adapter(RecordingConfig::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                val config = if (configJson != null) {
                    recordingConfigAdapter.fromJson(configJson)
                } else null
                startRecording(config)
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(config: RecordingConfig?) {
        if (isRecording) return
        recordingConfig = config
        isFinalizingRecording = false
        startTimeMs = System.currentTimeMillis()

        val notification = buildNotification("Recording video...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val app = applicationContext as MainApplication
        val cameraService = app.cameraService
        val fileName = "VID_${DATE_FORMAT.format(Date())}.mp4"

        cameraService.acquireKeepAlive()
        cameraService.beginExclusiveSession()

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_MOVIES + "/LensCast"
                    )
                }
            }

            val mediaStoreOutput = MediaStoreOutputOptions
                .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build()

            val quality = when (config?.quality ?: RecordingQuality.HIGH) {
                RecordingQuality.HIGH -> Quality.HIGHEST
                RecordingQuality.MEDIUM -> Quality.FHD
                RecordingQuality.LOW -> Quality.HD
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality))
                .build()

            val videoCapture = VideoCapture.withOutput(recorder)

            val cameraProvider = cameraService.getCameraProvider() ?: run {
                Log.e(TAG, "Camera provider not available")
                cleanupFailedStart()
                return
            }
            val cameraSelector = cameraService.getCurrentCameraSelector()

            @Suppress("DEPRECATION")
            cameraProvider.unbindAll()

            val preview = if (cameraService.isPreviewAvailable()) cameraService.getPreview() else null
            val imageCapture = cameraService.getImageCapture()
            val imageAnalysis = cameraService.getImageAnalysis()

            val lifecycleOwner = cameraService.getEffectiveLifecycleOwner()

            @Suppress("DEPRECATION")
            try {
                when {
                    preview != null && imageCapture != null && imageAnalysis != null ->
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector,
                            preview, imageCapture, imageAnalysis, videoCapture
                        )
                    preview != null && imageCapture != null ->
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector,
                            preview, imageCapture, videoCapture
                        )
                    preview != null && imageAnalysis != null ->
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector,
                            preview, imageAnalysis, videoCapture
                        )
                    preview != null ->
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector,
                            preview, videoCapture
                        )
                    imageAnalysis != null ->
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector,
                            imageAnalysis, videoCapture
                        )
                    else ->
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector,
                            videoCapture
                        )
                }
                Log.d(TAG, "Bound use cases for recording (streaming preserved if active)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind all use cases, trying with VideoCapture only", e)
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    videoCapture
                )
            }

            val currentRecording = videoCapture.output
                .prepareRecording(this, mediaStoreOutput)
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.d(TAG, "Recording started: $fileName")
                        }
                        is VideoRecordEvent.Finalize -> {
                            val savedUri = event.outputResults.outputUri.takeIf {
                                it.toString().isNotBlank()
                            }

                            if (!event.hasError() && savedUri != null) {
                                val duration = System.currentTimeMillis() - startTimeMs
                                val fileSizeBytes = queryMediaSize(savedUri)
                                val entry = app.captureHistoryStore.createVideoEntry(
                                    fileName = fileName,
                                    filePath = savedUri.toString(),
                                    fileSizeBytes = fileSizeBytes,
                                    durationMs = duration,
                                )
                                app.captureHistoryStore.add(entry)
                                Log.d(TAG, "Recording saved: $fileName at $savedUri ($fileSizeBytes bytes)")
                            } else {
                                Log.e(TAG, "Recording error: ${event.error}, uri=$savedUri")
                                savedUri?.let { failedUri ->
                                    runCatching {
                                        contentResolver.delete(failedUri, null, null)
                                    }.onFailure { deleteError ->
                                        Log.w(TAG, "Failed to clean up incomplete recording $failedUri", deleteError)
                                    }
                                }
                            }

                            finishRecordingSession()
                        }
                    }
                }

            activeRecording = currentRecording
            isRecording = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanupFailedStart()
        }
    }

    private fun scheduleNextRecording(config: RecordingConfig) {
        val delayMs = config.repeatIntervalSeconds * 1000
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isRecording) {
                stopRecording()
                startRecording(config)
            }
        }, delayMs)
    }

    private fun stopRecording() {
        if (!isRecording || isFinalizingRecording) return
        isRecording = false
        isFinalizingRecording = true

        val currentRecording = activeRecording
        if (currentRecording == null) {
            finishRecordingSession()
            return
        }

        val duration = System.currentTimeMillis() - startTimeMs
        Log.d(TAG, "Recording stopped. Duration: ${duration}ms")

        currentRecording.stop()
    }

    private fun finishRecordingSession() {
        activeRecording = null
        isFinalizingRecording = false

        val app = applicationContext as MainApplication
        app.cameraService.endExclusiveSession()
        app.cameraService.releaseKeepAlive()
        app.cameraService.rebindUseCases()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupFailedStart(uri: android.net.Uri? = null) {
        activeRecording = null
        isRecording = false
        isFinalizingRecording = false

        uri?.let {
            runCatching {
                contentResolver.delete(it, null, null)
            }.onFailure { deleteError ->
                Log.w(TAG, "Failed to clean up recording entry $it", deleteError)
            }
        }

        val app = applicationContext as MainApplication
        app.cameraService.endExclusiveSession()
        app.cameraService.releaseKeepAlive()
        app.cameraService.rebindUseCases()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun queryMediaSize(uri: android.net.Uri): Long {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        0L
                    }
                } else {
                    0L
                }
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun buildNotification(message: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("LensCast Recording")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("LensCast Recording")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.raulshma.lenscast.START_RECORDING"
        const val ACTION_STOP = "com.raulshma.lenscast.STOP_RECORDING"
        const val EXTRA_CONFIG = "recording_config"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "RecordingService"
        private var activeRecording: Recording? = null
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
