package com.raulshma.lenscast.streaming.web

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.raulshma.lenscast.capture.CaptureMediaResolver
import com.raulshma.lenscast.capture.DetectionEvent
import com.raulshma.lenscast.capture.model.CaptureMediaFormat
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.JpegDownscaler
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.streaming.model.BatchDeleteRequest
import com.raulshma.lenscast.streaming.model.BatchDeleteResponse
import com.raulshma.lenscast.streaming.model.GalleryItemDto
import com.raulshma.lenscast.streaming.model.GalleryResponseDto
import com.raulshma.lenscast.streaming.model.SuccessResponse
import java.io.File
import java.io.InputStream

/**
 * /api/gallery and /api/media routes — gallery listing/deletion and media resolution
 * (file streams and video thumbnails) for the transport layer to serve.
 * Pagination is [GalleryPage]'s; this handler parses params and serializes.
 * It serves the current history snapshot as-is — capture-time and app-side
 * refreshes keep the store current.
 */
class GalleryWebHandler(
    private val context: Context,
    private val captureHistoryStore: CaptureHistoryStore,
) {

    private val galleryAdapter by lazy { AppJson.moshi.adapter(GalleryResponseDto::class.java) }
    private val batchDeleteRequestAdapter by lazy { AppJson.moshi.adapter(BatchDeleteRequest::class.java) }
    private val batchDeleteResponseAdapter by lazy { AppJson.moshi.adapter(BatchDeleteResponse::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(SuccessResponse::class.java) }

    // Keyed with the app's media key so /api/media serves encrypted captures
    // transparently (the same decrypt-on-read every in-app consumer uses).
    private val mediaResolver = CaptureMediaResolver(
        context.contentResolver,
        (context.applicationContext as? com.raulshma.lenscast.MainApplication)?.mediaKeyProvider,
    )

    @Volatile
    private var placeholderThumbnail: ByteArray? = null

    fun getGallery(type: String?, page: Int = 0, pageSize: Int = 0, query: String? = null): String {
        val galleryPage = GalleryPage.of(captureHistoryStore.history.value, type, page, pageSize, query)

        val items = galleryPage.items.map { entry ->
            GalleryItemDto(
                id = entry.id,
                type = entry.type.name,
                fileName = entry.fileName,
                timestamp = entry.timestamp,
                fileSizeBytes = entry.fileSizeBytes,
                durationMs = entry.durationMs,
                favorite = entry.favorite,
                // Both types serve a downscaled grid thumbnail; photos point
                // `url` at the full-size route so the viewer can load the
                // original while the grid uses the thumbnail.
                thumbnailUrl = "/api/media/${entry.id}/thumbnail",
                url = "/api/media/${entry.id}",
                downloadUrl = "/api/media/${entry.id}?download=1",
            )
        }
        return galleryAdapter.toJson(
            GalleryResponseDto(
                items = items,
                total = galleryPage.total,
                page = page,
                pageSize = galleryPage.pageSize,
                hasMore = galleryPage.hasMore,
            )
        )
    }

    fun deleteMedia(id: String): String {
        val history = captureHistoryStore.history.value
        val entry = history.find { it.id == id }
        return if (entry == null) {
            ApiResponse.error(IllegalArgumentException("Media not found"))
        } else {
            captureHistoryStore.deleteMedia(id)
            successAdapter.toJson(SuccessResponse())
        }
    }

    fun batchDelete(body: String): String {
        val request = batchDeleteRequestAdapter.fromJson(body)
            ?: throw IllegalArgumentException("Invalid batch delete JSON")
        val deleted = captureHistoryStore.deleteAll(request.ids)
        return batchDeleteResponseAdapter.toJson(BatchDeleteResponse(deleted = deleted))
    }

    class ResolvedMedia(
        val stream: InputStream,
        val mimeType: String,
        val fileSizeBytes: Long,
    )

    fun resolveMediaFile(id: String): ResolvedMedia? {
        val history = captureHistoryStore.history.value
        // Two id spaces hit this route: the history UUID the gallery links
        // use, and the MediaStore numeric id a detection event's clip link
        // carries (`clipMediaId`). Either resolves to the same entry.
        val entry = history.find { it.id == id }
            ?: history.find { DetectionEvent.clipMediaIdFromContentUri(it.filePath)?.toString() == id }
            ?: return null
        val mimeType = CaptureMediaFormat.mimeFor(entry.type)
        // The one scheme ladder: content URIs open through the resolver and
        // report the history-recorded size; file-backed paths open from disk
        // and report the actual length.
        val opened = mediaResolver.openMedia(entry.filePath, entry.fileSizeBytes) ?: return null
        return ResolvedMedia(opened.stream, mimeType, opened.sizeBytes)
    }

    fun resolveVideoThumbnail(id: String): ByteArray? {
        val history = captureHistoryStore.history.value
        val entry = history.find { it.id == id } ?: return null
        if (entry.type != CaptureType.VIDEO) {
            return null
        }
        // Encrypted-at-rest videos have no frameable bytes for the retriever
        // (it needs real mp4 at rest; a decrypted stream cannot be handed to
        // it) — the documented trade-off: while encryption is on, video
        // thumbnails serve the generated filmstrip placeholder, on the web
        // dashboard exactly like the in-app grid. Playback still decrypts
        // through /api/media/{id}.
        if (mediaResolver.isEncryptedAtRest(entry.filePath)) {
            return placeholderThumbnail()
        }
        // One ladder for the retriever's data source: scheme'd paths go
        // through their Uri, an existing plain file through its path, a
        // missing file yields no thumbnail.
        val source = mediaResolver.displayModel(entry.filePath) ?: return null
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                when (source) {
                    is Uri -> retriever.setDataSource(context, source)
                    is File -> retriever.setDataSource(source.absolutePath)
                    else -> return null
                }
                val bitmap = retriever.getFrameAtTime(
                    1_000_000, // 1 second in microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: retriever.getFrameAtTime(0)
                if (bitmap != null) {
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream)
                    bitmap.recycle()
                    stream.toByteArray()
                } else {
                    null
                }
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate video thumbnail for $id", e)
            null
        }
    }

    /**
     * The photo thumbnail: the photo's bytes through the shared
     * [JpegDownscaler] ladder at [StreamDefaults.PHOTO_THUMBNAIL_MAX_PX] —
     * null for videos (the retriever owns those) and unreadable photos.
     */
    fun resolvePhotoThumbnail(id: String): ByteArray? {
        val history = captureHistoryStore.history.value
        val entry = history.find { it.id == id } ?: return null
        if (entry.type != CaptureType.PHOTO) {
            return null
        }
        val opened = mediaResolver.openMedia(entry.filePath, entry.fileSizeBytes) ?: return null
        val bytes = try {
            opened.stream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read photo for thumbnail $id", e)
            return null
        }
        return JpegDownscaler.downscale(
            jpeg = bytes,
            targetMaxPx = StreamDefaults.PHOTO_THUMBNAIL_MAX_PX,
            quality = StreamDefaults.PHOTO_THUMBNAIL_JPEG_QUALITY,
        )
    }

    /**
     * The generated filmstrip placeholder for encrypted-at-rest video
     * thumbnails: a dark frame with sprocket bars and a play glyph, rendered
     * once and reused. Served as a real JPEG so the dashboard's `<img>`/video
     * poster path renders it like any other thumbnail.
     */
    private fun placeholderThumbnail(): ByteArray {
        placeholderThumbnail?.let { return it }
        val width = StreamDefaults.PHOTO_THUMBNAIL_MAX_PX
        val height = width * 9 / 16
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(18, 18, 22))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(70, 255, 255, 255)
        }
        // The sprocket strip along the top and bottom edges.
        val hole = width / 24f
        val step = hole * 2.4f
        var x = step / 2f
        while (x + hole <= width) {
            canvas.drawRoundRect(
                x, height * 0.04f, x + hole, height * 0.04f + hole * 0.7f,
                hole * 0.2f, hole * 0.2f, paint,
            )
            canvas.drawRoundRect(
                x, height * 0.96f - hole * 0.7f, x + hole, height * 0.96f,
                hole * 0.2f, hole * 0.2f, paint,
            )
            x += step
        }
        // The centered play glyph.
        paint.color = android.graphics.Color.argb(160, 255, 255, 255)
        val cx = width / 2f
        val cy = height / 2f
        val r = height / 6f
        val triangle = android.graphics.Path().apply {
            moveTo(cx - r * 0.6f, cy - r)
            lineTo(cx - r * 0.6f, cy + r)
            lineTo(cx + r, cy)
            close()
        }
        canvas.drawPath(triangle, paint)
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream)
        bitmap.recycle()
        return stream.toByteArray().also { placeholderThumbnail = it }
    }

    companion object {
        private const val TAG = "GalleryWebHandler"
    }
}
