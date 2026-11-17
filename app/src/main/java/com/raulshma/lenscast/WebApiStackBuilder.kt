package com.raulshma.lenscast

import com.raulshma.lenscast.streaming.StreamingManager
import com.raulshma.lenscast.streaming.WebApiStack
import com.raulshma.lenscast.streaming.web.ApiRouter
import com.raulshma.lenscast.streaming.web.AuditLog
import com.raulshma.lenscast.streaming.web.AuditWebHandler
import com.raulshma.lenscast.streaming.web.AuthWebHandler
import com.raulshma.lenscast.streaming.web.AutomationTestWebHandler
import com.raulshma.lenscast.streaming.web.AutomationWebHandler
import com.raulshma.lenscast.streaming.web.CaptureWebHandler
import com.raulshma.lenscast.streaming.web.DetectionEventsWebHandler
import com.raulshma.lenscast.streaming.web.DetectionTestWebHandler
import com.raulshma.lenscast.streaming.web.DeterrenceWebHandler
import com.raulshma.lenscast.streaming.web.GalleryWebHandler
import com.raulshma.lenscast.streaming.web.PushWebHandler
import com.raulshma.lenscast.streaming.web.IntervalCaptureWebHandler
import com.raulshma.lenscast.streaming.web.LensWebHandler
import com.raulshma.lenscast.streaming.web.RecordingWebHandler
import com.raulshma.lenscast.streaming.web.SettingsWebHandler
import com.raulshma.lenscast.streaming.web.StatusWebHandler
import com.raulshma.lenscast.streaming.web.StreamWebHandler
import com.raulshma.lenscast.streaming.web.SystemWebHandler
import com.raulshma.lenscast.streaming.web.UnifiedPushWebHandler

/**
 * Composition root for the Web API: one handler module per domain, each
 * receiving only the services it needs. Lives in the app tier beside the
 * graph's host, not inside [com.raulshma.lenscast.streaming.StreamingManager]:
 * the stack pulls ~15 app-tier collaborators off [AppGraph], and building it
 * inside the manager made the streaming class a second composition root,
 * self-referential through the status handler. The manager keeps the lazy
 * `webApiStack` property and receives this builder as a constructor
 * `webApiStackFactory`, passing itself in — the stack's first request lands
 * mid-construction (the transport's eager initial server build), so the
 * manager self-reference must arrive as a value, the `this` capture the
 * builder always relied on; resolving it through the graph's
 * [AppGraph.streamingManager] lazy here would re-enter that lazy while its
 * initializer is still on the stack and recurse until the heap dies.
 */
internal fun buildWebApiStack(
    manager: StreamingManager,
    app: AppGraph,
): WebApiStack {
    val context = manager.appContext()
    val gallery = GalleryWebHandler(context, app.captureHistoryStore)
    val deterrence = DeterrenceWebHandler(manager.sirenPlayer)
    val detectionEvents = DetectionEventsWebHandler(
        app.detectionEventStore,
    )
    val authHandler = AuthWebHandler(app.settingsDataStore, manager.webAuthGate)
    val auditLog = AuditLog(
        file = java.io.File(context.filesDir, AUDIT_LOG_FILE),
    )
    val auditHandler = AuditWebHandler(auditLog)
    val detectionTest = DetectionTestWebHandler(
        coordinator = { app.detectionCoordinator },
    )
    val statusHandler = StatusWebHandler(
        streamingManager = manager,
        thermalMonitor = app.thermalMonitor,
        powerManager = app.powerManager,
        cameraService = app.cameraService,
        streamWatchdog = app.streamWatchdog,
        settingsDataStore = app.settingsDataStore,
    )
    val systemHandler = SystemWebHandler(
        context = context,
        powerManager = app.powerManager,
        captureHistoryStore = app.captureHistoryStore,
        restartApplication = { app.restartApplication() },
        torchStrengthMaxLevel = { app.cameraService.torchStrengthMaxLevel.value },
        // Wave 4 (blueprint P1 items 9/10 + the deferred item-7 telemetry):
        // the soft ladder LAUNCHER is the graph's ONE instance (the
        // heartbeat's SOFT_RESTART tier runs the same object — the handler
        // only launches it, past the response flush, because the ladder's
        // server stop tears down the request's own socket), the reset lever
        // is the camera service's bounded budget, and the telemetry reads
        // live.
        launchSoftRestart = { app.launchSoftRestart(); true },
        resetCameraLever = { app.cameraService.resetCamera() },
        crashRelaunchTelemetry = {
            val state = app.crashMarkerJournal.load()
            com.raulshma.lenscast.streaming.model.CrashRelaunchTelemetryDto(
                crashCount = state.consecutiveCrashes,
                state = com.raulshma.lenscast.streaming.web.SystemWebHandler.crashRelaunchStateName(
                    state.consecutiveCrashes,
                ),
            )
        },
        heartbeatTelemetry = {
            val state = app.heartbeatController.state.value
            com.raulshma.lenscast.streaming.model.HeartbeatTelemetryDto(
                armed = state.armed,
                misses = state.consecutiveMisses,
                tier = state.lastTier?.name,
            )
        },
    )
    val pushHandler = PushWebHandler(
        subscriptions = app.pushSubscriptionStore,
        vapidKeys = app.vapidKeys,
    )
    val unifiedPushHandler = UnifiedPushWebHandler(
        subscriptions = app.pushSubscriptionStore,
        vapidKeys = app.vapidKeys,
    )
    val automationHandler = AutomationWebHandler(
        app.settingsDataStore,
        app.automationFireJournal,
        testController = app.automationTestController,
    )
    val automationTest = AutomationTestWebHandler(controller = { app.automationTestController })
    return WebApiStack(
        router = ApiRouter(
            settings = SettingsWebHandler(
                settingsDataStore = app.settingsDataStore,
                detectionModelStore = app.detectionModelStore,
                audioModelStore = app.audioModelStore,
            ),
            automation = automationHandler,
            automationTest = automationTest,
            status = statusHandler,
            stream = StreamWebHandler(manager, app.streamingSession),
            capture = CaptureWebHandler(app.photoCaptureManager),
            lens = LensWebHandler(app.cameraService, app.settingsDataStore),
            interval = IntervalCaptureWebHandler(context),
            recording = RecordingWebHandler(app.recordingController),
            recordingSessions = com.raulshma.lenscast.streaming.web.RecordingSessionsWebHandler(
                captureHistoryStore = app.captureHistoryStore,
                eventStore = app.detectionEventStore,
            ),
            gallery = gallery,
            deterrence = deterrence,
            detectionEvents = detectionEvents,
            auth = authHandler,
            audit = auditHandler,
            detectionTest = detectionTest,
            auditLog = auditLog,
            system = systemHandler,
            push = pushHandler,
            unifiedPush = unifiedPushHandler,
        ),
        gallery = gallery,
        status = statusHandler,
        capture = app.photoCaptureManager,
        deterrence = deterrence,
        auth = authHandler,
        detectionEvents = detectionEvents,
        audit = auditHandler,
        auditLog = auditLog,
        detectionTest = detectionTest,
        system = systemHandler,
        push = pushHandler,
        unifiedPush = unifiedPushHandler,
        automation = automationHandler,
        automationTest = automationTest,
    )
}

/** The Web API audit trail, inside app-private files. */
private const val AUDIT_LOG_FILE = "audit_log.json"
