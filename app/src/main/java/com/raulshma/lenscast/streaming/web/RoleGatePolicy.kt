package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.streaming.SessionRole

/**
 * The pure read-only-viewer verdict behind the auth filter's role gate:
 * [decide] maps a session role + HTTP method + request path to ALLOW/DENY —
 * no sockets, no sessions, no clock, so the whole role × method × route
 * matrix is JVM-testable in one place. Enforcement lives in
 * [com.raulshma.lenscast.streaming.HttpAuthFilter] (it owns the cookie and
 * the answer codes); this object only owns the policy.
 *
 * The matrix, as implemented:
 *
 * - ADMIN (and the API token, and auth-off): ALLOW everything — unchanged.
 * - VIEWER reads (GET/HEAD/OPTIONS): ALLOW on every route EXCEPT the three
 *   admin-only reads — `/api/auth/config` (credential surface), `/api/auth/sessions`
 *   (the session registry), and `/api/audit` (the audit trail). The stream
 *   transports (`/stream`, `/audio`, `/snapshot`, the `/hls/` family), the
 *   media reads (the `/api/media/` family), and every other GET ride exactly
 *   today's authed-read behavior.
 * - VIEWER writes (everything else): DENY, with exactly two exceptions:
 *   `/api/auth/logout` (a viewer may end their own session) and
 *   `/api/audio/uplink` — talkback — plus the WHEP media-session verbs
 *   (`POST /whep`, `DELETE /whep[/{id}]`), which are media egress: they
 *   never change device state, they only let the caller *receive* the
 *   stream, exactly like the GET-shaped stream transports.
 *
 * Talkback decision: talkback is a viewer-ALLOWED feature — it is the
 * doorbell intercom use (a person at the door presses talk and speaks), not
 * a configuration change. The HTTP uplink is allowed above, and the WebSocket
 * twin `/ws/talkback` rides the ws sidecar, which stays cookie-gated only and
 * deliberately enforces no role at all (video/audio are reads; talkback is
 * the one write there and it is viewer-allowed by this same decision).
 *
 * `/api/auth/session` and `/api/auth/status` need no exception: they are
 * GETs outside the admin-only set, and the transport answers them before
 * this policy is consulted anyway (they are self checks, allowed for viewers).
 */
object RoleGatePolicy {

    /** The request may proceed. */
    enum class Verdict { ALLOW, DENY }

    /** The GET routes a viewer session may never take — admin-only reads. */
    val ADMIN_ONLY_GETS: Set<String> = setOf(
        "/api/auth/config",
        "/api/auth/sessions",
        "/api/audit",
    )

    /**
     * The non-read methods a viewer may still issue: their own logout, the
     * talkback uplink, and the WHEP media-session verbs (see
     * [isWhepMediaSession] — media egress is a read).
     */
    val VIEWER_ALLOWED_WRITES: Set<String> = setOf(
        "/api/auth/logout",
        "/api/audio/uplink",
    )

    /**
     * True when [method] on [path] is a WHEP media-session verb: POSTing an
     * SDP offer to `/whep` (which only ever *sends* media to the caller) and
     * DELETEing the session resource. Both are media egress — reads in the
     * same sense `/stream` is — so a viewer session may take them like any
     * stream transport, while every configuration write stays denied.
     */
    fun isWhepMediaSession(method: String, path: String): Boolean =
        method == "POST" && path == "/whep" ||
            method == "DELETE" && (path == "/whep" || path.startsWith("/whep/"))

    /** The verdict for [role] requesting [method] on [path]. */
    fun decide(role: SessionRole, method: String, path: String): Verdict = when (role) {
        SessionRole.ADMIN -> Verdict.ALLOW
        SessionRole.VIEWER -> when {
            method == "GET" || method == "HEAD" || method == "OPTIONS" ->
                if (path in ADMIN_ONLY_GETS) Verdict.DENY else Verdict.ALLOW
            path in VIEWER_ALLOWED_WRITES -> Verdict.ALLOW
            isWhepMediaSession(method, path) -> Verdict.ALLOW
            else -> Verdict.DENY
        }
    }
}
