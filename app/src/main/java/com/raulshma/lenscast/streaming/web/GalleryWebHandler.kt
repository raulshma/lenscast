package com.raulshma.lenscast.streaming.web

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.raulshma.lenscast.capture.model.CaptureType
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
 */
class GalleryWebHandler(
    private val context: Context,
    private val captureHistoryStore: CaptureHistoryStore,
) {

    private val galleryAdapter by lazy { WebJson.moshi.adapter(GalleryResponseDto::class.java) }
    private val batchDeleteRequestAdapter by lazy { WebJson.moshi.adapter(BatchDeleteRequest::class.java) }
    private val batchDeleteResponseAdapter by lazy { WebJson.moshi.adapter(BatchDeleteResponse::class.java) }
    private val successAdapter by lazy { WebJson.moshi.adapter(SuccessResponse::class.java) }

    fun getGallery(type: String?, page: Int = 0, pageSize: Int = 0): String {
        captureHistoryStore.refreshFromMediaStore()
        val history = captureHistoryStore.history.value
        val filtered = when (type?.uppercase()) {
            "PHOTO" -> history.filter { it.type == CaptureType.PHOTO }
            "VIDEO" -> history.filter { it.type == CaptureType.VIDEO }
            else -> history
        }

        val effectivePageSize = if (pageSize > 0) pageSize else DEFAULT_GALLERY_PAGE_SIZE
        val hasMore = if (effectivePageSize > 0) {
            page * effectivePageSize + effectivePageSize < filtered.size
        } else false

        val paged = if (effectivePageSize > 0 && page >= 0) {
            filtered.drop(page * effectivePageSize).take(effectivePageSize)
        } else {
            filtered
        }

        val items = paged.map { entry ->
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
            GalleryResponseDto(items = items, total = filtered.size, page = page, pageSize = effectivePageSize, hasMore = hasMore)
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
        val deleted = captureHistoryStore.deleteMediaBatch(request.ids)
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
        val mimeType = when (entry.type) {
            CaptureType.PHOTO -> "image/jpeg"
            CaptureType.VIDEO -> "video/mp4"
        }
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
                if (entry.filePath.startsWith("content://")) {
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
        private const val DEFAULT_GALLERY_PAGE_SIZE = 50
    }
}
