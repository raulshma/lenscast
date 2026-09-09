package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The webhook headers setting's decode: a JSON `{"Name": "value"}` map that
 * degrades to no headers on absence or garbage — a bad setting must never
 * break the dispatch.
 */
class WebhookNotifierParsingTest {

    @Test
    fun `null and blank decode to no headers`() {
        assertTrue(WebhookNotifier.parseHeaders(null).isEmpty())
        assertTrue(WebhookNotifier.parseHeaders("").isEmpty())
        assertTrue(WebhookNotifier.parseHeaders("   ").isEmpty())
    }

    @Test
    fun `a valid JSON map round trips`() {
        val headers = WebhookNotifier.parseHeaders("""{"Authorization":"Bearer t0k3n","X-Source":"hass"}""")
        assertEquals(mapOf("Authorization" to "Bearer t0k3n", "X-Source" to "hass"), headers)
    }

    @Test
    fun `malformed JSON degrades to no headers`() {
        assertTrue(WebhookNotifier.parseHeaders("not json").isEmpty())
        assertTrue(WebhookNotifier.parseHeaders("""["array","not","map"]""").isEmpty())
    }

    @Test
    fun `blank header names are dropped`() {
        val headers = WebhookNotifier.parseHeaders("""{"":"dropped","X-Ok":"kept"}""")
        assertEquals(mapOf("X-Ok" to "kept"), headers)
    }

    @Test
    fun `an empty JSON object decodes to no headers`() {
        assertTrue(WebhookNotifier.parseHeaders("{}").isEmpty())
    }

    // ── willDispatch: the would-this-POST gate the event log mirrors ──

    @Test
    fun `a disabled toggle or a non-http url never dispatches`() {
        assertFalse(WebhookNotifier.willDispatch(enabled = false, url = "https://ntfy.sh/t"))
        assertFalse(WebhookNotifier.willDispatch(enabled = true, url = " "))
        assertFalse(WebhookNotifier.willDispatch(enabled = true, url = null))
        assertFalse(WebhookNotifier.willDispatch(enabled = true, url = "ftp://example.com"))
        assertFalse(WebhookNotifier.willDispatch(enabled = true, url = "ntfy.sh/topic"))
    }

    @Test
    fun `an enabled toggle with an http or https url dispatches`() {
        assertTrue(WebhookNotifier.willDispatch(enabled = true, url = "https://ntfy.sh/topic"))
        assertTrue(WebhookNotifier.willDispatch(enabled = true, url = "http://127.0.0.1/hook"))
        // Surrounding whitespace is the dispatcher's own trim, not a blocker.
        assertTrue(WebhookNotifier.willDispatch(enabled = true, url = "  https://ntfy.sh/topic  "))
    }
}
