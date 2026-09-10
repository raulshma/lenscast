package com.raulshma.lenscast.streaming.model

import com.raulshma.lenscast.core.AppJson
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The Kotlin↔web DTO lockstep, tested at the seam. Each test serializes a
 * representative production DTO through the real [AppJson] adapters and
 * asserts it equals the checked-in fixture in web/contract/. The web client's
 * src/contract.test.ts asserts the same fixtures against its types.ts mirror
 * and API_DEFAULTS, so a DTO shape or default change that is not mirrored
 * through the fixtures fails on one side or the other.
 * See CONTEXT.md "Web API Handlers".
 *
 * The settings fixture is intentionally the DTO defaults instance (with the
 * nullable camera fields populated at their UI fallback values): that is what
 * makes a StreamDefaults / DTO default change fail here first, forcing the
 * fixture — and with it the web side — to move in lockstep.
 */
class DtoContractFixtureTest {

    private val moshi = AppJson.moshi
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    /** Unit tests run from the app module dir; the fixtures live in web/contract. */
    private fun fixtureFile(name: String): File =
        listOf(File("../web/contract/$name"), File("web/contract/$name"))
            .firstOrNull { it.exists() }
            ?: error("Contract fixture $name not found; tried ../web/contract and web/contract")

    /** Field-order-insensitive deep comparison of the wire JSON vs the fixture. */
    private fun assertMatchesFixture(fixtureName: String, json: String) {
        val actual = mapAdapter.fromJson(json)
        val expected = mapAdapter.fromJson(fixtureFile(fixtureName).readText())
        assertEquals("Serialized DTO does not match contract fixture $fixtureName", expected, actual)
    }

    @Test
    fun `settings response matches the settings fixture`() {
        val settings = SettingsResponseDto(
            camera = CameraSettingsDto().copy(
                iso = 800,
                exposureTime = 10_000_000L,
                focusDistance = 0f,
                colorTemperature = 5500,
                sceneMode = "",
            ),
            streaming = StreamingSettingsDto().copy(
                rtspInputFormat = "AUTO",
            ),
        )
        assertMatchesFixture(
            "settings.json",
            moshi.adapter(SettingsResponseDto::class.java).toJson(settings),
        )
    }

    @Test
    fun `status response matches the status fixture`() {
        val status = StatusResponseDto(
            streaming = StreamingStatusDto(
                isActive = true,
                url = "http://192.168.1.10:8080/stream",
                webStreamingEnabled = true,
                webStreamingActive = true,
                clientCount = 2,
                audioEnabled = true,
                audioUrl = "http://192.168.1.10:8080/audio",
                rtspEnabled = true,
                rtspStreamingActive = true,
                rtspUrl = "rtsp://192.168.1.10:8554/live",
            ),
            thermal = "NORMAL",
            camera = "Ready",
            battery = BatteryStatusDto(
                level = 87,
                isCharging = false,
                isPowerSaveMode = false,
            ),
            torchOn = false,
            zoomRatio = 1.75,
            lensId = "0",
            lensLabel = "Wide",
            zoomRange = RangeDto(min = 1.0, max = 8.0),
            exposureCompensationRange = RangeDto(min = -12.0, max = 12.0),
            isoRange = RangeDto(min = 100.0, max = 3200.0),
            adaptiveBitrate = AdaptiveBitrateStatusDto(
                enabled = true,
                qualityLevel = "GOOD",
                currentQuality = 75,
                targetQuality = 80,
                currentFps = 24,
                targetFps = 30,
                estimatedBandwidthKbps = 4200,
                minClientThroughputKbps = 3500,
                activeClients = 2,
                adjustmentCount = 7,
            ),
            connectionQuality = ConnectionQualityStatusDto(
                qualityLevel = "GOOD",
                estimatedBandwidthKbps = 4200,
                avgThroughputKbps = 3900,
                minThroughputKbps = 3500,
                worstLatencyMs = 120,
                avgFrameSizeBytes = 45000,
                totalBytesSent = 104857600,
                activeClients = 2,
                framesPerSecond = 23.5,
                clientDetails = mapOf(
                    "192.168.1.50:51000" to ClientConnectionDetailDto(
                        framesSent = 12500,
                        bytesSent = 52428800,
                        avgThroughputKbps = 3900,
                        lastFrameSizeBytes = 46000,
                        lastSendDurationMs = 12,
                    ),
                ),
            ),
            watchdog = WatchdogStatusDto(
                enabled = true,
                status = "MONITORING",
                consecutiveFailures = 0,
                totalRecoveries = 3,
                lastRecoveryTimestamp = 1_788_825_600_000,
                lastFailureReason = "Encoder stalled; pipeline restarted",
            ),
        )
        assertMatchesFixture(
            "status.json",
            moshi.adapter(StatusResponseDto::class.java).toJson(status),
        )
    }

    @Test
    fun `gallery response matches the gallery fixture`() {
        val gallery = GalleryResponseDto(
            items = listOf(
                GalleryItemDto(
                    id = "IMG_20260908_10153042.jpg",
                    type = "PHOTO",
                    fileName = "IMG_20260908_10153042.jpg",
                    timestamp = 1_788_825_600_000,
                    fileSizeBytes = 2_458_624,
                    durationMs = 0,
                    thumbnailUrl = "/api/media/IMG_20260908_10153042.jpg/thumbnail",
                    url = "/api/media/IMG_20260908_10153042.jpg",
                    downloadUrl = "/api/media/IMG_20260908_10153042.jpg?download=1",
                ),
                GalleryItemDto(
                    id = "VID_20260908_10201518.mp4",
                    type = "VIDEO",
                    fileName = "VID_20260908_10201518.mp4",
                    timestamp = 1_788_825_900_000,
                    fileSizeBytes = 52_428_800,
                    durationMs = 15_200,
                    thumbnailUrl = "/api/media/VID_20260908_10201518.mp4/thumbnail",
                    url = "/api/media/VID_20260908_10201518.mp4",
                    downloadUrl = "/api/media/VID_20260908_10201518.mp4?download=1",
                ),
            ),
            total = 42,
            page = 0,
            pageSize = 50,
            hasMore = true,
        )
        assertMatchesFixture(
            "gallery.json",
            moshi.adapter(GalleryResponseDto::class.java).toJson(gallery),
        )
    }

    @Test
    fun `recording status matches the recording status fixture`() {
        val recording = RecordingStatusDto(
            isRecording = true,
            elapsedSeconds = 42,
            isScheduled = true,
            scheduledStartTimeMs = 1_788_825_600_000,
        )
        assertMatchesFixture(
            "recording-status.json",
            moshi.adapter(RecordingStatusDto::class.java).toJson(recording),
        )
    }

    @Test
    fun `lenses response matches the lenses fixture`() {
        val lenses = LensesResponseDto(
            lenses = listOf(
                LensDto(
                    index = 0,
                    id = "0",
                    label = "Back Camera",
                    focalLength = 5.4,
                    isFront = false,
                    selected = true,
                ),
                LensDto(
                    index = 1,
                    id = "1",
                    label = "Front Camera",
                    focalLength = 4.0,
                    isFront = true,
                    selected = false,
                ),
            ),
            selectedIndex = 0,
        )
        assertMatchesFixture(
            "lenses.json",
            moshi.adapter(LensesResponseDto::class.java).toJson(lenses),
        )
    }

    @Test
    fun `interval capture status matches the interval capture fixture`() {
        val interval = IntervalCaptureStatusDto(
            isRunning = true,
            completedCaptures = 12,
        )
        assertMatchesFixture(
            "interval-capture-status.json",
            moshi.adapter(IntervalCaptureStatusDto::class.java).toJson(interval),
        )
    }

    @Test
    fun `detection events response matches the detection events fixture`() {
        val events = DetectionEventsResponseDto(
            events = listOf(
                DetectionEventDto(
                    id = "3f2b8c4e-1a5d-4e6f-9a7b-2c8d0e1f2a3b",
                    type = "motion",
                    source = "lenscast",
                    timestampMs = 1_788_825_600_000,
                    snapshotJpegBase64 = "/9j/4AAQSkZJRg==",
                    dispatchedActions = listOf("recording", "webhook"),
                    zones = listOf("Doorway"),
                    labels = listOf("person"),
                    clipMediaId = 123_456L,
                    clipFileName = "VID_20260908_10153100.mp4",
                ),
            ),
            total = 1,
        )
        assertMatchesFixture(
            "detection-events.json",
            moshi.adapter(DetectionEventsResponseDto::class.java).toJson(events),
        )
    }

    @Test
    fun `settings export matches the settings export fixture`() {
        val export = SettingsExportDto(
            exportedAtMs = 1_788_825_600_000,
            settings = SettingsResponseDto(
                camera = CameraSettingsDto().copy(
                    iso = 800,
                    exposureTime = 10_000_000L,
                    focusDistance = 0f,
                    colorTemperature = 5500,
                    sceneMode = "",
                ),
                streaming = StreamingSettingsDto().copy(
                    rtspInputFormat = "AUTO",
                ),
            ),
        )
        assertMatchesFixture(
            "settings-export.json",
            moshi.adapter(SettingsExportDto::class.java).toJson(export),
        )
    }

    @Test
    fun `audit log response matches the audit log fixture`() {
        val audit = AuditLogResponseDto(
            entries = listOf(
                AuditEntryDto(
                    timestampMs = 1_788_825_600_000,
                    action = "PUT /api/settings",
                    detail = "",
                    outcome = "ok",
                ),
                AuditEntryDto(
                    timestampMs = 1_788_825_540_000,
                    action = "login.failed",
                    detail = "192.168.1.20",
                    outcome = "error",
                ),
            ),
            total = 2,
        )
        assertMatchesFixture(
            "audit-log.json",
            moshi.adapter(AuditLogResponseDto::class.java).toJson(audit),
        )
    }

    @Test
    fun `detection test response matches the detection test fixture`() {
        val test = DetectionTestResponseDto(
            dispatchedActions = listOf("webhook", "mqtt", "notify"),
        )
        assertMatchesFixture(
            "detection-test.json",
            moshi.adapter(DetectionTestResponseDto::class.java).toJson(test),
        )
    }

    @Test
    fun `system info response matches the system fixture`() {
        val system = SystemInfoResponseDto(
            appVersion = "1.9.0",
            deviceModel = "Pixel 8",
            deviceManufacturer = "Google",
            androidVersion = "15",
            sdkInt = 35,
            osUptimeMs = 86_400_000,
            processUptimeMs = 3_600_000,
            battery = BatteryDetailDto(
                level = 76,
                isCharging = true,
                temperatureTenthsC = 295,
                voltageMillivolts = 4350,
                health = "good",
            ),
            storage = StorageInfoDto(
                usedBytes = 1_073_741_824,
                quotaBytes = 2_147_483_648,
                freeBytes = 53_687_091_200,
                totalBytes = 107_374_182_400,
            ),
        )
        assertMatchesFixture(
            "system.json",
            moshi.adapter(SystemInfoResponseDto::class.java).toJson(system),
        )
    }

    @Test
    fun `detection stats response matches the detection stats fixture`() {
        val stats = DetectionStatsResponseDto(
            last24h = mapOf("motion" to 6, "sound" to 2),
            last7d = mapOf("motion" to 21, "sound" to 8, "tamper" to 1),
            allTime = mapOf("motion" to 90, "sound" to 30, "tamper" to 3),
            perDay = listOf(
                DailyCountDto("2026-09-09", 5),
                DailyCountDto("2026-09-10", 3),
            ),
            totalEvents = 123,
            topZones = listOf(LabeledCountDto("Driveway", 12)),
            topLabels = listOf(
                LabeledCountDto("person", 9),
                LabeledCountDto("car", 4),
            ),
        )
        assertMatchesFixture(
            "detection-stats.json",
            moshi.adapter(DetectionStatsResponseDto::class.java).toJson(stats),
        )
    }
}
