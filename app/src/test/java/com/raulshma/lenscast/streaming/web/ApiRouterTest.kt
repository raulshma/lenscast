package com.raulshma.lenscast.streaming.web

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Web API's routing table: method+path to handler dispatch, the 404
 * verdict (including method mismatches, which have no 405 branch — a write
 * verb on a read path is simply not found), and the audit half of a mutating
 * dispatch — every POST/PUT/DELETE lands in the trail as `"$method $path"`,
 * ok or error, and a broken audit write never fails the audited request.
 * Handlers are mocked: the router is tested as a decision, not plumbing.
 */
class ApiRouterTest {

    private val settings = mockk<SettingsWebHandler>(relaxed = true)
    private val status = mockk<StatusWebHandler>(relaxed = true)
    private val stream = mockk<StreamWebHandler>(relaxed = true)
    private val capture = mockk<CaptureWebHandler>(relaxed = true)
    private val lens = mockk<LensWebHandler>(relaxed = true)
    private val interval = mockk<IntervalCaptureWebHandler>(relaxed = true)
    private val recording = mockk<RecordingWebHandler>(relaxed = true)
    private val recordingSessions = mockk<RecordingSessionsWebHandler>(relaxed = true)
    private val gallery = mockk<GalleryWebHandler>(relaxed = true)
    private val deterrence = mockk<DeterrenceWebHandler>(relaxed = true)
    private val detectionEvents = mockk<DetectionEventsWebHandler>(relaxed = true)
    private val auth = mockk<AuthWebHandler>(relaxed = true)
    private val audit = mockk<AuditWebHandler>(relaxed = true)
    private val detectionTest = mockk<DetectionTestWebHandler>(relaxed = true)
    private val system = mockk<SystemWebHandler>(relaxed = true)
    private val auditLog = mockk<AuditLog>(relaxed = true)

    private fun router() = ApiRouter(
        settings = settings,
        status = status,
        stream = stream,
        capture = capture,
        lens = lens,
        interval = interval,
        recording = recording,
        recordingSessions = recordingSessions,
        gallery = gallery,
        deterrence = deterrence,
        detectionEvents = detectionEvents,
        auth = auth,
        audit = audit,
        detectionTest = detectionTest,
        system = system,
        auditLog = auditLog,
    )

    private fun dispatch(request: ApiRequest): ApiResponse =
        runBlocking { router().dispatch(request) }

    // ── GET routing ──

    @Test
    fun `settings status and system reads dispatch to their handlers`() {
        coEvery { settings.get() } returns """{"settings":true}"""
        coEvery { settings.export() } returns """{"export":1}"""
        coEvery { status.get() } returns """{"status":true}"""
        coEvery { system.get() } returns """{"system":true}"""

        assertEquals("""{"settings":true}""", dispatch(ApiRequest(ApiMethod.GET, "/api/settings")).body)
        assertEquals("""{"export":1}""", dispatch(ApiRequest(ApiMethod.GET, "/api/settings/export")).body)
        assertEquals("""{"status":true}""", dispatch(ApiRequest(ApiMethod.GET, "/api/status")).body)
        assertEquals("""{"system":true}""", dispatch(ApiRequest(ApiMethod.GET, "/api/system")).body)
    }

    @Test
    fun `query parameters reach the gallery and session reads`() {
        coEvery { gallery.getGallery("photo", 2, 10) } returns """{"page":2}"""
        coEvery { recordingSessions.sessions("2026-09-11") } returns """{"day":true}"""

        assertEquals(
            """{"page":2}""",
            dispatch(
                ApiRequest(ApiMethod.GET, "/api/gallery", query = mapOf("type" to "photo", "page" to "2", "pageSize" to "10")),
            ).body,
        )
        assertEquals(
            """{"day":true}""",
            dispatch(ApiRequest(ApiMethod.GET, "/api/recordings/sessions", query = mapOf("day" to "2026-09-11"))).body,
        )
    }

    @Test
    fun `gallery pagination falls back to zero for missing or malformed numbers`() {
        coEvery { gallery.getGallery(null, 0, 0) } returns """{"page":0}"""

        assertEquals("""{"page":0}""", dispatch(ApiRequest(ApiMethod.GET, "/api/gallery")).body)
        assertEquals(
            """{"page":0}""",
            dispatch(ApiRequest(ApiMethod.GET, "/api/gallery", query = mapOf("page" to "abc", "pageSize" to "-"))).body,
        )
    }

    @Test
    fun `detection event reads pass limit and type with null fallbacks`() {
        coEvery { detectionEvents.list(5, "person") } returns """{"limit":5}"""
        coEvery { detectionEvents.list(null, null) } returns """{"all":true}"""
        coEvery { detectionEvents.stats() } returns """{"stats":true}"""

        assertEquals(
            """{"limit":5}""",
            dispatch(ApiRequest(ApiMethod.GET, "/api/detection/events", query = mapOf("limit" to "5", "type" to "person"))).body,
        )
        assertEquals("""{"all":true}""", dispatch(ApiRequest(ApiMethod.GET, "/api/detection/events")).body)
        assertEquals(
            // A malformed limit is absent, not zero.
            """{"all":true}""",
            dispatch(ApiRequest(ApiMethod.GET, "/api/detection/events", query = mapOf("limit" to "abc"))).body,
        )
        assertEquals("""{"stats":true}""", dispatch(ApiRequest(ApiMethod.GET, "/api/detection/stats")).body)
    }

    @Test
    fun `the audit read passes its limit`() {
        coEvery { audit.list(3) } returns """{"limit":3}"""
        coEvery { audit.list(null) } returns """{"all":true}"""
        coEvery { auth.get() } returns """{"auth":true}"""
        coEvery { auth.listSessions() } returns """{"sessions":1}"""

        assertEquals(
            """{"limit":3}""",
            dispatch(ApiRequest(ApiMethod.GET, "/api/audit", query = mapOf("limit" to "3"))).body,
        )
        assertEquals("""{"all":true}""", dispatch(ApiRequest(ApiMethod.GET, "/api/audit")).body)
        assertEquals("""{"auth":true}""", dispatch(ApiRequest(ApiMethod.GET, "/api/auth/config")).body)
        assertEquals("""{"sessions":1}""", dispatch(ApiRequest(ApiMethod.GET, "/api/auth/sessions")).body)
    }

    // ── write routing ──

    @Test
    fun `stream start aliases resume onto the same handler`() {
        coEvery { stream.startAll() } returns """{"started":true}"""

        assertEquals("""{"started":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/stream/start")).body)
        assertEquals("""{"started":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/stream/resume")).body)
    }

    @Test
    fun `the rtmp lifecycle routes dispatch to the rtmp handlers`() {
        coEvery { stream.startRtmp() } returns """{"rtmp":"started"}"""
        coEvery { stream.stopRtmp() } returns """{"rtmp":"stopped"}"""

        assertEquals("""{"rtmp":"started"}""", dispatch(ApiRequest(ApiMethod.POST, "/api/stream/rtmp/start")).body)
        assertEquals("""{"rtmp":"stopped"}""", dispatch(ApiRequest(ApiMethod.POST, "/api/stream/rtmp/stop")).body)
    }

    @Test
    fun `the whip lifecycle routes dispatch to the whip handlers`() {
        coEvery { stream.startWhip() } returns """{"whip":"started"}"""
        coEvery { stream.stopWhip() } returns """{"whip":"stopped"}"""

        assertEquals("""{"whip":"started"}""", dispatch(ApiRequest(ApiMethod.POST, "/api/stream/whip/start")).body)
        assertEquals("""{"whip":"stopped"}""", dispatch(ApiRequest(ApiMethod.POST, "/api/stream/whip/stop")).body)
    }

    @Test
    fun `the web and rtsp output lifecycles dispatch to their own handlers`() {
        coEvery { stream.startWeb() } returns """{"web":"started"}"""
        coEvery { stream.stopWeb() } returns """{"web":"stopped"}"""
        coEvery { stream.startRtsp() } returns """{"rtsp":"started"}"""
        coEvery { stream.stopRtsp() } returns """{"rtsp":"stopped"}"""

        assertEquals("""{"web":"started"}""", dispatch(ApiRequest(ApiMethod.POST, "/api/stream/web/start")).body)
        assertEquals("""{"web":"stopped"}""", dispatch(ApiRequest(ApiMethod.POST, "/api/stream/web/stop")).body)
        assertEquals("""{"rtsp":"started"}""", dispatch(ApiRequest(ApiMethod.POST, "/api/stream/rtsp/start")).body)
        assertEquals("""{"rtsp":"stopped"}""", dispatch(ApiRequest(ApiMethod.POST, "/api/stream/rtsp/stop")).body)
    }

    @Test
    fun `both model download routes dispatch to settings`() {
        coEvery { settings.downloadModel() } returns """{"model":"detection"}"""
        coEvery { settings.downloadAudioModel() } returns """{"model":"audio"}"""

        assertEquals(
            """{"model":"detection"}""",
            dispatch(ApiRequest(ApiMethod.POST, "/api/settings/ml-model/download")).body,
        )
        assertEquals(
            """{"model":"audio"}""",
            dispatch(ApiRequest(ApiMethod.POST, "/api/settings/audio-model/download")).body,
        )
    }

    @Test
    fun `write bodies pass through to the handlers`() {
        coEvery { settings.put("""{"enabled":true}""") } returns """{"saved":true}"""
        coEvery { lens.selectLens("""{"lens":"wide"}""") } returns """{"lens":true}"""

        assertEquals(
            """{"saved":true}""",
            dispatch(ApiRequest(ApiMethod.PUT, "/api/settings", body = """{"enabled":true}""")).body,
        )
        assertEquals(
            """{"lens":true}""",
            dispatch(ApiRequest(ApiMethod.POST, "/api/camera/lens", body = """{"lens":"wide"}""")).body,
        )
    }

    @Test
    fun `the capture and detection-test routes dispatch`() {
        coEvery { capture.capturePhoto() } returns """{"captured":true}"""
        coEvery { detectionTest.test() } returns """{"alerted":true}"""

        assertEquals("""{"captured":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/capture")).body)
        assertEquals("""{"alerted":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/detection/test")).body)
    }

    @Test
    fun `the remaining write routes dispatch with their bodies`() {
        coEvery { recording.start("{}") } returns """{"recording":true}"""
        coEvery { recording.stop() } returns """{"stopped":true}"""
        coEvery { interval.start("{}") } returns """{"started":true}"""
        coEvery { interval.stop() } returns """{"stopped":true}"""
        coEvery { deterrence.setSiren("{}") } returns """{"siren":true}"""
        coEvery { lens.tapFocus("{}") } returns """{"focus":true}"""
        coEvery { lens.setZoom("{}") } returns """{"zoom":true}"""
        coEvery { gallery.batchDelete("{}") } returns """{"deleted":true}"""
        coEvery { settings.import("{}") } returns """{"imported":true}"""
        coEvery { auth.put("""{"enabled":true}""") } returns """{"rotated":true}"""

        assertEquals("""{"recording":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/recording/start", body = "{}")).body)
        assertEquals("""{"stopped":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/recording/stop")).body)
        assertEquals("""{"started":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/capture/interval/start", body = "{}")).body)
        assertEquals("""{"stopped":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/capture/interval/stop")).body)
        assertEquals("""{"siren":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/deterrence/siren", body = "{}")).body)
        assertEquals("""{"focus":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/camera/focus", body = "{}")).body)
        assertEquals("""{"zoom":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/camera/zoom", body = "{}")).body)
        assertEquals("""{"deleted":true}""", dispatch(ApiRequest(ApiMethod.POST, "/api/media/batch-delete", body = "{}")).body)
        assertEquals("""{"imported":true}""", dispatch(ApiRequest(ApiMethod.PUT, "/api/settings/import", body = "{}")).body)
        // The rotation route exists at the router level; the token gate's
        // refusal of it is the WebAuthGateApiTokenTest's verdict, not this one.
        assertEquals(
            """{"rotated":true}""",
            dispatch(ApiRequest(ApiMethod.PUT, "/api/auth/config", body = """{"enabled":true}""")).body,
        )
    }

    @Test
    fun `the remaining status reads dispatch`() {
        coEvery { stream.listClients() } returns """{"clients":2}"""
        coEvery { interval.status() } returns """{"interval":true}"""
        coEvery { recording.status() } returns """{"recording":true}"""
        coEvery { lens.getLenses() } returns """{"lenses":[]}"""

        assertEquals("""{"clients":2}""", dispatch(ApiRequest(ApiMethod.GET, "/api/stream/clients")).body)
        assertEquals("""{"interval":true}""", dispatch(ApiRequest(ApiMethod.GET, "/api/capture/interval/status")).body)
        assertEquals("""{"recording":true}""", dispatch(ApiRequest(ApiMethod.GET, "/api/recording/status")).body)
        assertEquals("""{"lenses":[]}""", dispatch(ApiRequest(ApiMethod.GET, "/api/camera/lenses")).body)
    }

    @Test
    fun `the export route passes the handler response through unwrapped`() {
        // The only route whose handler answers with its own ApiResponse —
        // the router must not re-wrap it into the JSON content type.
        val csv = ApiResponse(200, "text/csv", "zone,file")
        coEvery { detectionEvents.export("csv", "person") } returns csv

        assertEquals(csv, dispatch(ApiRequest(ApiMethod.GET, "/api/detection/events/export", query = mapOf("format" to "csv", "type" to "person"))))
    }

    // ── DELETE routing ──

    @Test
    fun `deletes strip their id prefix and dispatch`() {
        coEvery { stream.kickClient("abc") } returns """{"kicked":true}"""
        coEvery { auth.revokeSession("tok123") } returns """{"revoked":true}"""
        coEvery { gallery.deleteMedia("9") } returns """{"deleted":true}"""

        assertEquals("""{"kicked":true}""", dispatch(ApiRequest(ApiMethod.DELETE, "/api/stream/clients/abc")).body)
        assertEquals("""{"revoked":true}""", dispatch(ApiRequest(ApiMethod.DELETE, "/api/auth/sessions/tok123")).body)
        assertEquals("""{"deleted":true}""", dispatch(ApiRequest(ApiMethod.DELETE, "/api/media/9")).body)
    }

    @Test
    fun `the clear deletes dispatch to their handlers`() {
        coEvery { detectionEvents.clear() } returns """{"cleared":true}"""
        coEvery { audit.clear() } returns """{"cleared":true}"""

        assertEquals("""{"cleared":true}""", dispatch(ApiRequest(ApiMethod.DELETE, "/api/detection/events")).body)
        assertEquals("""{"cleared":true}""", dispatch(ApiRequest(ApiMethod.DELETE, "/api/audit")).body)
    }

    // ── unknown routes and method mismatches ──

    @Test
    fun `unknown paths 404 on every method`() {
        for (method in ApiMethod.entries) {
            val response = dispatch(ApiRequest(method, "/api/nope"))
            assertEquals(404, response.httpStatus)
            assertEquals("""{"error":"Not found"}""", response.body)
        }
    }

    @Test
    fun `a read path never answers a write method and vice versa`() {
        // There is no 405 branch in the router: a method mismatch against an
        // otherwise-known path is just an unregistered route — 404.
        for (request in listOf(
            ApiRequest(ApiMethod.POST, "/api/status"),
            ApiRequest(ApiMethod.DELETE, "/api/settings"),
            ApiRequest(ApiMethod.GET, "/api/stream/start"),
            ApiRequest(ApiMethod.GET, "/api/recording/stop"),
        )) {
            assertEquals(404, dispatch(request).httpStatus)
        }
    }

    // ── the audit half of a mutating dispatch ──

    @Test
    fun `a successful write audits as ok under its method and path`() {
        coEvery { stream.stopAll() } returns """{"stopped":true}"""

        dispatch(ApiRequest(ApiMethod.POST, "/api/stream/stop"))

        verify(exactly = 1) {
            auditLog.record("POST /api/stream/stop", "", AuditEntry.OUTCOME_OK)
        }
    }

    @Test
    fun `an unknown write still audits as an error with the not-found detail`() {
        dispatch(ApiRequest(ApiMethod.POST, "/api/nope"))

        verify(exactly = 1) {
            auditLog.record("POST /api/nope", "not found", AuditEntry.OUTCOME_ERROR)
        }
    }

    @Test
    fun `a handler failure answers 200 with the error payload and audits as an error`() {
        coEvery { settings.put(any()) } throws java.io.IOException("boom")

        val response = dispatch(ApiRequest(ApiMethod.PUT, "/api/settings", body = "{}"))

        assertEquals(200, response.httpStatus)
        assertEquals("""{"success":false,"error":"boom"}""", response.body)
        verify(exactly = 1) {
            auditLog.record("PUT /api/settings", "boom", AuditEntry.OUTCOME_ERROR)
        }
    }

    @Test
    fun `error payloads truncate the message to 200 characters and flatten newlines`() {
        // 251 characters: take(200) keeps the 150 x's, the flattened newline,
        // and the first 49 y's — the newline spends one of the 200 slots.
        coEvery { lens.setTorch(any()) } throws IllegalStateException("x".repeat(150) + "\n" + "y".repeat(100))

        val response = dispatch(ApiRequest(ApiMethod.POST, "/api/camera/torch", body = "{}"))

        assertEquals(200, response.httpStatus)
        assertEquals(
            """{"success":false,"error":"${"x".repeat(150)} ${"y".repeat(49)}"}""",
            response.body,
        )
    }

    @Test
    fun `reads never audit`() {
        coEvery { status.get() } returns """{"status":true}"""

        dispatch(ApiRequest(ApiMethod.GET, "/api/status"))
        dispatch(ApiRequest(ApiMethod.GET, "/api/audit"))

        verify(exactly = 0) { auditLog.record(any(), any(), any()) }
    }

    @Test
    fun `a broken audit write never fails the audited request`() {
        coEvery { settings.put(any()) } returns """{"saved":true}"""
        every { auditLog.record(any(), any(), any()) } throws IllegalStateException("disk full")

        val response = dispatch(ApiRequest(ApiMethod.PUT, "/api/settings", body = "{}"))

        assertEquals(200, response.httpStatus)
        assertEquals("""{"saved":true}""", response.body)
    }
}
