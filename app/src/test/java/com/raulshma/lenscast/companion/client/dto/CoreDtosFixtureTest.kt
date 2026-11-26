package com.raulshma.lenscast.companion.client.dto

import com.raulshma.lenscast.core.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The companion client's decode-side DTO lockstep: each test decodes the
 * checked-in web/contract JSON fixture through the real [AppJson] adapters
 * and asserts representative fields. The fixtures are the same documents the
 * web dashboard's contract.test.ts pins against types.ts (and the app-side
 * DtoContractFixtureTest pins against the server DTOs), so a wire-shape
 * change that is not mirrored into [com.raulshma.lenscast.companion.client.dto]
 * fails here first — a fixture that stops decoding is a transcription bug,
 * not a fixture bug. Every DTO domain that has a fixture gets a case; the
 * auth probe responses (AuthStatusInfo / AuthSessionInfo / LoginResponse)
 * have no fixture yet and ride their client.ts shapes.
 */
class CoreDtosFixtureTest {

    private val moshi = AppJson.moshi

    /** Unit tests run from the app module dir; the fixtures live in web/contract. */
    private fun fixtureFile(name: String): File =
        listOf(File("../web/contract/$name"), File("web/contract/$name"))
            .firstOrNull { it.exists() }
            ?: error("Contract fixture $name not found; tried ../web/contract and web/contract")

    private inline fun <reified T> decode(name: String): T =
        moshi.adapter(T::class.java).fromJson(fixtureFile(name).readText())
            ?: error("Fixture $name decoded to null")

    @Test
    fun `status fixture decodes into DeviceStatus`() {
        val status = decode<DeviceStatus>("status.json")
        val streaming = status.streaming
        assertEquals(true, streaming.isActive)
        assertEquals("http://192.168.1.10:8080/stream", streaming.url)
        assertEquals(2, streaming.clientCount)
        assertEquals(true, streaming.webStreamingActive)
        assertEquals(true, streaming.rtspStreamingActive)
        assertEquals("rtsp://192.168.1.10:8554/live", streaming.rtspUrl)
        assertEquals(false, streaming.rtmpEnabled)
        assertEquals("idle", streaming.rtmpStatus)
        // One WHEP viewer — the session whose measured `whepStream` block the fixture carries.
        assertEquals(1, streaming.whepClients)
        assertNull(streaming.rtmpError)
        assertEquals("NORMAL", status.thermal)
        assertEquals(87, status.battery.level)
        assertEquals("Ready", status.camera)
        assertEquals(false, status.torchOn)
        assertEquals(false, status.sirenActive)
        assertEquals(false, status.cameraBlockedByLock)
        assertEquals(1.75, status.zoomRatio, 0.0)
        assertEquals("0", status.lensId)
        assertEquals("Wide", status.lensLabel)
        assertEquals(1.0, status.zoomRange?.min)
        assertEquals(8.0, status.zoomRange?.max)
        assertEquals(-12.0, status.exposureCompensationRange?.min)
        assertEquals(3200.0, status.isoRange?.max)
        assertEquals("GOOD", status.adaptiveBitrate?.qualityLevel)
        assertEquals(7, status.adaptiveBitrate?.adjustmentCount)
        val quality = status.connectionQuality
        assertEquals("GOOD", quality?.qualityLevel)
        assertEquals(104_857_600L, quality?.totalBytesSent)
        val detail = quality?.clientDetails?.get("192.168.1.50:51000")
        assertEquals(12_500L, detail?.framesSent)
        assertEquals(12L, detail?.lastSendDurationMs)
        assertEquals("MONITORING", status.watchdog?.status)
        assertEquals(3, status.watchdog?.totalRecoveries)
        assertEquals("Encoder stalled; pipeline restarted", status.watchdog?.lastFailureReason)
        assertEquals(2_000_000, status.encodedVideoBitrate)
    }

    @Test
    fun `system fixture decodes into SystemInfo`() {
        val system = decode<SystemInfo>("system.json")
        assertEquals("1.9.0", system.appVersion)
        assertEquals("Pixel 8", system.deviceModel)
        assertEquals("Google", system.deviceManufacturer)
        assertEquals("15", system.androidVersion)
        assertEquals(35, system.sdkInt)
        assertEquals(86_400_000L, system.osUptimeMs)
        assertEquals(3_600_000L, system.processUptimeMs)
        assertEquals(76, system.battery.level)
        assertEquals(true, system.battery.isCharging)
        assertEquals(295, system.battery.temperatureTenthsC)
        assertEquals("good", system.battery.health)
        assertEquals(212, system.battery.batteryCycleCount)
        assertEquals(53_687_091_200L, system.storage.freeBytes)
        assertEquals(107_374_182_400L, system.storage.totalBytes)
    }

    @Test
    fun `lenses fixture decodes into LensesResponse`() {
        val lenses = decode<LensesResponse>("lenses.json")
        assertEquals(3, lenses.lenses.size)
        assertEquals(0, lenses.selectedIndex)
        val wide = lenses.lenses[0]
        assertEquals("Wide", wide.label)
        assertEquals("WIDE", wide.kind)
        assertEquals(4.3, wide.focalLength, 0.0)
        assertEquals(true, wide.selected)
        assertNull(wide.zoomRatio)
        val zoom = lenses.lenses[1]
        assertEquals("ZOOM", zoom.kind)
        assertEquals(2.0, zoom.zoomRatio)
        assertEquals(false, zoom.selected)
        assertEquals(true, lenses.lenses[2].isFront)
    }

    @Test
    fun `detection events fixture decodes into DetectionEventsResponse`() {
        val response = decode<DetectionEventsResponse>("detection-events.json")
        assertEquals(1, response.total)
        val event = response.events.single()
        assertEquals("3f2b8c4e-1a5d-4e6f-9a7b-2c8d0e1f2a3b", event.id)
        assertEquals("motion", event.type)
        assertEquals("lenscast", event.source)
        assertEquals(1_788_825_600_000L, event.timestampMs)
        assertEquals("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAoHBwgHBgoICAgLCgoLDhgQDg0NDh0VFhEYIx8lJCIfIiEmKzcvJik0KSEiMEExNDk7Pj4+JS5ESUM8SDc9Pjv/2wBDAQoLCw4NDhwQEBw7KCIoOzs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozv/wAARCADwAUADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDzOiiiqEFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQAUUUUAFFFFABRRRQB//Z", event.snapshotJpegBase64)
        assertEquals(listOf("recording", "webhook"), event.dispatchedActions)
        assertEquals(listOf("Doorway"), event.zones)
        assertEquals(listOf("person"), event.labels)
        assertEquals(123_456L, event.clipMediaId)
        assertEquals("VID_20260908_10153100.mp4", event.clipFileName)
        assertEquals("#/gallery/123456", event.url)
    }

    @Test
    fun `detection stats fixture decodes into DetectionStats`() {
        val stats = decode<DetectionStats>("detection-stats.json")
        assertEquals(6, stats.last24h["motion"])
        assertEquals(2, stats.last24h["sound"])
        assertEquals(1, stats.last7d["tamper"])
        assertEquals(90, stats.allTime["motion"])
        assertEquals(listOf("2026-09-09", "2026-09-10"), stats.perDay.map { it.day })
        assertEquals(5, stats.perDay[0].count)
        assertEquals(123, stats.totalEvents)
        assertEquals("Driveway", stats.topZones.single().label)
        assertEquals(12, stats.topZones.single().count)
        assertEquals(listOf("person", "car"), stats.topLabels.map { it.label })
    }

    @Test
    fun `gallery fixture decodes into GalleryResponse`() {
        val gallery = decode<GalleryResponse>("gallery.json")
        assertEquals(42, gallery.total)
        assertEquals(0, gallery.page)
        assertEquals(50, gallery.pageSize)
        assertEquals(true, gallery.hasMore)
        assertEquals(2, gallery.items.size)
        val photo = gallery.items[0]
        assertEquals("PHOTO", photo.type)
        assertEquals("IMG_20260908_10153042.jpg", photo.fileName)
        assertEquals(2_458_624L, photo.fileSizeBytes)
        assertEquals(0L, photo.durationMs)
        assertEquals(false, photo.favorite)
        assertEquals("/api/media/IMG_20260908_10153042.jpg", photo.url)
        assertEquals("/api/media/IMG_20260908_10153042.jpg?download=1", photo.downloadUrl)
        val video = gallery.items[1]
        assertEquals("VIDEO", video.type)
        assertEquals(15_200L, video.durationMs)
    }

    @Test
    fun `recording status fixture decodes into RecordingStatus`() {
        val recording = decode<RecordingStatus>("recording-status.json")
        assertEquals(true, recording.isRecording)
        assertEquals(42, recording.elapsedSeconds)
        assertEquals(true, recording.isScheduled)
        assertEquals(1_788_825_600_000L, recording.scheduledStartTimeMs)
    }

    @Test
    fun `recording sessions fixture decodes into RecordingsResponse`() {
        val recordings = decode<RecordingsResponse>("recording-sessions.json")
        assertEquals(5, recordings.sessions.size)
        assertEquals(
            listOf("manual", "motion", "sound", "continuous", "interval"),
            recordings.sessions.map { it.trigger },
        )
        val session = recordings.sessions[1]
        assertEquals(1_788_825_600_000L, session.startMs)
        assertEquals(1_788_826_500_000L, session.endMs)
        assertEquals("2f8a1c3d-5b6e-4a7c-9d0e-1f2a3b4c5d6e", session.mediaId)
        assertEquals(1_788_850_060_000L, recordings.sessions[2].endMs)
    }

    @Test
    fun `audit log fixture decodes into AuditResponse`() {
        val audit = decode<AuditResponse>("audit-log.json")
        assertEquals(2, audit.total)
        val ok = audit.entries[0]
        assertEquals("PUT /api/settings", ok.action)
        assertEquals("", ok.detail)
        assertEquals("ok", ok.outcome)
        val failed = audit.entries[1]
        assertEquals("login.failed", failed.action)
        assertEquals("192.168.1.20", failed.detail)
        assertEquals("error", failed.outcome)
        assertEquals(1_788_825_540_000L, failed.timestampMs)
    }

    @Test
    fun `a status payload missing the additive fields decodes against older devices`() {
        // The wire-tolerance half of the lockstep: older devices omit the
        // optional blocks/fields (rtmp/whip/srt trio, whepClients, ranges,
        // watchdog), and the client must not crash on them — every optional
        // field folds to its null default.
        val older = """
            {
              "streaming": {
                "isActive": true,
                "url": "http://192.168.1.10:8080/stream",
                "webStreamingEnabled": true,
                "webStreamingActive": true,
                "clientCount": 1,
                "audioEnabled": false,
                "audioUrl": "",
                "rtspEnabled": false,
                "rtspStreamingActive": false,
                "rtspUrl": ""
              },
              "thermal": "NORMAL",
              "camera": "Ready",
              "battery": {"level": 50, "isCharging": false, "isPowerSaveMode": false},
              "torchOn": false,
              "sirenActive": false,
              "zoomRatio": 1.0
            }
        """.trimIndent()
        val status = moshi.adapter(DeviceStatus::class.java).fromJson(older) ?: error("decode failed")
        assertEquals(false, status.torchOn)
        assertEquals(false, status.sirenActive)
        assertNull(status.streaming.whepClients)
        assertNull(status.streaming.srtEnabled)
        assertNull(status.watchdog)
    }
}
