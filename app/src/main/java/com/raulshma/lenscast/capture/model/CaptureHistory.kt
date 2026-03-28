package com.raulshma.lenscast.capture.model

enum class CaptureType {
    PHOTO, VIDEO
}

data class CaptureHistory(
    val id: String,
    val type: CaptureType,
    val fileName: String,
    val filePath: String,
    val timestamp: Long,
    val fileSizeBytes: Long = 0,
    val durationMs: Long = 0,
)

data class IntervalCaptureConfig(
    val intervalSeconds: Long = 5,
    val totalCaptures: Int = 100,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val imageQuality: Int = 90,
    val resolutionName: String = "FHD_1080P",
    val captureMode: CaptureMode = CaptureMode.MINIMIZE_LATENCY,
    val flashMode: FlashMode = FlashMode.OFF,
)

enum class CaptureMode {
    MINIMIZE_LATENCY, MAXIMIZE_QUALITY
}

enum class FlashMode {
    ON, OFF, AUTO
}

data class RecordingConfig(
    val durationSeconds: Long = 0,
    val repeatIntervalSeconds: Long = 0,
    val quality: RecordingQuality = RecordingQuality.HIGH,
    val maxFileSizeBytes: Long = 0,
)

enum class RecordingQuality {
    HIGH, MEDIUM, LOW
}
