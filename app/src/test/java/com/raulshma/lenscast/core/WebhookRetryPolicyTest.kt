package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The webhook dispatch ladder: the success verdict and the linear backoff
 * (2 s, 4 s — three attempts at most).
 */
class WebhookRetryPolicyTest {

    // ── Success verdict ──

    @Test
    fun `2xx is successful, everything else is retryable`() {
        assertTrue(WebhookRetryPolicy.isSuccessful(200))
        assertTrue(WebhookRetryPolicy.isSuccessful(204))
        assertTrue(WebhookRetryPolicy.isSuccessful(299))
        assertFalse(WebhookRetryPolicy.isSuccessful(301))
        assertFalse(WebhookRetryPolicy.isSuccessful(500))
        assertFalse(WebhookRetryPolicy.isSuccessful(-1))
    }

    // ── Attempt budget ──

    @Test
    fun `at most three attempts per dispatch`() {
        assertTrue(WebhookRetryPolicy.shouldRetry(0))
        assertTrue(WebhookRetryPolicy.shouldRetry(1))
        assertTrue(WebhookRetryPolicy.shouldRetry(2))
        assertFalse(WebhookRetryPolicy.shouldRetry(3))
        assertFalse(WebhookRetryPolicy.shouldRetry(4))
        assertEquals(3, WebhookRetryPolicy.MAX_ATTEMPTS)
    }

    // ── Linear backoff ──

    @Test
    fun `retry delays are the 2s then 4s ladder`() {
        assertEquals(2_000L, WebhookRetryPolicy.retryDelayMs(1))
        assertEquals(4_000L, WebhookRetryPolicy.retryDelayMs(2))
    }

    @Test
    fun `out-of-range retry numbers clamp into the ladder`() {
        assertEquals(2_000L, WebhookRetryPolicy.retryDelayMs(0))
        assertEquals(2_000L, WebhookRetryPolicy.retryDelayMs(-3))
        assertEquals(4_000L, WebhookRetryPolicy.retryDelayMs(9))
    }
}
