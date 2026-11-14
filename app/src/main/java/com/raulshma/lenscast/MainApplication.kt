package com.raulshma.lenscast

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.raulshma.lenscast.automation.AutomationActionExecutor
import com.raulshma.lenscast.automation.AutomationEventWiring
import com.raulshma.lenscast.automation.AutomationSnapshotProvider
import com.raulshma.lenscast.automation.schedule.AutomationFireJournal
import com.raulshma.lenscast.automation.schedule.AutomationScheduler
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.StreamToggle
import com.raulshma.lenscast.capture.BackupWorker
import com.raulshma.lenscast.capture.DecryptedPhotoCache
import com.raulshma.lenscast.capture.CaptureMediaResolver
import com.raulshma.lenscast.capture.DetectionCoordinator
import com.raulshma.lenscast.capture.DetectionEventStore
import com.raulshma.lenscast.capture.DetectionNotifier
import com.raulshma.lenscast.capture.PhotoCaptureManager
import com.raulshma.lenscast.capture.RecordingController
import com.raulshma.lenscast.capture.TamperMonitor
import com.raulshma.lenscast.capture.TamperResponsePolicy
import com.raulshma.lenscast.capture.TimelapseComposer
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.core.ConnectivityMonitor
import com.raulshma.lenscast.core.KeepAwakeController
import com.raulshma.lenscast.core.KeyguardMonitor
import com.raulshma.lenscast.core.KeystoreMediaKeyProvider
import com.raulshma.lenscast.core.LockBypassController
import com.raulshma.lenscast.core.MediaCrypto
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.SirenAutoStop
import com.raulshma.lenscast.core.StreamWatchdog
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.TlsCertManager
import com.raulshma.lenscast.core.WebhookNotifier
import com.raulshma.lenscast.core.mqtt.MqttAlertPublisher
import com.raulshma.lenscast.core.mqtt.MqttTopics
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.gallery.GallerySession
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.settings.SettingsApplier
import com.raulshma.lenscast.streaming.ServerForegroundController
import com.raulshma.lenscast.streaming.StreamStateJournal
import com.raulshma.lenscast.streaming.StreamStateJournalWriter
import com.raulshma.lenscast.streaming.StreamWidgetProvider
import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.StreamingSession
import com.raulshma.lenscast.streaming.StreamingTransports
import com.raulshma.lenscast.streaming.onvif.OnvifServer
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class MainApplication : Application(), SingletonImageLoader.Factory, AppGraph {
    override val cameraService: CameraService by lazy { CameraService(this) }
    override val streamingManager: StreamingManager by lazy {
        StreamingManager(
            this,
            thermalMonitor,
            // The Web API stack is composed in the app tier (WebApiStackBuilder
            // — the builder pulls ~15 graph collaborators the manager must not
            // reach into the composition root for). The manager hands itself
            // to the factory at the stack's first request (today, the
            // transport's eager initial server build, mid-construction) — so
            // the builder must take the manager as a value; reading
            // `streamingManager` here would re-enter this lazy and recurse.
            webApiStackFactory = { manager -> buildWebApiStack(manager, this) },
        )
    }
    override val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(this) }
    // The single media-at-rest key (Android Keystore, generated once) behind
    // the opt-in capture encryption; every encrypt/decrypt site resolves
    // through this one provider.
    override val mediaKeyProvider: MediaCrypto.KeyProvider by lazy { KeystoreMediaKeyProvider() }
    // The decrypted-photo cache the gallery's Coil loading rides when media
    // encryption is on (photos only — videos play through the decrypting
    // stream and preview as a placeholder).
    override val decryptedPhotoCache: DecryptedPhotoCache by lazy {
        DecryptedPhotoCache(this, CaptureMediaResolver(contentResolver, mediaKeyProvider))
    }
    override val captureHistoryStore: CaptureHistoryStore by lazy {
        CaptureHistoryStore(
            this,
            retentionDays = { settingsDataStore.captureRetentionDays.value },
            quotaMb = { settingsDataStore.storageQuotaMb.value },
            // A capture's decrypted-photo cache file dies with the capture —
            // whatever removed it (manual, batch, retention, quota).
            onEntriesDeleted = { deleted -> decryptedPhotoCache.deleteAll(deleted) },
        )
    }
    // The gallery package's one session (undo staging + decryption warm
    // loop) — the AppGraph member the gallery screens read instead of
    // reaching into the store and cache separately.
    override val gallerySession: GallerySession by lazy {
        GallerySession(
            captureHistoryStore = captureHistoryStore,
            photoCache = decryptedPhotoCache,
            appScope = appScope,
        )
    }
    override val recordingController: RecordingController by lazy { RecordingController(this) }
    // The one timelapse pipeline, shared by the capture screen's manual
    // assembly and the interval worker's on-completion auto-assembly — one
    // place owns the MediaStore + encryption publish seam.
    override val timelapseComposer: TimelapseComposer by lazy {
        TimelapseComposer(
            this,
            captureHistoryStore,
            encryptionEnabled = { settingsDataStore.mediaEncryptionEnabled.value },
            mediaKeyProvider = mediaKeyProvider,
        )
    }
    override val photoCaptureManager: PhotoCaptureManager by lazy {
        PhotoCaptureManager(
            this,
            cameraService,
            captureHistoryStore,
            encryptionEnabled = { settingsDataStore.mediaEncryptionEnabled.value },
            mediaKeyProvider = mediaKeyProvider,
        )
    }
    override val powerManager: PowerManager by lazy { PowerManager(this) }
    override val thermalMonitor: ThermalMonitor by lazy { ThermalMonitor(this) }
    override val connectivityMonitor: ConnectivityMonitor by lazy { ConnectivityMonitor(this) }
    // Watchdog and session reference the manager and each other; every such
    // edge is a provider resolved on first use. A direct reference would
    // re-enter streamingManager's lazy initializer mid-construction (the
    // manager builds the Web API stack, which builds the watchdog) and
    // recurse until the heap dies at launch.
    override val streamWatchdog: StreamWatchdog by lazy {
        StreamWatchdog(cameraService, { streamingManager }, streamingSession)
    }
    override val streamingSession: StreamingSession by lazy {
        StreamingSession(
            this, cameraService, { streamingManager }, powerManager, thermalMonitor,
            keyguardMonitor, lockBypassController,
        ) { streamWatchdog }
    }
    // The locked-mount story's state source: is the keyguard up, and when did
    // the user unlock — plus the wake that makes a locked-mount bind
    // legitimate. Plain Android over the application context — no cycle with
    // the streaming graph (the session reads the monitor during begin; the
    // controller reads it before posting the wake, and the session asks it to
    // post on a denied bind). Both lazies depend only on the context and each
    // other's already-declared pieces, so the reference order is free.
    override val keyguardMonitor: KeyguardMonitor by lazy { KeyguardMonitor(this) }
    override val lockBypassController: LockBypassController by lazy {
        LockBypassController(this, keyguardMonitor)
    }
    // Server state -> runtime service server mode. The session still owns the
    // FGS intents; this is the app-lifetime bridge that keeps the server-only
    // mode in sync with every server start/stop path (launch, port/TLS
    // restart, StreamToggle.stopServer, watchdog recovery).
    val serverForegroundController: ServerForegroundController by lazy {
        ServerForegroundController(streamingSession, streamingManager.isServerRunning)
    }
    val settingsApplier: SettingsApplier by lazy {
        SettingsApplier(settingsDataStore, cameraService, streamingManager, streamWatchdog, mqttAlertPublisher, onvifServer)
    }
    // The ONVIF device service reads its advertised values live per request
    // (the same IP/URL sources the mDNS registration and the RTSP URL builder
    // use), so only the firmware version and serial are fixed at construction.
    // Composed before the applier runs and registered as the process default
    // the streaming server's dispatch resolves.
    val onvifServer: OnvifServer by lazy {
        OnvifServer(
            ipAddress = { NetworkUtils.getLocalIpAddress() },
            rtspPort = { settingsDataStore.rtspPort.value },
            webPort = { settingsDataStore.streamingPort.value },
            audioEnabled = { settingsDataStore.streamAudioEnabled.value },
            enabled = { settingsDataStore.onvifEnabled.value },
            httpsEnabled = { settingsDataStore.httpsEnabled.value },
            videoWidth = { settingsDataStore.rtspResolution.value.width },
            videoHeight = { settingsDataStore.rtspResolution.value.height },
            videoBitrate = { streamingManager.currentVideoBitrate() },
            videoFps = { settingsDataStore.settings.value.frameRate },
            videoCodec = { settingsDataStore.rtspVideoCodec.value },
            firmwareVersion = com.raulshma.lenscast.BuildConfig.VERSION_NAME,
            model = android.os.Build.MODEL?.ifBlank { null } ?: "LensCast",
            serialNumber = deviceId(),
            context = this,
        ).also { OnvifServer.compose(it) }
    }
    val webhookNotifier: WebhookNotifier by lazy {
        WebhookNotifier(configProvider = {
            settingsDataStore.webhookEnabled.value to settingsDataStore.webhookUrl.value
        })
    }
    // The MQTT alert publisher reads its config live per dispatch (the same
    // live-read contract as the webhook above); the device id derives once
    // per process — it is stable for the installation's lifetime. The
    // telemetry half reads the push outputs' live flags and the sensor
    // snapshot (battery/thermal/bitrate/clients) live per announce/tick.
    val mqttAlertPublisher: MqttAlertPublisher by lazy {
        MqttAlertPublisher(
            configProvider = {
                MqttAlertPublisher.Config(
                    enabled = settingsDataStore.mqttEnabled.value,
                    broker = MqttAlertPublisher.Broker(
                        host = settingsDataStore.mqttBrokerHost.value,
                        port = settingsDataStore.mqttBrokerPort.value,
                        username = settingsDataStore.mqttUsername.value,
                        password = settingsDataStore.mqttPassword.value,
                        tls = settingsDataStore.mqttTls.value,
                    ),
                    discoveryPrefix = settingsDataStore.mqttDiscoveryPrefix.value,
                    telemetryEnabled = settingsDataStore.mqttTelemetryEnabled.value,
                )
            },
            deviceId = deviceId(),
            deviceName = android.os.Build.MODEL?.ifBlank { null } ?: "LensCast",
            streamStatesProvider = {
                mapOf(
                    MqttTopics.StreamOutput.WEB to
                        streamingManager.isWebStreamingActive.value,
                    MqttTopics.StreamOutput.RTSP to
                        streamingManager.isRtspRunning.value,
                    MqttTopics.StreamOutput.RTMP to
                        streamingManager.isRtmpActive(),
                    MqttTopics.StreamOutput.WHIP to
                        streamingManager.isWhipActive(),
                    MqttTopics.StreamOutput.SRT to
                        streamingManager.isSrtActive(),
                )
            },
            telemetrySnapshotProvider = {
                MqttAlertPublisher.TelemetrySnapshot(
                    batteryPercent = powerManager.batteryLevel.value,
                    thermal = thermalMonitor.thermalState.value.name,
                    encodedBitrateBps = streamingManager.currentVideoBitrate(),
                    activeClients = streamingManager.telemetryActiveClientCount(),
                )
            },
        )
    }
    val detectionNotifier: DetectionNotifier by lazy {
        DetectionNotifier(this)
    }
    // The VAPID identity for Web Push: one persistent P-256 pair, load-once
    // like the TLS identity — a rotated key silently orphans every browser
    // subscription bound to the old public key.
    override val vapidKeys: com.raulshma.lenscast.core.push.VapidKeys by lazy {
        com.raulshma.lenscast.core.push.VapidKeys.loadOrCreate(
            com.raulshma.lenscast.core.push.VapidKeys.keyFile(this),
        )
    }
    override val pushSubscriptionStore: com.raulshma.lenscast.core.push.PushSubscriptionStore by lazy {
        com.raulshma.lenscast.core.push.PushSubscriptionStore(this)
    }
    // The Web Push alert sink reads its config live per dispatch (the same
    // live-read contract as the webhook and MQTT above).
    val webPushSender: com.raulshma.lenscast.core.push.WebPushSender by lazy {
        com.raulshma.lenscast.core.push.WebPushSender(
            configProvider = {
                com.raulshma.lenscast.core.push.WebPushSender.Config(
                    browserEnabled = settingsDataStore.pushEnabled.value,
                    unifiedPushEnabled = settingsDataStore.unifiedPushEnabled.value,
                    // An invalid sub makes every push service reject the JWT at
                    // dispatch — fall open to the default contact instead of
                    // failing silently (the settings field flags it live).
                    subject = com.raulshma.lenscast.core.push.VapidSubjectPolicy.orDefault(
                        settingsDataStore.pushVapidSubject.value,
                        com.raulshma.lenscast.core.StreamDefaults.PUSH_VAPID_SUBJECT_DEFAULT,
                    ),
                )
            },
            store = pushSubscriptionStore,
            vapidKeys = vapidKeys,
        )
    }
    // The ML detection model lives in app-private storage, not the APK; one
    // store owns the download + integrity so the coordinator's gate and the
    // settings screen share one state.
    override val detectionModelStore: DetectionModelStore by lazy {
        DetectionModelStore(java.io.File(filesDir, DetectionModelStore.DEFAULT_DIR_NAME))
    }
    // The YAMNet audio-classification model: the same on-demand contract, one
    // store per model — the coordinator's classifier and the settings row
    // share this state.
    override val audioModelStore: AudioModelStore by lazy {
        AudioModelStore(java.io.File(filesDir, AudioModelStore.DEFAULT_DIR_NAME))
    }
    override val detectionCoordinator: DetectionCoordinator by lazy {
        DetectionCoordinator(
            appContext = this,
            settingsDataStore = settingsDataStore,
            recordingController = recordingController,
            photoCaptureManager = photoCaptureManager,
            webhookNotifier = webhookNotifier,
            eventStore = detectionEventStore,
            captureHistoryStore = captureHistoryStore,
            detectionModelStore = detectionModelStore,
            audioModelStore = audioModelStore,
            streamingManager = { streamingManager },
            cameraService = { cameraService },
            mqttPublisher = { mqttAlertPublisher },
            webPushSender = { webPushSender },
            detectionNotifier = { detectionNotifier },
            batteryPercent = { powerManager.batteryLevel.value },
            sirenAutoStop = sirenAutoStop,
        )
    }
    override val tlsCertManager: TlsCertManager by lazy { TlsCertManager(this) }
    override val updateChecker: UpdateChecker by lazy { UpdateChecker(this) }
    override val updateNotifier: UpdateNotifier by lazy { UpdateNotifier(this) }
    val updateCheckPipeline: UpdateCheckPipeline by lazy {
        UpdateCheckPipeline.production(updateChecker, updateNotifier, settingsDataStore)
    }
    // App-owned like the checker and notifier above, so the settings screen's
    // ViewModel factory pulls every update collaborator off the Application
    // instead of constructing per-composition adapters.
    override val updateDownloader: UpdateDownloader by lazy { UpdateDownloader(this) }
    override val updateInstaller: UpdateInstaller by lazy { UpdateInstaller(this) }
    val streamStateJournal: StreamStateJournal by lazy { StreamStateJournal(this) }
    // The automation scheduler's once-per-day dedup journal: the rules live
    // in DataStore and survive every process start, so the journal is the
    // half that records which occurrences already ran — the boot-time
    // catch-up reads it and never double-fires them. Values carry both
    // record kinds (time occurrence keys and event-fire timestamps); the
    // schedule policy separates them by magnitude.
    override val automationFireJournal: AutomationFireJournal by lazy { AutomationFireJournal(this) }
    // The in-app automation scheduler (time rules on the 30 s poll, event
    // rules on the wiring's cues): the second front-end over the executor's
    // seams (the receiver is the first, constructing one per broadcast; the
    // scheduler owns one for its lifetime — both are stateless routes onto
    // the same seam graph).
    val automationScheduler: AutomationScheduler by lazy {
        AutomationScheduler(
            settingsDataStore,
            automationFireJournal,
            AutomationActionExecutor(this),
            AutomationSnapshotProvider(
                powerManager,
                connectivityMonitor,
                thermalMonitor,
                streamingManager,
                cameraService,
                recordingController,
                settingsDataStore,
            ),
        )
    }
    // The scheduler's event plumbing: detection events, charger edges,
    // battery level changes, stream start/stop and viewer join/leave —
    // started in onCreate alongside the loop so event rules fire headless.
    val automationEventWiring: AutomationEventWiring by lazy {
        AutomationEventWiring(
            scheduler = automationScheduler,
            detectionEventStore = detectionEventStore,
            powerManager = powerManager,
            streamingManager = streamingManager,
            scope = appScope,
        )
    }
    // The one siren auto-stop timer for the whole process: the detection
    // coordinator's deterrence and the automation receiver's SET_SIREN must
    // land on the same timer, or a stale one owner's timer could cut the
    // other's siren short and a manual stop could not cancel a pending one.
    val sirenAutoStop: SirenAutoStop by lazy {
        SirenAutoStop(appScope)
    }
    // Process-singleton via [DetectionEventStore.get] — the writer (the
    // coordinator) and the reader (the Web API handler) must share one
    // in-memory list — owned here like every other collaborator. The
    // retention provider is read per sweep, so a settings change takes
    // effect on the next open/append without re-creating the store.
    override val detectionEventStore: DetectionEventStore by lazy {
        DetectionEventStore.get(this, retentionDays = { settingsDataStore.eventRetentionDays.value })
    }
    // One toggle over one transports adapter — the boot receiver and the
    // quick-settings tile start/stop through the same ladder the camera
    // screen and the Web API use, without re-typing the adapter.
    val streamToggle: StreamToggle by lazy {
        StreamToggle(StreamingTransports(streamingManager, streamingSession))
    }
    /** App-lifetime scope for work that must outlive a ViewModel or screen. */
    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The single-owner guard for the quit ladder ([quitApplication]): the
     * first invocation flips it and owns the teardown; every re-invocation
     * while the ladder runs or after it returns (exitProcess pending) is a
     * no-op. @Volatile: quit calls can arrive from any thread (main UI, a
     * broadcast receiver, a web handler), and the flag must be visible
     * across them without a lock the ladder would then hold.
     */
    @Volatile
    private var isQuitting = false

    override fun onCreate() {
        super.onCreate()
        connectivityMonitor.start()
        keyguardMonitor.start()
        settingsApplier.start(appScope)
        serverForegroundController.start(appScope)
        wireFramePump()
        initializeStreamStateJournal()
        initializeAutomationScheduler()
        initializeKeepAwake()
        initializeTamperMonitor()
        initializeDetectionMicWatch()
        initializeWidgetRefresh()
        initializeAutoUpdateCheck()
        initializeUnlockHeal()
    }

    /**
     * Retry the server-only foreground promotion from a visible Activity.
     * Android can deny a start issued while the process is in the background
     * (for example a boot-time or broadcast-start server); the server keeps
     * running and the next user visit restores guaranteed alert delivery.
     */
    override fun ensureServerForeground() = serverForegroundController.sync()

    /**
     * The user quit (the camera screen's overflow menu — the only caller
     * today, though the web/automation surfaces could call it): the
     * best-effort graceful teardown before the process kill — a live
     * recording stops first (its finalize must not be cut short mid-write),
     * then the one server stop ladder ([StreamToggle.stopServer] — every
     * output, the transport, mDNS and the session, dropping the foreground
     * service, wake lock and monitors while the journal and widget
     * collectors observe the transitions) — then [exitProcess], which is the
     * point of the menu item: the death is deliberate, so nothing here can
     * postpone it beyond the teardown's grace window.
     *
     * Single-owner guard: [isQuitting] is set by the FIRST invocation and
     * every later one no-ops, so a re-entry while the ladder runs (a second
     * menu tap, a broadcast racing the menu) cannot run the stop ladder
     * twice — a double stop would re-enter the recording finalize and the
     * server teardown mid-flight — nor queue a second [exitProcess] behind
     * the pending one.
     */
    override fun quitApplication() {
        if (isQuitting) return
        isQuitting = true
        appScope.launch {
            runCatching { recordingController.stop() }
            runCatching { streamToggle.stopServer() }
            delay(QUIT_TEARDOWN_GRACE_MS)
            exitProcess(0)
        }
    }

    /**
     * The installation-scoped MQTT client id / HA unique-id stem. ANDROID_ID
     * is stable per app install; a read failure falls back to a persisted
     * random id — a constant would give every such install the same MQTT
     * client id, and the broker would keep kicking them off each other.
     */
    private fun deviceId(): String {
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (androidId != null) return androidId
        val prefs = getSharedPreferences(DEVICE_ID_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(DEVICE_ID_KEY, null)
            ?: java.util.UUID.randomUUID().toString().also {
                prefs.edit().putString(DEVICE_ID_KEY, it).apply()
            }
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
     * The time-of-day scheduler is app-runtime composition, not screen state:
     * rules must fire even when no screen has composed (headless start via
     * the tile or the Web API). start() is idempotent and its immediate first
     * evaluation IS the boot catch-up — the persisted rules plus the fire
     * journal mean a process restart never replays an occurrence.
     */
    private fun initializeAutomationScheduler() {
        automationScheduler.start()
        automationEventWiring.start()
    }

    /**
     * Keep-awake is app-runtime composition, not screen state: the
     * security-phone use case needs the CPU awake while the screen is locked
     * — the automation engine is a 30-second poll loop with no alarms, and
     * without the hold a suspended CPU silently stops every rule, detection
     * path and stream at once. The hold rides the keep-awake setting (on by
     * default); quitting is the exit ladder's process death, which releases
     * every wakelock — the controller needs no teardown.
     */
    private fun initializeKeepAwake() {
        KeepAwakeController(
            keepAwakeEnabledFromDisk = { settingsDataStore.keepAwakeEnabledFromDisk() },
            keepAwakeEnabled = settingsDataStore.keepAwakeEnabled,
            powerManager = powerManager,
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
        // and the alert fan-out (webhook, MQTT, Web Push, local notification)
        // — never a screen's lifetime.
        streamingManager.setMotionListener { delta, zones -> detectionCoordinator.onMotion(delta, zones) }
        streamingManager.setSoundListener { rms -> detectionCoordinator.onSound(rms) }
        // Sound classification rides the same audio chunks: the coordinator
        // gates the feed on its own settings (feature + sound detection on)
        // and the classifier runs off the audio path — annotate-only, never
        // suppressing the RMS events above.
        streamingManager.setSoundClassificationTap { pcm16 ->
            detectionCoordinator.feedSoundClassification(
                pcm16,
                streamingManager.audioCaptureSampleRateHz(),
                streamingManager.audioCaptureChannelCount(),
            )
        }
    }

    /**
     * Tamper detection is app-runtime composition, not screen state: a power
     * cut must raise an event even when no screen has composed (headless
     * streaming via the tile or the Web API). The monitor only fires when the
     * setting is on and a stream is live — those gates read live, so the
     * collect runs for the process lifetime and stays inert otherwise.
     *
     * The response beyond the alert fan-out is [TamperResponsePolicy]'s,
     * executed here: flush the detection-event log to disk, and (when backup
     * is armed) cancel-and-re-enqueue every pending capture backup as an
     * expedited request that skips the Wi-Fi-only gate — the priority ladder
     * for a camera that may never see power again.
     */
    private fun initializeTamperMonitor() {
        TamperMonitor(
            isCharging = powerManager.isCharging,
            batteryPercent = { powerManager.batteryLevel.value },
            enabled = { settingsDataStore.tamperDetectionEnabled.value },
            isStreamActive = { streamingManager.isLiveStreaming() },
            onTamper = { batteryPercentValue -> runTamperResponse(batteryPercentValue) },
            scope = appScope,
        ).start()
    }

    /**
     * The sound-detection mic hold is app-runtime composition, not screen
     * state: arming detection from the Web API (or a settings sync) starts a
     * headless capture, and the foreground service must re-assert its
     * MICROPHONE type or the OS silences the capture. The manager's flow
     * flips only when the detection-only hold starts or stops, so the
     * re-assert runs exactly on those transitions.
     */
    private fun initializeDetectionMicWatch() {
        appScope.launch {
            streamingManager.isDetectionMicActive.collect {
                runCatching { streamingSession.refreshForegroundMicVerdict() }
                    .onFailure { android.util.Log.w("MainApplication", "Mic verdict re-assert failed: ${it.message}") }
            }
        }
    }

    /**
     * The unlock heal is app-runtime composition, not screen state: a stream
     * started while the device was locked (dashboard, tile, boot resume) may
     * have had its camera bind denied, and the earliest retry cue is the
     * keyguard dismissal — not the next app-visible resume, which may never
     * come on a headless mount. Best-effort and idempotent: a still-denied
     * bind leaves the blocked-by-lock flag standing for the resume seam or
     * the lock-bypass shim, and the streaming promotion re-assert rides
     * along.
     */
    private fun initializeUnlockHeal() {
        appScope.launch {
            keyguardMonitor.unlockEvents.collect {
                runCatching { streamingSession.reassertStreamingForegroundIfActive() }
                runCatching { streamingSession.rebindOnUnlock() }
            }
        }
    }

    private fun runTamperResponse(batteryPercentValue: Int?) {
        val verdict = TamperResponsePolicy.decide(
            backupEnabled = settingsDataStore.backupEnabled.value,
        )
        // The alert fan-out (webhook/MQTT/notification/deterrence + the event
        // log entry) first — the response below preserves what it produces.
        detectionCoordinator.onTamper(batteryPercentValue)
        appScope.launch {
            // The tamper event's own record persists inline; the flush is the
            // guarantee that the log's whole current state is on disk before
            // the power can die.
            if (verdict.flushEventLog) {
                runCatching { detectionEventStore.flush() }
                    .onFailure { android.util.Log.w("MainApplication", "Event-log flush failed: ${it.message}") }
            }
            if (verdict.expediteBackup) {
                runCatching {
                    BackupWorker.expeditePending(
                        this@MainApplication,
                        captureHistoryStore.history.value.map { it.filePath },
                    )
                }.onFailure {
                    android.util.Log.w("MainApplication", "Tamper backup expedite failed: ${it.message}")
                }
            }
        }
    }

    /**
     * Widget state honesty beyond its own taps: a stream toggled from the
     * Quick Settings tile or the Web API must not leave the home-screen
     * widget's label stale, so the manager's live output flows re-render the
     * widget on every transition. The refresh is idempotent and cheap, and
     * the collector covers all five live outputs (web, RTSP, and the
     * RTMP/WHIP/SRT push statuses) — a push-only stream must not leave the
     * widget reading "Idle" while [StreamingManager.isLiveStreaming] is true.
     */
    private fun initializeWidgetRefresh() {
        appScope.launch {
            combine(
                streamingManager.isWebStreamingActive,
                streamingManager.isRtspRunning,
                streamingManager.rtmpStatus,
                streamingManager.whipStatus,
                streamingManager.srtStatus,
            ) { _, _, _, _, _ -> }
                .collect {
                    StreamWidgetProvider.refresh(this@MainApplication)
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

    companion object {
        private const val DEVICE_ID_PREFS = "lenscast_device_id"
        private const val DEVICE_ID_KEY = "fallback_device_id"

        /** The teardown window between the stop ladder and the process kill. */
        private const val QUIT_TEARDOWN_GRACE_MS = 500L
    }
}
