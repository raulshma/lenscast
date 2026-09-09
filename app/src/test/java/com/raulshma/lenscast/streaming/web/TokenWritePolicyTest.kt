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
    }

    @Test
    fun `the deterrence siren and torch routes are writable`() {
        assertTrue(TokenWritePolicy.allowsPost("/api/deterrence/siren"))
        assertTrue(TokenWritePolicy.allowsPost("/api/camera/torch"))
    }

    @Test
    fun `the detection model download route is writable`() {
        assertTrue(TokenWritePolicy.allowsPost("/api/settings/ml-model/download"))
    }

    @Test
    fun `settings and auth routes are never token-writable`() {
        assertFalse(TokenWritePolicy.allowsPost("/api/settings"))
        assertFalse(TokenWritePolicy.allowsPost("/api/auth/login"))
        assertFalse(TokenWritePolicy.allowsPost("/api/auth/logout"))
        assertFalse(TokenWritePolicy.allowsPost("/api/auth/config"))
        assertFalse(TokenWritePolicy.allowsPost("/api/auth/session"))
        assertFalse(TokenWritePolicy.allowsPost("/api/auth/sessions"))
    }

    @Test
    fun `routes outside the allow-list stay read-only for tokens`() {
        assertFalse(TokenWritePolicy.allowsPost("/api/camera/lens"))
        assertFalse(TokenWritePolicy.allowsPost("/api/camera/focus"))
        assertFalse(TokenWritePolicy.allowsPost("/api/camera/zoom"))
        assertFalse(TokenWritePolicy.allowsPost("/api/capture/interval/start"))
        assertFalse(TokenWritePolicy.allowsPost("/api/capture/interval/stop"))
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
                "/api/capture",
                "/api/recording/start",
                "/api/recording/stop",
                "/api/deterrence/siren",
                "/api/camera/torch",
                "/api/settings/ml-model/download",
            ),
            TokenWritePolicy.TOKEN_WRITABLE_POST_ROUTES,
        )
    }
}
