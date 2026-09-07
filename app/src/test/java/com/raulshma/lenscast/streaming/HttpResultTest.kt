package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpResultTest {

    // ── jsonError shape ──

    @Test
    fun `jsonError pins the flat error-object wire shape`() {
        val result = HttpResult.jsonError(404, "Media not found")
        assertEquals(404, result.statusCode)
        assertEquals("application/json", result.mimeType)
        assertEquals("""{"error":"Media not found"}""", (result.body as HttpResult.ResponseBody.Text).text)
    }

    @Test
    fun `jsonError escapes quotes and backslashes`() {
        val result = HttpResult.jsonError(500, """say "hi"\ now""")
        assertEquals(
            """{"error":"say \"hi\"\\ now"}""",
            (result.body as HttpResult.ResponseBody.Text).text,
        )
    }

    @Test
    fun `jsonError escapes control characters with short escapes`() {
        // Real control characters in, their short JSON escapes out.
        val result = HttpResult.jsonError(500, "line1\nline2\r\tend")
        assertEquals(
            "{\"error\":\"line1\\nline2\\r\\tend\"}",
            (result.body as HttpResult.ResponseBody.Text).text,
        )
    }

    @Test
    fun `jsonError hex-escapes control characters without a short escape`() {
        // \u0001 has no short escape — it must appear as the six characters \u0001.
        val result = HttpResult.jsonError(500, "ab")
        assertEquals(
            "{\"error\":\"a\\u0001b\"}",
            (result.body as HttpResult.ResponseBody.Text).text,
        )
    }

    @Test
    fun `plain text passes through the escaper unchanged`() {
        assertEquals(
            """{"error":"nothing to escape"}""",
            (HttpResult.jsonError(400, "nothing to escape").body as HttpResult.ResponseBody.Text).text,
        )
    }

    // ── the one no-store triple ──

    @Test
    fun `no-store headers are the full triple in order`() {
        assertEquals(
            linkedMapOf(
                "Cache-Control" to "no-store, no-cache, must-revalidate, max-age=0",
                "Pragma" to "no-cache",
                "Expires" to "0",
            ),
            HttpResult.NO_STORE_HEADERS,
        )
    }
}
