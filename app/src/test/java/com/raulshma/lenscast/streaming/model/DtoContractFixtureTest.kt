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
                    thumbnailUrl = "/api/media/IMG_20260908_10153042.jpg",
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
                ),
            ),
            total = 1,
        )
        assertMatchesFixture(
            "detection-events.json",
            moshi.adapter(DetectionEventsResponseDto::class.java).toJson(events),
        )
    }
}
