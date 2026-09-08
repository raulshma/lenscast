package com.raulshma.lenscast.capture.model

/**
 * Pure capture-media identity: the mime type each [CaptureType] serializes
 * as, the MediaStore folder the captures land in, and the content-URI sniff.
 * PhotoCaptureManager and RecordingService write these values into MediaStore
 * and CaptureHistoryStore queries them back — one home, byte-for-byte. The
 * rooted write paths and the query prefixes are derived from the same folder
 * names, so a rename desyncs nothing.
 */
object CaptureMediaFormat {
    const val MIME_PHOTO = "image/jpeg"
    const val MIME_VIDEO = "video/mp4"

    /** The shared capture folder name under the public Pictures/Movies roots. */
    const val PHOTO_DIR_NAME = "LensCast"
    const val VIDEO_DIR_NAME = "LensCast"

    private const val PICTURES_ROOT = "Pictures"
    private const val MOVIES_ROOT = "Movies"

    /**
     * The MediaStore RELATIVE_PATH captures are written with (no trailing
     * slash) — the exact strings RecordingService and PhotoCaptureManager
     * insert, composed here once per format instead of root + dir at each
     * write site.
     */
    const val PHOTOS_WRITE_RELATIVE_PATH = "$PICTURES_ROOT/$PHOTO_DIR_NAME"
    const val VIDEOS_WRITE_RELATIVE_PATH = "$MOVIES_ROOT/$VIDEO_DIR_NAME"

    /** MediaStore RELATIVE_PATH query prefixes (trailing slash included). */
    const val PHOTOS_RELATIVE_PATH = "$PHOTOS_WRITE_RELATIVE_PATH/"
    const val VIDEOS_RELATIVE_PATH = "$VIDEOS_WRITE_RELATIVE_PATH/"

    /** The one mime each capture type streams/saves as. */
    fun mimeFor(type: CaptureType): String = when (type) {
        CaptureType.PHOTO -> MIME_PHOTO
        CaptureType.VIDEO -> MIME_VIDEO
    }

    /** True for MediaStore content URIs (vs file paths or file:// URIs). */
    fun isContentUri(path: String): Boolean = path.startsWith("content://")

    /**
     * The legacy file-system directory for on-disk video output (pre-Q
     * writes), derived from the one folder name so a rename moves every
     * producer.
     */
    fun videoDir(moviesRoot: java.io.File): java.io.File = java.io.File(moviesRoot, VIDEO_DIR_NAME)
}
