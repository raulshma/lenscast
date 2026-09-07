package com.raulshma.lenscast.streaming.web

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.raulshma.lenscast.capture.model.CaptureMediaFormat
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.core.AppJson
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

    fun getGallery(type: String?, page: Int = 0, pageSize: Int = 0): String {
        val galleryPage = GalleryPage.of(captureHistoryStore.history.value, type, page, pageSize)

        val items = galleryPage.items.map { entry ->
            val isVideo = entry.type == CaptureType.VIDEO
            GalleryItemDto(
                id = entry.id,
                type = entry.type.name,
                fileName = entry.fileName,
                timestamp = entry.timestamp,
                fileSizeBytes = entry.fileSizeBytes,
                durationMs = entry.durationMs,
                thumbnailUrl = if (isVideo) "/api/media/${entry.id}/thumbnail" else "/api/media/${entry.id}",
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
        val entry = history.find { it.id == id } ?: return null
        val mimeType = CaptureMediaFormat.mimeFor(entry.type)
        return try {
            val uri = Uri.parse(entry.filePath)
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                ResolvedMedia(inputStream, mimeType, entry.fileSizeBytes)
            } else {
                fileFallback(entry.filePath, mimeType)
            }
        } catch (_: Exception) {
            fileFallback(entry.filePath, mimeType)
        }
    }

    private fun fileFallback(filePath: String, mimeType: String): ResolvedMedia? {
        return try {
            val file = File(filePath)
            if (file.exists()) ResolvedMedia(file.inputStream(), mimeType, file.length()) else null
        } catch (_: Exception) {
            null
        }
    }

    fun resolveVideoThumbnail(id: String): ByteArray? {
        val history = captureHistoryStore.history.value
        val entry = history.find { it.id == id } ?: return null
        if (entry.type != CaptureType.VIDEO) {
            return null
        }
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                val uri = Uri.parse(entry.filePath)
                if (CaptureMediaFormat.isContentUri(entry.filePath)) {
                    retriever.setDataSource(context, uri)
                } else {
                    val file = File(entry.filePath)
                    if (file.exists()) {
                        retriever.setDataSource(file.absolutePath)
                    } else {
                        retriever.setDataSource(context, uri)
                    }
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

    companion object {
        private const val TAG = "GalleryWebHandler"
    }
}
