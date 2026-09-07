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

// Fields that never reached the worker (start/end windows, capture mode,
// resolution, JPEG quality) were deleted: the UI must not advertise controls
// with no runtime effect.
data class IntervalCaptureConfig(
    val intervalSeconds: Long = 5,
    val totalCaptures: Int = 100,
    val flashMode: FlashMode = FlashMode.OFF,
)

enum class FlashMode {
    ON, OFF, AUTO
}

data class RecordingConfig(
    val durationSeconds: Long = 0,
    val repeatIntervalSeconds: Long = 0,
    val quality: RecordingQuality = RecordingQuality.HIGH,
    val includeAudio: Boolean = true,
    val startTimeMs: Long? = null,
)

enum class RecordingQuality {
    HIGH, MEDIUM, LOW
}
