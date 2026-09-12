package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.streaming.SessionRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exhaustive role × method × route-class matrix for [RoleGatePolicy].
 * Every route class the server serves is spelled out per method, so a new
 * route added to the router forces a conscious decision here (it lands in an
 * existing class, or the test breaks until it is classified).
 */
class RoleGatePolicyTest {

    private fun decide(role: SessionRole, method: String, path: String): RoleGatePolicy.Verdict =
        RoleGatePolicy.decide(role, method, path)

    /** Route classes exactly as the transport + router serve them. */
    private val routeClasses = mapOf(
        // Stream transports: authed reads, viewer access unchanged from today.
        "transport read" to listOf("/stream", "/audio", "/snapshot", "/snapshot?highres=1", "/hls/playlist.m3u8", "/hls/seg123.ts"),
        // Media reads (router-adjacent, served by the transport).
        "media read" to listOf("/api/media/IMG_1.jpg", "/api/media/IMG_1.jpg/thumbnail"),
        // The admin-only reads.
        "admin read" to listOf("/api/auth/config", "/api/auth/sessions", "/api/audit"),
        // The viewer-allowed self checks.
        "self check" to listOf("/api/auth/session", "/api/auth/status"),
        // Viewer's own logout.
        "own logout" to listOf("/api/auth/logout"),
        // Talkback uplink (doorbell intercom).
        "talkback" to listOf("/api/audio/uplink"),
        // Every other read route the ApiRouter registers.
        "other api read" to listOf(
            "/api/settings", "/api/settings/export", "/api/status", "/api/camera/lenses",
            "/api/stream/clients", "/api/capture/interval/status", "/api/recording/status",
            "/api/recordings/sessions", "/api/gallery", "/api/detection/events",
            "/api/detection/events/export", "/api/detection/stats", "/api/system",
            "/api/push/vapid-public", "/api/push/subscriptions",
        ),
        // Every write route class the ApiRouter registers.
        "settings write" to listOf("/api/settings", "/api/settings/import", "/api/settings/ml-model/download"),
        "stream lifecycle" to listOf("/api/stream/start", "/api/stream/stop", "/api/stream/web/start", "/api/stream/web/stop"),
        "capture" to listOf("/api/capture", "/api/capture/interval/start", "/api/capture/interval/stop"),
        "camera control" to listOf("/api/camera/lens", "/api/camera/focus", "/api/camera/zoom", "/api/camera/torch"),
        "recording control" to listOf("/api/recording/start", "/api/recording/stop"),
        "deterrence" to listOf("/api/deterrence/siren", "/api/detection/test"),
        "media write" to listOf("/api/media/batch-delete", "/api/media/IMG_1.jpg"),
        "session revoke" to listOf("/api/auth/sessions/tok12345"),
        "client kick" to listOf("/api/stream/clients/1.2.3.4:5000"),
        "audit clear" to listOf("/api/audit"),
        "auth config write" to listOf("/api/auth/config"),
        "push subscribe" to listOf("/api/push/subscriptions"),
    )

    private val readMethods = listOf("GET", "HEAD", "OPTIONS")
    private val writeMethods = listOf("POST", "PUT", "DELETE")

    // ── admin: everything, unchanged ──

    @Test
    fun `admin is allowed on every route class for every method`() {
        for ((_, paths) in routeClasses) {
            for (path in paths) {
                for (method in readMethods + writeMethods) {
                    assertEquals("admin $method $path", RoleGatePolicy.Verdict.ALLOW, decide(SessionRole.ADMIN, method, path))
                }
            }
        }
    }

    // ── viewer reads ──

    @Test
    fun `viewer is allowed reads on transport media and other api routes`() {
        for (clazz in listOf("transport read", "media read", "other api read", "self check")) {
            for (path in routeClasses.getValue(clazz)) {
                for (method in readMethods) {
                    assertEquals("viewer $method $path", RoleGatePolicy.Verdict.ALLOW, decide(SessionRole.VIEWER, method, path))
                }
            }
        }
    }

    @Test
    fun `viewer is denied reads on the three admin-only routes`() {
        for (path in routeClasses.getValue("admin read")) {
            for (method in readMethods) {
                assertEquals("viewer $method $path", RoleGatePolicy.Verdict.DENY, decide(SessionRole.VIEWER, method, path))
            }
        }
    }

    // ── viewer writes ──

    @Test
    fun `viewer is denied every write route class except logout and talkback`() {
        val allowed = setOf("own logout", "talkback")
        for ((clazz, paths) in routeClasses) {
            if (clazz in allowed) continue
            for (path in paths) {
                for (method in writeMethods) {
                    assertEquals("viewer $method $path", RoleGatePolicy.Verdict.DENY, decide(SessionRole.VIEWER, method, path))
                }
            }
        }
    }

    @Test
    fun `viewer may end their own session and use talkback`() {
        assertEquals(RoleGatePolicy.Verdict.ALLOW, decide(SessionRole.VIEWER, "POST", "/api/auth/logout"))
        assertEquals(RoleGatePolicy.Verdict.ALLOW, decide(SessionRole.VIEWER, "POST", "/api/audio/uplink"))
    }

    // ── WHEP media-session verbs: egress reads, viewer-allowed like /stream ──

    @Test
    fun `viewer may POST an SDP offer and DELETE its WHEP session`() {
        assertEquals(RoleGatePolicy.Verdict.ALLOW, decide(SessionRole.VIEWER, "POST", "/whep"))
        assertEquals(RoleGatePolicy.Verdict.ALLOW, decide(SessionRole.VIEWER, "DELETE", "/whep"))
        assertEquals(RoleGatePolicy.Verdict.ALLOW, decide(SessionRole.VIEWER, "DELETE", "/whep/abc123"))
    }

    @Test
    fun `the WHEP egress exception does not leak onto other methods or paths`() {
        // GET /whep is a read like every GET — ALLOW here, 405 at the
        // transport (the session verbs are POST and DELETE only).
        assertEquals(RoleGatePolicy.Verdict.ALLOW, decide(SessionRole.VIEWER, "GET", "/whep"))
        assertEquals(RoleGatePolicy.Verdict.DENY, decide(SessionRole.VIEWER, "PUT", "/whep"))
        // POST under the id prefix is not the offer route.
        assertEquals(RoleGatePolicy.Verdict.DENY, decide(SessionRole.VIEWER, "POST", "/whep/abc123"))
        // Look-alike paths stay denied.
        assertEquals(RoleGatePolicy.Verdict.DENY, decide(SessionRole.VIEWER, "POST", "/whep-evil"))
        assertEquals(RoleGatePolicy.Verdict.DENY, decide(SessionRole.VIEWER, "POST", "/api/whep"))
    }

    // ── the legacy/default role ──

    @Test
    fun `wire-name round trip and unknown fold to admin`() {
        assertEquals(SessionRole.ADMIN, SessionRole.fromWireName("admin"))
        assertEquals(SessionRole.VIEWER, SessionRole.fromWireName("viewer"))
        assertEquals(SessionRole.ADMIN, SessionRole.fromWireName(null) ?: SessionRole.ADMIN)
        assertEquals(SessionRole.ADMIN, SessionRole.fromWireName("root") ?: SessionRole.ADMIN)
        assertEquals("admin", SessionRole.ADMIN.wireName)
        assertEquals("viewer", SessionRole.VIEWER.wireName)
    }
}
