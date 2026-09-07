package com.raulshma.lenscast

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.capture.PhotoCaptureManager
import com.raulshma.lenscast.capture.RecordingController
import com.raulshma.lenscast.core.ConnectivityMonitor
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.settings.SettingsApplier
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import com.raulshma.lenscast.update.UpdateChecker
import com.raulshma.lenscast.update.UpdateNotifier
import com.raulshma.lenscast.update.UpdatePolicy
import com.raulshma.lenscast.update.model.UpdateCheckResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainApplication : Application(), SingletonImageLoader.Factory {
    val cameraService: CameraService by lazy { CameraService(this) }
    val streamingManager: StreamingManager by lazy { StreamingManager(this, thermalMonitor) }
    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(this) }
    val captureHistoryStore: CaptureHistoryStore by lazy { CaptureHistoryStore(this) }
    val recordingController: RecordingController by lazy { RecordingController(this) }
    val photoCaptureManager: PhotoCaptureManager by lazy {
        PhotoCaptureManager(this, cameraService, captureHistoryStore)
    }
    val powerManager: PowerManager by lazy { PowerManager(this) }
    val thermalMonitor: ThermalMonitor by lazy { ThermalMonitor(this) }
    val connectivityMonitor: ConnectivityMonitor by lazy { ConnectivityMonitor(this) }
    // The watchdog and the session reference each other; both resolve lazily
    // and neither touches the other during construction.
    val streamWatchdog: StreamWatchdog by lazy {
        StreamWatchdog(cameraService, streamingManager, streamingSession)
    }
    val streamingSession: StreamingSession by lazy {
        StreamingSession(
            this, cameraService, streamingManager, powerManager, thermalMonitor,
        ) { streamWatchdog }
    }
    val settingsApplier: SettingsApplier by lazy {
        SettingsApplier(settingsDataStore, cameraService, streamingManager, streamWatchdog)
    }
    val updateChecker: UpdateChecker by lazy { UpdateChecker(this) }
    val updateNotifier: UpdateNotifier by lazy { UpdateNotifier(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        connectivityMonitor.start()
        settingsApplier.start(appScope)
        wireFramePump()
        initializeAutoUpdateCheck()
    }

    /**
     * The frame pump is app-runtime composition, not screen state: camera
     * frames must reach the streaming outputs even when no camera screen has
     * composed (headless start via the Web API).
     */
    private fun wireFramePump() {
        cameraService.setFrameListener { yuvData, width, height, rotation ->
            streamingManager.pushFrame(yuvData, width, height, rotation)
        }
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    private fun initializeAutoUpdateCheck() {
        appScope.launch {
            delay(3_000)
            val settings = settingsDataStore
            val enabled = settings.updateAutoCheckEnabled.first()
            val lastCheck = settings.updateLastCheckTime.first()
            if (!UpdatePolicy.shouldAutoCheck(lastCheck, enabled)) return@launch

            when (val result = updateChecker.checkForUpdate()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    val remoteVersion = UpdatePolicy.normalize(result.release.tagName)
                    val dismissed = settings.updateDismissedVersion.first()
                    if (UpdatePolicy.shouldNotify(dismissed, result.release.tagName)) {
                        updateNotifier.showUpdateAvailable(remoteVersion)
                    }
                    settings.saveUpdateLastCheckTime(System.currentTimeMillis())
                }
                is com.raulshma.lenscast.update.model.UpdateCheckResult.UpToDate -> {
                    settings.saveUpdateLastCheckTime(System.currentTimeMillis())
                }
                else -> { /* RateLimited or Error: silent */ }
            }
        }
    }
}
