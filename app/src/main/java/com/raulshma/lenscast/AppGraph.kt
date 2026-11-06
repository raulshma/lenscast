package com.raulshma.lenscast

import com.raulshma.lenscast.automation.schedule.AutomationFireJournal
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.capture.DecryptedPhotoCache
import com.raulshma.lenscast.capture.DetectionCoordinator
import com.raulshma.lenscast.capture.DetectionEventStore
import com.raulshma.lenscast.capture.PhotoCaptureManager
import com.raulshma.lenscast.capture.RecordingController
import com.raulshma.lenscast.capture.TimelapseComposer
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.core.ConnectivityMonitor
import com.raulshma.lenscast.core.MediaCrypto
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.TlsCertManager
import com.raulshma.lenscast.core.push.PushSubscriptionStore
import com.raulshma.lenscast.core.push.VapidKeys
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import com.raulshma.lenscast.update.UpdateChecker
import com.raulshma.lenscast.update.UpdateDownloader
import com.raulshma.lenscast.update.UpdateInstaller
import com.raulshma.lenscast.update.UpdateNotifier
import kotlinx.coroutines.CoroutineScope

/**
 * The app's dependency graph seam: the collaborators the composition root
 * (MainApplication) owns for the process lifetime, named so the rest of the
 * codebase can depend on them without depending on the composition root
 * itself. Before this interface every screen factory, service, and streaming
 * component hard-cast the Application (`context.applicationContext as
 * MainApplication`) to pull its collaborators — implementations reached up
 * into the composition-root class at runtime, inverting the dependency
 * direction. Consumers now cast to [AppGraph] and read the graph's types;
 * MainApplication is the graph's Android host — the class the OS
 * instantiates, whose lazy `val`s satisfy the interface directly (same
 * members, same lazy-init timing, same singleton identity).
 *
 * The surface is exactly what consumers pull — no wider. A member belongs
 * here when a screen ViewModel factory, a service, a worker, or a streaming
 * component resolves it off the application; app-runtime composition no
 * consumer reads (the onCreate wiring, the settings applier, the update
 * pipeline, the push sender) stays private to the host class.
 */
interface AppGraph {
    val cameraService: CameraService
    val streamingManager: StreamingManager
    val settingsDataStore: SettingsDataStore
    val mediaKeyProvider: MediaCrypto.KeyProvider
    val decryptedPhotoCache: DecryptedPhotoCache
    val captureHistoryStore: CaptureHistoryStore
    val recordingController: RecordingController
    val timelapseComposer: TimelapseComposer
    val photoCaptureManager: PhotoCaptureManager
    val powerManager: PowerManager
    val thermalMonitor: ThermalMonitor
    val connectivityMonitor: ConnectivityMonitor
    val streamWatchdog: StreamWatchdog
    val streamingSession: StreamingSession
    val tlsCertManager: TlsCertManager
    val pushSubscriptionStore: PushSubscriptionStore
    val vapidKeys: VapidKeys
    val detectionModelStore: DetectionModelStore
    val audioModelStore: AudioModelStore
    val detectionCoordinator: DetectionCoordinator
    val detectionEventStore: DetectionEventStore
    val automationFireJournal: AutomationFireJournal
    val updateChecker: UpdateChecker
    val updateDownloader: UpdateDownloader
    val updateInstaller: UpdateInstaller
    val updateNotifier: UpdateNotifier

    /** App-lifetime scope for work that must outlive a ViewModel or screen. */
    val appScope: CoroutineScope

    /**
     * Retry the server-only foreground promotion from a visible Activity.
     * Android can deny a start issued while the process is in the background
     * (for example a boot-time or broadcast-start server); the server keeps
     * running and the next user visit restores guaranteed alert delivery.
     */
    fun ensureServerForeground()

    /**
     * The user quit (the camera screen's overflow menu): the best-effort
     * graceful teardown before the process kill — a live recording stops
     * first, then the one server stop ladder, then the deliberate exit.
     */
    fun quitApplication()
}
