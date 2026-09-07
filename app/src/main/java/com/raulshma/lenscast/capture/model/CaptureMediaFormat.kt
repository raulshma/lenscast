package com.raulshma.lenscast.capture.model

/**
 * Pure capture-media identity: the mime type each [CaptureType] serializes
 * as, the MediaStore folder the captures land in, and the content-URI sniff.
 * PhotoCaptureManager and RecordingService write these values into MediaStore
 * and CaptureHistoryStore queries them back — one home, byte-for-byte.
 */
object CaptureMediaFormat {
    const val MIME_PHOTO = "image/jpeg"
    const val MIME_VIDEO = "video/mp4"

    /** The shared capture folder name under the public Pictures/Movies roots. */
    const val PHOTO_DIR_NAME = "LensCast"
    const val VIDEO_DIR_NAME = "LensCast"

    /** MediaStore RELATIVE_PATH query prefixes (trailing slash included). */
    const val PHOTOS_RELATIVE_PATH = "Pictures/LensCast/"
    const val VIDEOS_RELATIVE_PATH = "Movies/LensCast/"

    /** The one mime each capture type streams/saves as. */
    fun mimeFor(type: CaptureType): String = when (type) {
        CaptureType.PHOTO -> MIME_PHOTO
        CaptureType.VIDEO -> MIME_VIDEO
    }

    /** True for MediaStore content URIs (vs file paths or file:// URIs). */
    fun isContentUri(path: String): Boolean = path.startsWith("content://")
}
