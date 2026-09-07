package com.raulshma.lenscast.capture

import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import com.raulshma.lenscast.capture.model.CaptureMediaFormat
import com.raulshma.lenscast.core.ForegroundNotifications
import com.raulshma.lenscast.core.MicAccess
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.capture.model.RecordingQuality
import java.util.Date

/**
 * The foreground service that holds the live recording. It owns no public
 * state: every transition is reported to the app-scoped [RecordingController],
 * which all consumers observe. Camera binding goes through
 * CameraService's `bindRecording` seam — never through the provider directly.
 */
class RecordingService : Service() {

    private var isRecording = false
    private var startTimeMs: Long = 0
    private var recordingConfig: RecordingConfig? = null
    private var isFinalizingRecording = false
    private var capturedRecordingAudioExclusively = false
    private var activeRecording: Recording? = null

    private val app: MainApplication by lazy { applicationContext as MainApplication }
    private val recordingController: RecordingController by lazy { app.recordingController }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ForegroundNotifications.createChannel(this, CHANNEL_ID, "Recording")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)?.let { json ->
                    runCatching { RecordingConfigJson.decode(json) }.getOrNull()
                }
                startRecording(config)
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(config: RecordingConfig?) {
        // A start while the previous recording is still draining would
        // overwrite activeRecording and lose the session when the pending
        // Finalize fires — drop it instead (the repeat policy re-issues).
        if (isRecording || isFinalizingRecording) return
        recordingConfig = config
        isFinalizingRecording = false
        startTimeMs = System.currentTimeMillis()

        val shouldIncludeAudio = config?.includeAudio ?: true
        val audioEnabled = shouldIncludeAudio && MicAccess.isGranted(this)

        val notification = ForegroundNotifications.build(
            this,
            CHANNEL_ID,
            "LensCast Recording",
            if (audioEnabled) "Recording video and audio..." else "Recording video...",
        )
        val cameraService = app.cameraService
        val fileName = MediaFileNaming.videoName(Date())

        try {
            if (audioEnabled) {
                app.streamingManager.setRecordingAudioCaptureActive(true)
                capturedRecordingAudioExclusively = true
            }

            ForegroundNotifications.startCameraForeground(
                this, NOTIFICATION_ID, notification, audioEnabled
            )

            cameraService.acquireKeepAlive()
            cameraService.beginExclusiveSession()

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, CaptureMediaFormat.MIME_VIDEO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, CaptureMediaFormat.VIDEOS_WRITE_RELATIVE_PATH)
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

            if (!cameraService.bindRecording(videoCapture)) {
                Log.e(TAG, "Could not bind camera for recording")
                cleanupFailedStart()
                return
            }

            var pendingRecording = videoCapture.output.prepareRecording(this, mediaStoreOutput)
            if (audioEnabled) {
                pendingRecording = pendingRecording.withAudioEnabled()
            }

            val currentRecording = pendingRecording
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.d(TAG, "Recording started: $fileName")
                            recordingController.onServiceStarted(startTimeMs, recordingConfig)
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

    private fun stopRecording() {
        if (!isRecording || isFinalizingRecording) {
            if (!isRecording) {
                // Nothing live to drain. Report anyway so a stop aimed at a
                // stale service instance can never wedge the controller in a
                // non-Idle state.
                recordingController.onServiceStopped()
            }
            return
        }
        isRecording = false
        isFinalizingRecording = true

        val currentRecording = activeRecording
        if (currentRecording == null) {
            finishRecordingSession()
            return
        }

        val duration = System.currentTimeMillis() - startTimeMs
        Log.d(TAG, "Recording stopped. Duration: ${duration}ms")

        recordingController.onServiceFinalizing()
        currentRecording.stop()
    }

    /** A completed recording: clear the finalizing flag, then the shared teardown. */
    private fun finishRecordingSession() {
        isFinalizingRecording = false
        teardownSession()
    }

    /** A start that never came live: clear both live flags, then the shared teardown. */
    private fun cleanupFailedStart() {
        isRecording = false
        isFinalizingRecording = false
        teardownSession()
    }

    /**
     * The one teardown ladder both a normal finish and a failed start run,
     * in exactly this order: drop the recording, release audio/camera
     * session/keep-alive/binding, report stopped, then retire the service.
     */
    private fun teardownSession() {
        activeRecording = null

        val cameraService = app.cameraService
        releaseExclusiveRecordingAudio()
        cameraService.endExclusiveSession()
        cameraService.releaseKeepAlive()
        cameraService.unbindRecording()

        recordingController.onServiceStopped()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseExclusiveRecordingAudio() {
        if (!capturedRecordingAudioExclusively) return
        capturedRecordingAudioExclusively = false
        app.streamingManager.setRecordingAudioCaptureActive(false)
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

    companion object {
        const val ACTION_START = "com.raulshma.lenscast.START_RECORDING"
        const val ACTION_STOP = "com.raulshma.lenscast.STOP_RECORDING"
        const val EXTRA_CONFIG = "recording_config"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "RecordingService"
    }
}
