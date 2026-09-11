package com.raulshma.lenscast.camera.model

import kotlin.math.abs
import kotlin.math.floor

/**
 * The pure EXIF geotag decisions: whether a fix is taggable, the GPS
 * coordinate rational strings (the EXIF `"deg/1,min/1,sec/1000"` form), the
 * N/S / E/W hemisphere refs, and the artist/comment tag values. The tagger
 * runtime ([com.raulshma.lenscast.camera.ExifTagger]) only writes what this
 * policy produces — the formatting is JVM-tested, not device-only.
 */
object ExifGeoPolicy {

    const val LATITUDE_MIN = -90.0
    const val LATITUDE_MAX = 90.0
    const val LONGITUDE_MIN = -180.0
    const val LONGITUDE_MAX = 180.0

    /** The app-credit EXIF Artist tag value. */
    fun artistTag(appName: String): String = appName

    /** The app-credit EXIF UserComment tag value. */
    fun commentTag(appName: String): String = "Captured with $appName"

    /** A valid, non-null fix within the coordinate ranges. */
    fun isTaggable(latitude: Double?, longitude: Double?): Boolean {
        if (latitude == null || longitude == null) return false
        if (latitude.isNaN() || longitude.isNaN()) return false
        return latitude in LATITUDE_MIN..LATITUDE_MAX &&
            longitude in LONGITUDE_MIN..LONGITUDE_MAX
    }

    /** The GPS hemisphere reference for a latitude. */
    fun latitudeRef(latitude: Double): String = if (latitude < 0) "S" else "N"

    /** The GPS hemisphere reference for a longitude. */
    fun longitudeRef(longitude: Double): String = if (longitude < 0) "W" else "E"

    /**
     * One coordinate as the EXIF GPS rational string
     * `"deg/1,min/1,sec/1000"` — seconds scaled to milliseconds so integer
     * rationals stay exact; the sign travels in the ref, not the DMS.
     */
    fun dmsRationals(coordinate: Double): String {
        val absolute = abs(coordinate)
        val degrees = floor(absolute).toInt()
        val minutesTotal = (absolute - degrees) * 60.0
        val minutes = floor(minutesTotal).toInt()
        val secondsMillis = ((minutesTotal - minutes) * 60.0 * 1000.0).toInt()
        return "$degrees/1,$minutes/1,$secondsMillis/1000"
    }

    /**
     * The complete GPS tag set for one fix, or null when the fix is not
     * taggable (null/NaN/out-of-range coordinates — a missing fix never
     * writes zeroed coordinates).
     */
    fun geotagTags(latitude: Double?, longitude: Double?): GpsTags? {
        if (!isTaggable(latitude, longitude)) return null
        return GpsTags(
            latitudeDms = dmsRationals(latitude!!),
            latitudeRef = latitudeRef(latitude),
            longitudeDms = dmsRationals(longitude!!),
            longitudeRef = longitudeRef(longitude),
        )
    }

    /** The four GPS tag values the tagger writes for a valid fix. */
    data class GpsTags(
        val latitudeDms: String,
        val latitudeRef: String,
        val longitudeDms: String,
        val longitudeRef: String,
    )
}
