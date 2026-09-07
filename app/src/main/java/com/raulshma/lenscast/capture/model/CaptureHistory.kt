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
) {
    companion object {
        // The bounded-recording ceilings the capture screen's sliders offer;
        // the RecordingController's auto-stop/repeat policy consumes the
        // values as configured, so the bounds live here next to the config.
        const val MAX_DURATION_SECONDS = 3600L
        const val MAX_REPEAT_SECONDS = 3600L

        /**
         * The next occurrence of the wall-clock [hour]:[minute] — today if
         * that time has not passed [now] yet, otherwise tomorrow. The same
         * calendar semantics the capture screen used inline: seconds zeroed,
         * the day rolled over only when the target already lies in the past.
         */
        fun scheduledStartFor(hour: Int, minute: Int, now: Long): Long {
            val calendar = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
                set(java.util.Calendar.SECOND, 0)
                if (timeInMillis < now) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }
            return calendar.timeInMillis
        }
    }
}

enum class RecordingQuality {
    HIGH, MEDIUM, LOW
}
