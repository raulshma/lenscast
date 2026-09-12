package com.raulshma.lenscast.capture.model

import com.raulshma.lenscast.core.parseEnum
import com.squareup.moshi.JsonClass

enum class CaptureType {
    PHOTO, VIDEO
}

/**
 * What started a recording (or produced a video capture), stamped at
 * creation time so the NVR timeline no longer has to reconstruct it. The
 * wire names are the dashboard timeline's trigger enum (`manual`, `motion`,
 * `sound`, `continuous`, `scheduled`, `interval`) — [wireName] is the only
 * spelling that crosses onto the wire, and [fromWireName] decodes through
 * the one `core/EnumParsing.parseEnum` with MANUAL as the explicit fallback
 * (an unknown value reads as a manual capture, never crashes the timeline).
 */
enum class RecordingTrigger(val wireName: String) {
    MANUAL("manual"),
    MOTION("motion"),
    SOUND("sound"),
    SCHEDULED("scheduled"),
    CONTINUOUS_LOOP("continuous"),
    INTERVAL("interval"),
    ;

    companion object {
        fun fromWireName(name: String?): RecordingTrigger =
            parseEnum(entries.firstOrNull { it.wireName == name }?.name, MANUAL)
    }
}

/**
 * The pure decision of which trigger a capture-history entry carries: an
 * explicitly-attributed origin (the detection coordinator's clips, the
 * continuous loop's segments, an assembled timelapse) always wins; a config
 * with a future [RecordingConfig.startTimeMs] that reached the scheduled
 * start path folds MANUAL into SCHEDULED; everything else is MANUAL. Pure so
 * the producer-side stamping is JVM-tested instead of spread through the
 * service.
 */
object RecordingTriggerPolicy {
    fun forStart(config: RecordingConfig?, scheduledStart: Boolean): RecordingTrigger {
        if (config == null) return RecordingTrigger.MANUAL
        return when {
            config.trigger != RecordingTrigger.MANUAL -> config.trigger
            scheduledStart -> RecordingTrigger.SCHEDULED
            else -> RecordingTrigger.MANUAL
        }
    }
}

@JsonClass(generateAdapter = true)
data class CaptureHistory(
    val id: String,
    val type: CaptureType,
    val fileName: String,
    val filePath: String,
    val timestamp: Long,
    val fileSizeBytes: Long = 0,
    val durationMs: Long = 0,
    /**
     * The recording's provenance ([RecordingTrigger.wireName]), set at
     * creation; null on captures created before the field existed or adopted
     * from device media — the timeline reconstructs those from detection
     * events exactly as before.
     */
    val trigger: String? = null,
    /** User-marked favorite; survives store merges and MediaStore refreshes. */
    val favorite: Boolean = false,
)

// Fields that never reached the worker (start/end windows, capture mode,
// resolution, JPEG quality) were deleted: the UI must not advertise controls
// with no runtime effect.
@JsonClass(generateAdapter = true)
data class IntervalCaptureConfig(
    val intervalSeconds: Long = 5,
    val totalCaptures: Int = 100,
    val flashMode: FlashMode = FlashMode.OFF,
)

enum class FlashMode {
    ON, OFF, AUTO
}

@JsonClass(generateAdapter = true)
data class RecordingConfig(
    val durationSeconds: Long = 0,
    val repeatIntervalSeconds: Long = 0,
    val quality: RecordingQuality = RecordingQuality.HIGH,
    val includeAudio: Boolean = true,
    val startTimeMs: Long? = null,
    /** Provenance stamp carried onto the finalized capture's history entry. */
    val trigger: RecordingTrigger = RecordingTrigger.MANUAL,
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
