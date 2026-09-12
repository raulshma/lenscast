package com.raulshma.lenscast

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
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
import com.raulshma.lenscast.core.KeystoreMediaKeyProvider
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
import com.raulshma.lenscast.core.NetworkUtils
import com.raulshma.lenscast.settings.SettingsApplier
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

class MainApplication : Application(), SingletonImageLoader.Factory {
    val cameraService: CameraService by lazy { CameraService(this) }
    val streamingManager: StreamingManager by lazy { StreamingManager(this, thermalMonitor) }
    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(this) }
    // The single media-at-rest key (Android Keystore, generated once) behind
    // the opt-in capture encryption; every encrypt/decrypt site resolves
    // through this one provider.
    val mediaKeyProvider: MediaCrypto.KeyProvider by lazy { KeystoreMediaKeyProvider() }
    // The decrypted-photo cache the gallery's Coil loading rides when media
    // encryption is on (photos only — videos play through the decrypting
    // stream and preview as a placeholder).
    val decryptedPhotoCache: DecryptedPhotoCache by lazy {
        DecryptedPhotoCache(this, CaptureMediaResolver(contentResolver, mediaKeyProvider))
    }
    val captureHistoryStore: CaptureHistoryStore by lazy {
        CaptureHistoryStore(
            this,
            retentionDays = { settingsDataStore.captureRetentionDays.value },
            quotaMb = { settingsDataStore.storageQuotaMb.value },
            // A capture's decrypted-photo cache file dies with the capture —
            // whatever removed it (manual, batch, retention, quota).
            onEntriesDeleted = { deleted -> decryptedPhotoCache.deleteAll(deleted) },
        )
    }
    val recordingController: RecordingController by lazy { RecordingController(this) }
    // The one timelapse pipeline, shared by the capture screen's manual
    // assembly and the interval worker's on-completion auto-assembly — one
    // place owns the MediaStore + encryption publish seam.
    val timelapseComposer: TimelapseComposer by lazy {
        TimelapseComposer(
            this,
            captureHistoryStore,
            encryptionEnabled = { settingsDataStore.mediaEncryptionEnabled.value },
            mediaKeyProvider = mediaKeyProvider,
        )
    }
    val photoCaptureManager: PhotoCaptureManager by lazy {
        PhotoCaptureManager(
            this,
            cameraService,
            captureHistoryStore,
            encryptionEnabled = { settingsDataStore.mediaEncryptionEnabled.value },
            mediaKeyProvider = mediaKeyProvider,
        )
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
    val vapidKeys: com.raulshma.lenscast.core.push.VapidKeys by lazy {
        com.raulshma.lenscast.core.push.VapidKeys.loadOrCreate(
            com.raulshma.lenscast.core.push.VapidKeys.keyFile(this),
        )
    }
    val pushSubscriptionStore: com.raulshma.lenscast.core.push.PushSubscriptionStore by lazy {
        com.raulshma.lenscast.core.push.PushSubscriptionStore(this)
    }
    // The Web Push alert sink reads its config live per dispatch (the same
    // live-read contract as the webhook and MQTT above).
    val webPushSender: com.raulshma.lenscast.core.push.WebPushSender by lazy {
        com.raulshma.lenscast.core.push.WebPushSender(
            configProvider = {
                com.raulshma.lenscast.core.push.WebPushSender.Config(
                    enabled = settingsDataStore.pushEnabled.value,
                    subject = settingsDataStore.pushVapidSubject.value,
                )
            },
            store = pushSubscriptionStore,
            vapidKeys = vapidKeys,
        )
    }
    // The ML detection model lives in app-private storage, not the APK; one
    // store owns the download + integrity so the coordinator's gate and the
    // settings screen share one state.
    val detectionModelStore: DetectionModelStore by lazy {
        DetectionModelStore(java.io.File(filesDir, DetectionModelStore.DEFAULT_DIR_NAME))
    }
    // The YAMNet audio-classification model: the same on-demand contract, one
    // store per model — the coordinator's classifier and the settings row
    // share this state.
    val audioModelStore: AudioModelStore by lazy {
        AudioModelStore(java.io.File(filesDir, AudioModelStore.DEFAULT_DIR_NAME))
    }
    val detectionCoordinator: DetectionCoordinator by lazy {
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
    val detectionEventStore: DetectionEventStore by lazy {
        DetectionEventStore.get(this, retentionDays = { settingsDataStore.eventRetentionDays.value })
    }
    // One toggle over one transports adapter — the boot receiver and the
    // quick-settings tile start/stop through the same ladder the camera
    // screen and the Web API use, without re-typing the adapter.
    val streamToggle: StreamToggle by lazy {
        StreamToggle(StreamingTransports(streamingManager, streamingSession))
    }
    /** App-lifetime scope for work that must outlive a ViewModel or screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        connectivityMonitor.start()
        settingsApplier.start(appScope)
        wireFramePump()
        initializeStreamStateJournal()
        initializeTamperMonitor()
        initializeWidgetRefresh()
        initializeAutoUpdateCheck()
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
     * the two outputs share one collector.
     */
    private fun initializeWidgetRefresh() {
        appScope.launch {
            combine(
                streamingManager.isWebStreamingActive,
                streamingManager.isRtspRunning,
            ) { webActive, rtspActive -> webActive to rtspActive }
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
    }
}
