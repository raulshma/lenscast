package com.raulshma.lenscast.camera

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.raulshma.lenscast.camera.model.ExifGeoPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The EXIF writer for captured photos: the app-credit Artist/UserComment
 * tags on every camera-screen capture, plus the GPS coordinate tags when
 * the opt-in geotag setting is on and a fix is available. All tag values
 * and the GPS rational formatting come from the pure [ExifGeoPolicy]; this
 * class owns only the file/URI plumbing through the framework
 * [android.media.ExifInterface] (no extra dependency). A tagging failure
 * logs and never breaks the capture path.
 *
 * @param locationProvider resolves the current best fix, or null — the
 * caller owns the location-permission gate.
 */
class ExifTagger(
    private val context: Context,
    private val appName: String,
    private val locationProvider: () -> android.location.Location?,
) {

    /**
     * Tags the photo at [filePath] (a `content://` gallery URI or a plain
     * file path — the same shapes PhotoCaptureManager records in history).
     * Runs on IO; safe to call from a capture callback.
     */
    suspend fun tagPhoto(filePath: String, includeGeotag: Boolean) = withContext(Dispatchers.IO) {
        val exif = open(filePath) ?: return@withContext
        try {
            exif.setAttribute(android.media.ExifInterface.TAG_ARTIST, ExifGeoPolicy.artistTag(appName))
            exif.setAttribute(
                android.media.ExifInterface.TAG_USER_COMMENT,
                ExifGeoPolicy.commentTag(appName),
            )
            if (includeGeotag) {
                val location = locationProvider()
                ExifGeoPolicy.geotagTags(location?.latitude, location?.longitude)?.let { gps ->
                    exif.setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, gps.latitudeDms)
                    exif.setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, gps.latitudeRef)
                    exif.setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE, gps.longitudeDms)
                    exif.setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, gps.longitudeRef)
                }
            }
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "EXIF tagging failed for $filePath", e)
        }
    }

    /**
     * Resolves the writable [android.media.ExifInterface] for one saved
     * photo: gallery content URIs open through a read-write file descriptor
     * (API 24+; older devices skip content tagging — their captures ride the
     * legacy file path), plain paths open directly.
     */
    private fun open(filePath: String): android.media.ExifInterface? {
        return try {
            if (filePath.startsWith("content://")) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
                val fd = context.contentResolver.openFileDescriptor(Uri.parse(filePath), "rw")
                    ?: return null
                fd.use { android.media.ExifInterface(it.fileDescriptor) }
            } else {
                val file = File(filePath)
                if (!file.exists()) return null
                android.media.ExifInterface(file.absolutePath)
            }
        } catch (e: Exception) {
            Log.w(TAG, "EXIF open failed for $filePath", e)
            null
        }
    }

    companion object {
        private const val TAG = "ExifTagger"
    }
}
