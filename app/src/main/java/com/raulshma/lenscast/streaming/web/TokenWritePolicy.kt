package com.raulshma.lenscast.streaming.web

/**
 * The exact POST routes a valid API token may write — the pure allow-list
 * behind the Web Auth Gate's token verdict. Everything not listed stays
 * read-only for tokens (a presented token on any other POST is denied), and
 * the list deliberately contains no `/api/auth/` route: a bearer token never
 * mints sessions, rotates credentials, or logs out. Every listed path is a
 * route the [ApiRouter] registers, and the list is a deliberate subset of
 * those — a route that is not registered server-side has no business being
 * token-writable.
 */
object TokenWritePolicy {

    /** The POST routes accepted with a valid API token. */
    val TOKEN_WRITABLE_POST_ROUTES: Set<String> = setOf(
        // Stream lifecycle (resume is the router's alias for start)
        "/api/stream/start",
        "/api/stream/resume",
        "/api/stream/stop",
        "/api/stream/web/start",
        "/api/stream/web/stop",
        "/api/stream/rtsp/start",
        "/api/stream/rtsp/stop",
        "/api/stream/rtmp/start",
        "/api/stream/rtmp/stop",
        "/api/stream/whip/start",
        "/api/stream/whip/stop",
        "/api/stream/srt/start",
        "/api/stream/srt/stop",
        // Capture: the photo route exactly as the router registers it
        "/api/capture",
        // Recording lifecycle
        "/api/recording/start",
        "/api/recording/stop",
        // Deterrence: the siren route plus the torch route the router registers
        "/api/deterrence/siren",
        "/api/camera/torch",
        // Live lens controls: the zoom/focus/lens triple the companion's Live
        // tab drives — same class as torch (ephemeral camera state, nothing
        // persisted, nothing disabled). Zoom's absence was found on the
        // loopback E2E: the slider moved, the request 401'd, and the snackbar
        // read "Sign-in required" while torch and siren on the same tab passed.
        "/api/camera/zoom",
        "/api/camera/focus",
        "/api/camera/lens",
        // The interval-capture loop's start/stop: recording-class control
        // (it creates media on a schedule; it persists no setting).
        "/api/capture/interval/start",
        "/api/capture/interval/stop",
        // Detection model fetch: a device-maintenance action in the siren/torch
        // class — it changes no persisted setting and cannot disable anything.
        "/api/settings/ml-model/download",
        // Audio model fetch: the YAMNet twin of the detection model route —
        // same device-maintenance class, same nothing-persisted contract.
        "/api/settings/audio-model/download",
        // Test alert: fires the alert sinks once (webhook/MQTT/notification);
        // it persists nothing, records no event, and cannot arm deterrence.
        "/api/detection/test",
        // App restart: a device-maintenance action in the same class — the
        // token holder is the automation system itself, and restarting the app
        // is what its own keeper does on wedged state. It persists nothing
        // (the quit ladder is the one the overflow menu runs) and the relaunch
        // re-arms every persisted stream by policy.
        "/api/system/restart",
        // Automation test arm/restore: the token holder IS the automation
        // system — firing a rule once (and restoring its effects) is exactly
        // what the scheduler does without any credential, minus the journal.
        "/api/automation/test",
        "/api/automation/test/restore",
        // UnifiedPush receiver pairing: a headless distributor has no browser
        // session. This is the one persisted-state exception: enrolling a
        // receiver routes future detection alerts to it, while the routes stay
        // kind-scoped and cannot inspect or alter browser subscriptions.
        "/api/push/unifiedpush",
        "/api/push/unifiedpush/remove",
    )

    // Deliberately absent: the browser Web Push routes. A browser
    // subscription is the subscribing browser's own service-worker state and
    // is only useful to that browser, so it rides the session-cookie + CSRF
    // ladder and is never token-writable or token-readable (the gate denies
    // token GET/HEAD on /api/push/subscriptions too). The UnifiedPush routes
    // above are the automation exception, and both are receiver-scoped.

    /** True when a POST carrying a valid API token may proceed to [path]. */
    fun allowsPost(path: String): Boolean = path in TOKEN_WRITABLE_POST_ROUTES
}
