package com.raulshma.lenscast

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.StreamToggle
import com.raulshma.lenscast.capture.DetectionCoordinator
import com.raulshma.lenscast.capture.DetectionEventStore
import com.raulshma.lenscast.capture.PhotoCaptureManager
import com.raulshma.lenscast.capture.RecordingController
import com.raulshma.lenscast.core.ConnectivityMonitor
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.TlsCertManager
import com.raulshma.lenscast.core.WebhookNotifier
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.settings.SettingsApplier
import com.raulshma.lenscast.streaming.StreamStateJournal
import com.raulshma.lenscast.streaming.StreamStateJournalWriter
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import com.raulshma.lenscast.streaming.StreamingTransports
import com.raulshma.lenscast.update.UpdateChecker
import com.raulshma.lenscast.update.UpdateCheckPipeline
import com.raulshma.lenscast.update.UpdateDownloader
import com.raulshma.lenscast.update.UpdateInstaller
import com.raulshma.lenscast.update.UpdateNotifier
import com.raulshma.lenscast.update.UpdatePolicy
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
    // Watchdog and session reference the manager and each other; every such
    // edge is a provider resolved on first use. A direct reference would
    // re-enter streamingManager's lazy initializer mid-construction (the
    // manager builds the Web API stack, which builds the watchdog) and
    // recurse until the heap dies at launch.
    val streamWatchdog: StreamWatchdog by lazy {
        StreamWatchdog(cameraService, { streamingManager }, streamingSession)
    }
    val streamingSession: StreamingSession by lazy {
        StreamingSession(
            this, cameraService, { streamingManager }, powerManager, thermalMonitor,
        ) { streamWatchdog }
    }
    val settingsApplier: SettingsApplier by lazy {
        SettingsApplier(settingsDataStore, cameraService, streamingManager, streamWatchdog)
    }
    val webhookNotifier: WebhookNotifier by lazy {
        WebhookNotifier(configProvider = {
            settingsDataStore.webhookEnabled.value to settingsDataStore.webhookUrl.value
        })
    }
    val detectionCoordinator: DetectionCoordinator by lazy {
        DetectionCoordinator(
            settingsDataStore = settingsDataStore,
            recordingController = recordingController,
            photoCaptureManager = photoCaptureManager,
            webhookNotifier = webhookNotifier,
            eventStore = detectionEventStore,
            streamingManager = { streamingManager },
            cameraService = { cameraService },
        )
    }
    val tlsCertManager: TlsCertManager by lazy { TlsCertManager(this) }
    val updateChecker: UpdateChecker by lazy { UpdateChecker(this) }
    val updateNotifier: UpdateNotifier by lazy { UpdateNotifier(this) }
    val updateCheckPipeline: UpdateCheckPipeline by lazy {
        UpdateCheckPipeline.production(updateChecker, updateNotifier, settingsDataStore)
    }
    // App-owned like the checker and notifier above, so the settings screen's
    // ViewModel factory pulls every update collaborator off the Application
    // instead of constructing per-composition adapters.
    val updateDownloader: UpdateDownloader by lazy { UpdateDownloader(this) }
    val updateInstaller: UpdateInstaller by lazy { UpdateInstaller(this) }
    val streamStateJournal: StreamStateJournal by lazy { StreamStateJournal(this) }
    // Process-singleton via [DetectionEventStore.get] — the writer (the
    // coordinator) and the reader (the Web API handler) must share one
    // in-memory list — owned here like every other collaborator.
    val detectionEventStore: DetectionEventStore by lazy { DetectionEventStore.get(this) }
    // One toggle over one transports adapter — the boot receiver and the
    // quick-settings tile start/stop through the same ladder the camera
    // screen and the Web API use, without re-typing the adapter.
    val streamToggle: StreamToggle by lazy {
        StreamToggle(StreamingTransports(streamingManager, streamingSession))
    }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        connectivityMonitor.start()
        settingsApplier.start(appScope)
        wireFramePump()
        initializeStreamStateJournal()
        initializeAutoUpdateCheck()
    }

    /**
     * Boot-resume journaling is app-runtime composition, not screen state: the
     * journal must track the manager's live output flows even when no camera
     * screen has composed (headless start via the Web API or the tile), so the
     * boot receiver always reads the last observed stream state.
     */
    private fun initializeStreamStateJournal() {
        StreamStateJournalWriter(
            journal = streamStateJournal,
            webActive = streamingManager.isWebStreamingActive,
            rtspActive = streamingManager.isRtspRunning,
            scope = appScope,
        ).start()
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
        // Detection events (motion + sound) dispatch through the coordinator:
        // schedule arming, bounded motion recording or the legacy auto-photo,
        // and the webhook — never a screen's lifetime.
        streamingManager.setDetectionListener { type, value ->
            when (type) {
                DetectionCoordinator.EVENT_TYPE_MOTION -> detectionCoordinator.onMotion(value)
                DetectionCoordinator.EVENT_TYPE_SOUND -> detectionCoordinator.onSound(value)
            }
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

    /**
     * Startup auto-check: only the gating (24h clock + enabled flag) and the
     * delay live here — the check itself is [UpdateCheckPipeline]'s, shared
     * with the manual check, and its outcome needs no UI on startup.
     */
    private fun initializeAutoUpdateCheck() {
        // The fdroid flavor ships without the self-updater (F-Droid policy);
        // its update surfaces are disabled at the composition root.
        if (!com.raulshma.lenscast.BuildConfig.SELF_UPDATE) return
        appScope.launch {
            delay(3_000)
            val enabled = settingsDataStore.updateAutoCheckEnabled.first()
            val lastCheck = settingsDataStore.updateLastCheckTime.first()
            if (!UpdatePolicy.shouldAutoCheck(lastCheck, enabled)) return@launch
            updateCheckPipeline.runCheck()
        }
    }
}
