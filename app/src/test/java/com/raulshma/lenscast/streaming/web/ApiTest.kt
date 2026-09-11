package com.raulshma.lenscast.streaming.web

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * The transport-agnostic envelope every handler and the router speak: ok's
 * 200-JSON contract, the 404 verdict's fixed shape, and the error payload's
 * bounded, single-line rendering of a handler failure — the web client's
 * contract decodes these, so their exact spelling is load-bearing. The
 * envelope only: the dispatch decisions live in [ApiRouterTest].
 */
class ApiTest {

    // ── ok and notFound ──

    @Test
    fun `ok answers 200 json with the body verbatim`() {
        val response = ApiResponse.ok("""{"a":1}""")

        assertEquals(200, response.httpStatus)
        assertEquals("application/json", response.contentType)
        assertEquals("""{"a":1}""", response.body)
    }

    @Test
    fun `not found answers 404 json with the fixed body`() {
        val response = ApiResponse.notFound()

        assertEquals(404, response.httpStatus)
        assertEquals("application/json", response.contentType)
        assertEquals("""{"error":"Not found"}""", response.body)
    }

    // ── the error payload ──

    @Test
    fun `an exception message renders as the failed payload`() {
        assertEquals(
            """{"success":false,"error":"boom"}""",
            ApiResponse.error(IOException("boom")),
        )
    }

    @Test
    fun `a message-less exception falls back to the internal error text`() {
        assertEquals(
            """{"success":false,"error":"Internal error"}""",
            ApiResponse.error(IOException()),
        )
    }

    @Test
    fun `newlines are flattened so one error stays one json line`() {
        assertEquals(
            """{"success":false,"error":"a b"}""",
            ApiResponse.error(IOException("a\nb")),
        )
    }

    @Test
    fun `exactly two hundred characters survive untouched`() {
        // The boundary: 200 is the cap, not cap-minus-one — nothing is cut.
        val message = "x".repeat(200)

        assertEquals(
            """{"success":false,"error":"$message"}""",
            ApiResponse.error(IOException(message)),
        )
    }

    @Test
    fun `characters past two hundred are cut`() {
        assertEquals(
            """{"success":false,"error":"${"x".repeat(200)}"}""",
            ApiResponse.error(IOException("x".repeat(201))),
        )
    }
}
