package com.raulshma.lenscast.capture

import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
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
import com.raulshma.lenscast.capture.model.RecordingTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/**
 * The foreground service that holds the live recording. It owns no public
 * state: every transition is reported to the app-scoped [RecordingController],
 * which all consumers observe. Camera binding goes through
 * CameraService's `bindRecording` seam — never through the provider directly.
 *
 * The write seam: plaintext recordings go straight to MediaStore through
 * CameraX's [MediaStoreOutputOptions]. With media encryption on, the Recorder
 * instead writes a temp file in cacheDir ([FileOutputOptions] — CameraX's
 * recorder cannot write through an app-owned stream), and the Finalize path
 * promotes it into MediaStore through the shared [EncryptedMediaSink] — the
 * same seam photos use — before the history entry and the backup enqueue
 * read the at-rest size. The extension stays `.mp4`; decryption is the
 * resolver's job.
 */
class RecordingService : Service() {

    private var isRecording = false
    private var startTimeMs: Long = 0
    private var recordingConfig: RecordingConfig? = null
    private var isFinalizingRecording = false
    private var capturedRecordingAudioExclusively = false
    private var activeRecording: Recording? = null

    /** Drains the encrypted Finalize promotion off the main executor. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The encrypted path's in-flight CameraX temp output, if any. */
    private var pendingTempFile: File? = null

    private val app: MainApplication by lazy { applicationContext as MainApplication }
    private val recordingController: RecordingController by lazy { app.recordingController }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ForegroundNotifications.createChannel(this, CHANNEL_ID, "Recording")
    }

    override fun onDestroy() {
        // Destroy mid-drain (system teardown while the encrypted promotion is
        // in flight): the clip is lost with the cancelled scope, but the
        // controller must never stay wedged in Finalizing and the temp
        // plaintext must not outlive the service.
        if (isFinalizingRecording) {
            pendingTempFile?.delete()
            pendingTempFile = null
            recordingController.onServiceStopped()
        }
        serviceScope.cancel()
        super.onDestroy()
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
                this, ForegroundNotifications.RECORDING_NOTIFICATION_ID, notification, audioEnabled
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

            // The write seam: plaintext rides MediaStore directly; encryption
            // rides a temp file promoted through the EncryptedMediaSink on
            // Finalize (CameraX's recorder cannot write through an app-owned
            // stream). A keyless encryption flip fails open to plaintext
            // rather than losing the recording.
            val encrypting = app.settingsDataStore.mediaEncryptionEnabled.value &&
                app.mediaKeyProvider != null
            val tempFile = if (encrypting) {
                File.createTempFile("lenscast_enc_", ".mp4", cacheDir)
            } else {
                null
            }
            pendingTempFile = tempFile

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

            // prepareRecording's two concrete OutputOptions overloads don't
            // accept the if/else's common supertype, so the output choice and
            // the prepare call stay in the same branch.
            var pendingRecording = if (tempFile != null) {
                videoCapture.output.prepareRecording(
                    this,
                    androidx.camera.video.FileOutputOptions.Builder(tempFile).build(),
                )
            } else {
                videoCapture.output.prepareRecording(
                    this,
                    MediaStoreOutputOptions
                        .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                        .setContentValues(contentValues)
                        .build(),
                )
            }
            // Degrade to video-only rather than throwing if RECORD_AUDIO was
            // revoked between the camera screen's gate and the service start.
            if (audioEnabled &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
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
                            // The duration is a Finalize-time fact; the
                            // encrypted promotion below only spends IO time.
                            // [pendingTempFile] stays set through the drain so
                            // a service teardown can clean the plaintext temp.
                            val duration = System.currentTimeMillis() - startTimeMs

                            if (!event.hasError() && (savedUri != null || tempFile != null)) {
                                serviceScope.launch {
                                    val filePath: String
                                    val fileSizeBytes: Long
                                    if (tempFile != null) {
                                        // One encrypt-on-write seam for both
                                        // capture types: the temp recording
                                        // streams into the new MediaStore row
                                        // as ciphertext; any failure removes
                                        // the row (a lost clip, never a broken
                                        // one).
                                        val saved = EncryptedMediaSink(
                                            contentResolver,
                                            app.mediaKeyProvider!!,
                                        ).writeFileToMediaStore(
                                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                            contentValues,
                                            tempFile,
                                        )
                                        tempFile.delete()
                                        pendingTempFile = null
                                        if (saved == null) {
                                            Log.e(TAG, "Encrypted recording promotion failed; clip discarded")
                                            withContext(Dispatchers.Main) { finishRecordingSession() }
                                            return@launch
                                        }
                                        filePath = saved.uriString
                                        fileSizeBytes = saved.storedSizeBytes
                                    } else {
                                        pendingTempFile = null
                                        filePath = savedUri!!.toString()
                                        fileSizeBytes = queryMediaSize(savedUri)
                                    }
                                    val entry = app.captureHistoryStore.createVideoEntry(
                                        fileName = fileName,
                                        filePath = filePath,
                                        fileSizeBytes = fileSizeBytes,
                                        durationMs = duration,
                                        // Provenance stamped at creation: the
                                        // config's trigger (folded to SCHEDULED
                                        // by the controller's schedule path,
                                        // MOTION/SOUND by the detection
                                        // coordinator, CONTINUOUS_LOOP by the
                                        // loop controller) — never reconstructed.
                                        trigger = recordingConfig?.trigger ?: RecordingTrigger.MANUAL,
                                    )
                                    app.captureHistoryStore.add(entry)
                                    BackupWorker.enqueue(applicationContext, filePath)
                                    Log.d(TAG, "Recording saved: $fileName at $filePath ($fileSizeBytes bytes)")
                                    withContext(Dispatchers.Main) { finishRecordingSession() }
                                }
                            } else {
                                Log.e(TAG, "Recording error: ${event.error}, uri=$savedUri")
                                tempFile?.delete()
                                pendingTempFile = null
                                savedUri?.let { failedUri ->
                                    runCatching {
                                        contentResolver.delete(failedUri, null, null)
                                    }.onFailure { deleteError ->
                                        Log.w(TAG, "Failed to clean up incomplete recording $failedUri", deleteError)
                                    }
                                }
                                finishRecordingSession()
                            }
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
        // A failed start never promotes the encrypted temp output.
        pendingTempFile?.delete()
        pendingTempFile = null
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            // The boolean overload is the API-23 equivalent of REMOVE.
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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
        private const val TAG = "RecordingService"
    }
}
