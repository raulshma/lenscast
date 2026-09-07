package com.raulshma.lenscast.capture

import android.content.ContentResolver
import android.net.Uri
import com.raulshma.lenscast.capture.model.CaptureMediaFormat
import java.io.File
import java.io.InputStream

/**
 * The one scheme ladder for capture-media paths. A history entry's
 * `filePath` may be a MediaStore `content://` URI, a `file://` URI, or a
 * plain relative/absolute path (pre-Q writes); every consumer — gallery UI,
 * web handlers, the capture manager, the history store's deletes — used to
 * re-roll its own variant of "which kind is this, and how do I open/verify/
 * remove it". This owns the classification once and the four operations over
 * it; callers hand in a path and get a verdict.
 *
 * The [ContentResolver] serves the `content://` branches; the `file://` and
 * plain-path branches are pure `java.io` and JVM-testable. Classify is pure
 * everywhere.
 */
class CaptureMediaResolver(private val contentResolver: ContentResolver? = null) {

    /** Which kind of path a history entry carries. */
    enum class PathKind { CONTENT_URI, FILE_URI, PLAIN_PATH }

    // ── Classification (pure; string in → verdict out) ──

    fun classify(path: String): PathKind = when {
        CaptureMediaFormat.isContentUri(path) -> PathKind.CONTENT_URI
        path.startsWith(FILE_SCHEME) -> PathKind.FILE_URI
        else -> PathKind.PLAIN_PATH
    }

    /**
     * The file behind a file-backed path: resolved out of the `file://` URI
     * when scheme'd (through `android.net.Uri`, as the history store always
     * parsed it), taken as-is when plain. Null for content URIs.
     */
    fun fileOf(path: String): File? = when (classify(path)) {
        PathKind.CONTENT_URI -> null
        PathKind.FILE_URI -> File(Uri.parse(path).path.orEmpty())
        PathKind.PLAIN_PATH -> File(path)
    }

    // ── Display resolution (gallery UI models) ──

    /**
     * What a gallery image/video consumer should load: a [Uri] for scheme'd
     * paths (as recorded — never probed on disk), a [File] only when a plain
     * path exists on disk, null otherwise.
     */
    fun displayModel(path: String): Any? = when (classify(path)) {
        PathKind.CONTENT_URI, PathKind.FILE_URI -> Uri.parse(path)
        PathKind.PLAIN_PATH -> File(path).takeIf { it.exists() }
    }

    // ── Streams ──

    /**
     * Opens the media at [path]: through the ContentResolver for content
     * URIs, straight from disk for file URIs and plain paths. Null when the
     * media cannot be opened (missing file, provider rejection, IO error).
     */
    fun openStream(path: String): InputStream? = try {
        when (classify(path)) {
            PathKind.CONTENT_URI -> contentResolver?.openInputStream(Uri.parse(path))
            PathKind.FILE_URI, PathKind.PLAIN_PATH ->
                fileOf(path)?.takeIf { it.exists() }?.inputStream()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Opens the media plus the size to report for it: content URIs report the
     * caller's recorded size (MediaStore thumbnails/sizes may not be known at
     * open time), file-backed paths report the actual file length.
     */
    fun openMedia(path: String, recordedSizeBytes: Long): OpenedMedia? {
        val stream = openStream(path) ?: return null
        val sizeBytes = when (classify(path)) {
            PathKind.CONTENT_URI -> recordedSizeBytes
            PathKind.FILE_URI, PathKind.PLAIN_PATH -> fileOf(path)?.length() ?: recordedSizeBytes
        }
        return OpenedMedia(stream, sizeBytes)
    }

    // ── Existence and deletion ──

    /**
     * Whether the backing media exists: provider round-trip for content
     * URIs, disk check for file URIs and plain paths.
     */
    fun exists(path: String): Boolean {
        return try {
            when (classify(path)) {
                PathKind.CONTENT_URI -> {
                    val resolver = contentResolver ?: return false
                    resolver.openInputStream(Uri.parse(path))?.use { true } ?: false
                }
                PathKind.FILE_URI, PathKind.PLAIN_PATH -> fileOf(path)?.exists() == true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Deletes the backing media. A missing file counts as deleted (the goal
     * is "gone"); a failed provider/file delete does not. Content URIs go
     * through the resolver, `file://` URIs through their decoded path, plain
     * paths through the file directly.
     */
    fun delete(path: String): Boolean {
        return try {
            when (classify(path)) {
                PathKind.CONTENT_URI -> {
                    val resolver = contentResolver ?: return false
                    resolver.delete(Uri.parse(path), null, null) > 0
                }
                PathKind.FILE_URI, PathKind.PLAIN_PATH ->
                    fileOf(path)?.let { !it.exists() || it.delete() } == true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** A stream plus the size a consumer should report for it. */
    data class OpenedMedia(val stream: InputStream, val sizeBytes: Long)

    companion object {
        private const val FILE_SCHEME = "file://"
    }
}
