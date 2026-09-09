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
        // Capture: the photo route exactly as the router registers it
        "/api/capture",
        // Recording lifecycle
        "/api/recording/start",
        "/api/recording/stop",
        // Deterrence: the siren route plus the torch route the router registers
        "/api/deterrence/siren",
        "/api/camera/torch",
    )

    /** True when a POST carrying a valid API token may proceed to [path]. */
    fun allowsPost(path: String): Boolean = path in TOKEN_WRITABLE_POST_ROUTES
}
