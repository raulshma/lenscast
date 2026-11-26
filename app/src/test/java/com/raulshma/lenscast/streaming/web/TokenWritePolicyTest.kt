package com.raulshma.lenscast.streaming.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API token's write allow-list: exactly the POST routes a valid token may
 * hit, nothing else, and never an auth/session route.
 */
class TokenWritePolicyTest {

    @Test
    fun `every stream lifecycle route is writable`() {
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/start"))
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/stop"))
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/web/start"))
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/web/stop"))
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/rtsp/start"))
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/rtsp/stop"))
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/rtmp/start"))
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/rtmp/stop"))
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/whip/start"))
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/whip/stop"))
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/srt/start"))
        assertTrue(TokenWritePolicy.allowsPost("/api/stream/srt/stop"))
    }

    @Test
    fun `the capture photo route is writable exactly as the router registers it`() {
        assertTrue(TokenWritePolicy.allowsPost("/api/capture"))
        // The invented spelling is not a registered route and must not pass.
        assertFalse(TokenWritePolicy.allowsPost("/api/capture/photo"))
    }

    @Test
    fun `recording routes are writable`() {
        assertTrue(TokenWritePolicy.allowsPost("/api/recording/start"))
        assertTrue(TokenWritePolicy.allowsPost("/api/recording/stop"))
        // The schedule-only cancel rides the same token ladder (a token
        // pairing's capture screen cancels through it). This file pins the
        // token allow-list only — the paired-session role matrix (a viewer
        // credential denied before the handler cancels) is RoleGatePolicy's
        // verdict, covered there.
        assertTrue(TokenWritePolicy.allowsPost("/api/recording/cancel-schedule"))
    }

    @Test
    fun `the deterrence siren and torch routes are writable`() {
        assertTrue(TokenWritePolicy.allowsPost("/api/deterrence/siren"))
        assertTrue(TokenWritePolicy.allowsPost("/api/camera/torch"))
    }

    @Test
    fun `the live lens controls and interval-capture loop are writable`() {
        // The companion's Live tab drives these on a token pairing — a route
        // the UI exposes must be token-writable or the control 401s (found on
        // the loopback E2E: the zoom slider moved, the request was denied,
        // "Sign-in required" snackbar, while torch and siren on the same tab
        // passed). All ephemeral camera state — nothing persisted.
        assertTrue(TokenWritePolicy.allowsPost("/api/camera/zoom"))
        assertTrue(TokenWritePolicy.allowsPost("/api/camera/focus"))
        assertTrue(TokenWritePolicy.allowsPost("/api/camera/lens"))
        assertTrue(TokenWritePolicy.allowsPost("/api/capture/interval/start"))
        assertTrue(TokenWritePolicy.allowsPost("/api/capture/interval/stop"))
    }

    @Test
    fun `the detection model download and test-alert routes are writable`() {
        assertTrue(TokenWritePolicy.allowsPost("/api/settings/ml-model/download"))
        // The YAMNet twin: same device-maintenance class, nothing persisted.
        assertTrue(TokenWritePolicy.allowsPost("/api/settings/audio-model/download"))
        // The test alert dispatches the alert sinks once; it persists nothing.
        assertTrue(TokenWritePolicy.allowsPost("/api/detection/test"))
    }

    @Test
    fun `the UnifiedPush receiver pairing routes are writable`() {
        // A headless distributor has no session cookie; pairing is exactly
        // the automation class the API token exists for.
        assertTrue(TokenWritePolicy.allowsPost("/api/push/unifiedpush"))
        assertTrue(TokenWritePolicy.allowsPost("/api/push/unifiedpush/remove"))
        // The browser routes stay session-only.
        assertFalse(TokenWritePolicy.allowsPost("/api/push/subscriptions"))
        assertFalse(TokenWritePolicy.allowsPost("/api/push/vapid-public"))
    }

    @Test
    fun `settings auth and persisted ui layout routes are never token-writable`() {
        assertFalse(TokenWritePolicy.allowsPost("/api/settings"))
        assertFalse(TokenWritePolicy.allowsPost("/api/ui/layout"))
        assertFalse(TokenWritePolicy.allowsPost("/api/auth/login"))
        assertFalse(TokenWritePolicy.allowsPost("/api/auth/logout"))
        assertFalse(TokenWritePolicy.allowsPost("/api/auth/config"))
        assertFalse(TokenWritePolicy.allowsPost("/api/auth/session"))
        assertFalse(TokenWritePolicy.allowsPost("/api/auth/sessions"))
    }

    @Test
    fun `routes outside the allow-list stay read-only for tokens`() {
        // Destructive and session-class routes stay off the token ladder.
        assertFalse(TokenWritePolicy.allowsPost("/api/media/batch-delete"))
        assertFalse(TokenWritePolicy.allowsPost("/api/audio/uplink"))
        assertFalse(TokenWritePolicy.allowsPost("/api/detection/events"))
    }

    @Test
    fun `near-miss paths do not pass`() {
        assertFalse(TokenWritePolicy.allowsPost("/api/stream/start/extra"))
        assertFalse(TokenWritePolicy.allowsPost("/api/stream/startx"))
        assertFalse(TokenWritePolicy.allowsPost("/api/stream"))
        assertFalse(TokenWritePolicy.allowsPost(""))
    }

    @Test
    fun `the allow-list is exactly the documented set`() {
        assertEquals(
            setOf(
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
                "/api/capture",
                "/api/recording/start",
                "/api/recording/stop",
                "/api/recording/cancel-schedule",
                "/api/deterrence/siren",
                "/api/camera/torch",
                "/api/camera/zoom",
                "/api/camera/focus",
                "/api/camera/lens",
                "/api/capture/interval/start",
                "/api/capture/interval/stop",
                "/api/settings/ml-model/download",
                "/api/settings/audio-model/download",
                "/api/detection/test",
                "/api/system/restart",
                "/api/system/reset-camera",
                "/api/automation/test",
                "/api/automation/test/restore",
                "/api/push/unifiedpush",
                "/api/push/unifiedpush/remove",
            ),
            TokenWritePolicy.TOKEN_WRITABLE_POST_ROUTES,
        )
    }
}
