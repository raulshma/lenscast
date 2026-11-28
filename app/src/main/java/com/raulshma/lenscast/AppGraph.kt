package com.raulshma.lenscast

import com.raulshma.lenscast.automation.AutomationTestController
import com.raulshma.lenscast.automation.schedule.AutomationFireJournal
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.LightSensorMonitor
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
import com.raulshma.lenscast.gallery.GallerySession
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamStateJournal
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
    val lightSensorMonitor: LightSensorMonitor
    val streamingManager: StreamingManager
    val settingsDataStore: SettingsDataStore
    val mediaKeyProvider: MediaCrypto.KeyProvider
    val decryptedPhotoCache: DecryptedPhotoCache
    val captureHistoryStore: CaptureHistoryStore
    /**
     * The gallery's shared session: the one soft-delete staging truth
     * (pending set, undo, commit poller) plus the warm-decryption maps.
     * App-scoped so the gallery and viewer routes act on the same staged set
     * through one [com.raulshma.lenscast.gallery.GalleryViewModel] family
     * instead of two NavBackStackEntry-scoped copies.
     */
    val gallerySession: GallerySession
    /**
     * The gallery's LOCAL backend — [GallerySource] over [captureHistoryStore]
     * + [gallerySession] (deletes stage into the shared session, media
     * decodes from this phone's bytes). Process-scoped like the stores it
     * wraps: the routes' ViewModels are per-entry, the items and the staged
     * set are not. The remote counterpart is
     * [com.raulshma.lenscast.companion.di.CompanionGraph.gallerySource].
     */
    val localGallerySource: com.raulshma.lenscast.gallery.GallerySource
    val recordingController: RecordingController
    val timelapseComposer: TimelapseComposer
    val photoCaptureManager: PhotoCaptureManager
    val powerManager: PowerManager
    val thermalMonitor: ThermalMonitor
    val connectivityMonitor: ConnectivityMonitor
    val streamWatchdog: StreamWatchdog
    val streamingSession: StreamingSession
    /** The live keyguard truth + unlock cue ([KeyguardMonitor]); the locked-mount story's state source. */
    val keyguardMonitor: com.raulshma.lenscast.core.KeyguardMonitor
    /** The locked-mount camera wake ([com.raulshma.lenscast.core.LockBypassController]); the service's denied-promotion and the session's denied-bind trigger. */
    val lockBypassController: com.raulshma.lenscast.core.LockBypassController
    /**
     * The post-death camera bring-up prompt ([com.raulshma.lenscast.core.FsiResumeController],
     * blueprint P1 item 11): the service's server-only landing evaluates it,
     * the resume shim and MainActivity dismiss its notification.
     */
    val fsiResumeController: com.raulshma.lenscast.core.FsiResumeController
    val tlsCertManager: TlsCertManager
    val pushSubscriptionStore: PushSubscriptionStore
    val vapidKeys: VapidKeys
    val detectionModelStore: DetectionModelStore
    val audioModelStore: AudioModelStore
    val detectionCoordinator: DetectionCoordinator
    val detectionEventStore: DetectionEventStore
    /**
     * The event log's LOCAL backend — [com.raulshma.lenscast.capture.EventsSource]
     * over [detectionEventStore] (the screen's old inline store reads,
     * lifted into a source). The remote counterpart is
     * [com.raulshma.lenscast.companion.di.CompanionGraph.eventsSource].
     */
    val localEventsSource: com.raulshma.lenscast.capture.EventsSource
    /**
     * The capture screen's LOCAL backend —
     * [com.raulshma.lenscast.capture.CaptureSource] over this phone's
     * pipeline singletons ([recordingController], [photoCaptureManager],
     * [timelapseComposer], the settings store). The remote counterpart is
     * [com.raulshma.lenscast.companion.di.CompanionGraph.captureSource].
     */
    val localCaptureSource: com.raulshma.lenscast.capture.CaptureSource
    /**
     * The automation screen's LOCAL backend —
     * [com.raulshma.lenscast.automation.AutomationSource] over the settings
     * store's automation surface + [automationFireJournal] + the test
     * controller. The remote counterpart is
     * [com.raulshma.lenscast.companion.di.CompanionGraph.automationSource].
     */
    val localAutomationSource: com.raulshma.lenscast.automation.AutomationSource
    /**
     * The settings screens' LOCAL backend —
     * [com.raulshma.lenscast.settings.SettingsSource] over
     * [settingsDataStore] + the camera/power/model collaborators (the
     * SettingsViewModel's old graph members, lifted into a source). The
     * remote counterpart is
     * [com.raulshma.lenscast.companion.di.CompanionGraph.settingsSource].
     */
    val localSettingsSource: com.raulshma.lenscast.settings.SettingsSource
    val automationFireJournal: AutomationFireJournal
    /** The boot-resume journal — the boot path's and the runtime service's single source of stream intent. */
    val streamStateJournal: StreamStateJournal
    /**
     * The one start/stop ladder over every output
     * ([com.raulshma.lenscast.camera.model.StreamToggle]): the boot receiver
     * and the tile run their verdicts through it; the FSI-resume landing in
     * [com.raulshma.lenscast.streaming.LockBypassActivity] joins them so no
     * caller re-rolls a start path.
     */
    val streamToggle: com.raulshma.lenscast.camera.model.StreamToggle
    /** The crash marker — the crash handler's loop-guard counter; wave 3's telemetry reads its give-up state. */
    val crashMarkerJournal: com.raulshma.lenscast.core.CrashMarkerJournal
    /**
     * The alarm heartbeat (blueprint P0 item 3): arming off the server
     * lifecycle, and the fired-alarm verdict path the runtime service's
     * `ACTION_HEARTBEAT` start resolves.
     */
    val heartbeatController: com.raulshma.lenscast.core.HeartbeatController
    /**
     * The in-process soft-restart ladder (blueprint P1 item 9) — the quit
     * ladder minus process death. ONE implementation with TWO entry points:
     * the dashboard's `POST /api/system/restart?mode=soft` and the
     * heartbeat's SOFT_RESTART tier both run this instance, so the wire and
     * the alarm can never drift into two different recovery behaviors.
     */
    val softRestart: com.raulshma.lenscast.core.SoftRestartLadder
    val updateChecker: UpdateChecker
    val updateDownloader: UpdateDownloader
    val updateInstaller: UpdateInstaller
    val updateNotifier: UpdateNotifier

    /**
     * The companion mode's sub-graph: the paired-device roster, credential
     * store, mDNS discovery and live sessions the viewing/controlling half
     * of the app resolves — one lazy object instead of a dozen individual
     * graph members.
     */
    val companion: com.raulshma.lenscast.companion.di.CompanionGraph

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

    /**
     * Restart the app: schedule the launcher-intent relaunch on AlarmManager
     * (the one mechanism that survives the process death) and then run the
     * same [quitApplication] teardown. The relaunch is registered before the
     * quit guard flips, and the quit follows past a short settle so a
     * web-triggered restart's OK response flushes to the dashboard first
     * (issue #18's dashboard App-Restart button).
     */
    fun restartApplication()

    /**
     * Launch the in-process soft-restart ladder off the caller's thread,
     * past the HTTP response flush — the same settle-then-run shape
     * [restartApplication] uses, because the ladder's server stop tears down
     * the listener socket the triggering request rode on. The dashboard's
     * soft-restart route acknowledges before the ladder runs; the verdict
     * lands in the log.
     */
    fun launchSoftRestart()

    /**
     * The light sensor's dashboard-demand inputs: the SSE transport's client
     * count and the stamp of the last served plain status GET. The host owns
     * the flows and the one collector that folds them (with the pulse TTL
     * verdict in LightSensorPolicy, stamped on the monotonic
     * `SystemClock.elapsedRealtime` clock so a wall-clock correction cannot
     * stretch or cut the pulse) into the monitor's dashboard gate — the
     * Web API stack reports through these seams, never at the flag itself.
     */
    fun onLightSensorSseClients(count: Int)

    /** The per-served-status pulse arm ([onLightSensorSseClients]'s poller half). */
    fun onLightSensorStatusServed()

    /**
     * The Automation Test's arm/restore owner (issue #18 item 3): fires a
     * rule's actions once through the automation executor and winds the
     * touched axes back on restore. Pulled by the automation screen and the
     * web stack's test routes.
     */
    val automationTestController: AutomationTestController
}
