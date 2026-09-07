package com.raulshma.lenscast.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure capture file naming: one timestamp format, one photo pattern, one
 * video pattern. PhotoCaptureManager and RecordingService both name their
 * output here, so the on-disk naming cannot drift between them.
 */
object MediaFileNaming {
    private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun photoName(now: Date): String = "IMG_${DATE_FORMAT.format(now)}.jpg"

    fun videoName(now: Date): String = "VID_${DATE_FORMAT.format(now)}.mp4"
}
