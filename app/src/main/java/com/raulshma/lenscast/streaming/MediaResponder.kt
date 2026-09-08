package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.capture.PhotoCaptureManager
import com.raulshma.lenscast.streaming.HttpResult.ResponseBody.Bytes
import com.raulshma.lenscast.streaming.HttpResult.ResponseBody.Stream
import com.raulshma.lenscast.streaming.hls.HlsManager
import com.raulshma.lenscast.streaming.hls.HlsSegmentSource
import com.raulshma.lenscast.streaming.web.GalleryWebHandler
import kotlinx.coroutines.runBlocking
import java.io.InputStream

/**
 * The media responder: gallery files (with thumbnails and video ranges),
 * snapshots (low-res frame vs high-res capture), and the PCM audio stream.
 * Answers as [HttpResult] data; the [StreamingServer] module translates the
 * value onto responses. [enabled] arrives per call so the pump stays the
 * single owner of the flag.
 */
class MediaResponder(
    private val gallery: GalleryWebHandler,
    private val capture: PhotoCaptureManager,
    private val audioStreamingManager: AudioStreamingManager,
    private val hlsSegments: HlsSegmentSource = HlsManager,
) {

    fun serveMediaFile(
        uri: String,
        hasDownloadParam: Boolean,
        rangeHeader: String?,
    ): HttpResult {
        val path = uri.removePrefix("/api/media/")
        if (path.isEmpty()) {
            return HttpResult.jsonError(400, "Missing media ID")
        }

        // Handle /api/media/{id}/thumbnail
        if (path.endsWith("/thumbnail")) {
            val id = path.removeSuffix("/thumbnail")
            val thumbnailBytes = gallery.resolveVideoThumbnail(id)
            if (thumbnailBytes != null) {
                return HttpResult(
                    statusCode = 200,
                    mimeType = "image/jpeg",
                    body = Bytes(thumbnailBytes),
                    headers = mapOf("Cache-Control" to "public, max-age=3600"),
                )
            }
            // Fallback: try to serve the media file itself (for photos)
            val resolved = gallery.resolveMediaFile(id)
            if (resolved != null) {
                return HttpResult(
                    statusCode = 200,
                    mimeType = resolved.mimeType,
                    body = Stream(resolved.stream, contentLength = null),
                )
            }
            return HttpResult.jsonError(404, "Thumbnail not available")
        }

        val resolved = gallery.resolveMediaFile(path)
            ?: return HttpResult.jsonError(404, "Media not found")

        // For video files, support HTTP Range requests for proper playback
        if (resolved.mimeType.startsWith("video/") && !hasDownloadParam) {
            return serveVideoWithRange(
                resolved.stream,
                resolved.mimeType,
                resolved.fileSizeBytes,
                rangeHeader,
            )
        }

        val headers = if (hasDownloadParam) {
            mapOf("Content-Disposition" to "attachment")
        } else {
            emptyMap()
        }
        return HttpResult(
            statusCode = 200,
            mimeType = resolved.mimeType,
            body = Stream(resolved.stream, contentLength = null),
            headers = headers,
        )
    }

    fun serveSnapshot(query: String?, latestFrame: ByteArray?, enabled: Boolean): HttpResult {
        if (!enabled) {
            return HttpResult.streamingDisabled()
        }

        val options = parseSnapshotQuery(query)

        if (options.highRes) {
            // The single place the transport blocks on a capture: this
            // dedicated server thread awaits the suspend capture instead of
            // every caller runBlocking-ing its own hop.
            val result = runBlocking { capture.captureSnapshot(options.saveToDisk) }
            return when (result) {
                is PhotoCaptureManager.SnapshotResult.Success -> HttpResult(
                    statusCode = 200,
                    mimeType = "image/jpeg",
                    body = Bytes(result.data),
                    headers = HttpResult.NO_STORE_HEADERS + (
                        result.savedPath?.let { path ->
                            mapOf("X-Saved-Path" to path)
                        } ?: emptyMap()
                        ),
                )
                is PhotoCaptureManager.SnapshotResult.Error -> HttpResult.jsonError(
                    500,
                    result.message,
                )
            }
        }

        val jpeg = latestFrame
        return if (jpeg != null) {
            HttpResult(
                statusCode = 200,
                mimeType = "image/jpeg",
                body = Bytes(jpeg),
                headers = HttpResult.NO_STORE_HEADERS,
            )
        } else {
            HttpResult.plainText(404, "No frame available")
        }
    }

    fun serveHlsPlaylist(enabled: Boolean): HttpResult {
        if (!enabled) return HttpResult.streamingDisabled()
        if (!hlsSegments.hasSegments()) {
            return HttpResult.plainText(503, "HLS starting — try again in a few seconds")
        }
        return HttpResult(
            statusCode = 200,
            mimeType = "application/vnd.apple.mpegurl",
            body = Bytes(
                hlsSegments.playlist().toByteArray(Charsets.UTF_8)
            ),
            headers = HttpResult.NO_STORE_HEADERS,
        )
    }

    fun serveHlsSegment(name: String, enabled: Boolean): HttpResult {
        if (!enabled) return HttpResult.streamingDisabled()
        val bytes = hlsSegments.segment(name)
            ?: return HttpResult.plainText(404, "Segment not found")
        return HttpResult(
            statusCode = 200,
            mimeType = "video/mp2t",
            body = Bytes(bytes),
            headers = mapOf("Cache-Control" to "public, max-age=30"),
        )
    }

    fun serveAudio(enabled: Boolean): HttpResult {
        if (!enabled) {
            return HttpResult.streamingDisabled()
        }

        val audioStream = audioStreamingManager.openStream()
        return if (audioStream != null) {
            HttpResult(
                statusCode = 200,
                mimeType = "application/octet-stream",
                body = Stream(audioStream, contentLength = null),
                headers = HttpResult.NO_STORE_HEADERS + mapOf(
                    "X-Accel-Buffering" to "no",
                    "X-Audio-Format" to "pcm_s16le",
                    "X-Audio-Sample-Rate" to "${audioStreamingManager.getSampleRateHz()}",
                    "X-Audio-Channels" to "${audioStreamingManager.getChannelCount()}",
                ),
            )
        } else {
            return HttpResult.plainText(503, "Audio stream not available")
        }
    }

    private fun serveVideoWithRange(
        inputStream: InputStream,
        mimeType: String,
        totalSize: Long,
        rangeHeader: String?,
    ): HttpResult {
        val range = resolveRange(rangeHeader, totalSize)
        if (range == null) {
            // No range requested – serve full content
            return HttpResult(
                statusCode = 200,
                mimeType = mimeType,
                body = Stream(inputStream, totalSize),
                headers = mapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Length" to totalSize.toString(),
                ),
            )
        }

        var skipped = 0L
        while (skipped < range.start) {
            val skippedThisTime = inputStream.skip(range.start - skipped)
            if (skippedThisTime <= 0L) break
            skipped += skippedThisTime
        }

        return HttpResult(
            statusCode = 206,
            mimeType = mimeType,
            body = Stream(inputStream, range.end - range.start + 1),
            headers = mapOf(
                "Content-Range" to "bytes ${range.start}-${range.end}/$totalSize",
                "Accept-Ranges" to "bytes",
                "Content-Length" to (range.end - range.start + 1).toString(),
            ),
        )
    }

    data class ResolvedRange(val start: Long, val end: Long)

    data class SnapshotOptions(val highRes: Boolean, val saveToDisk: Boolean)

    companion object {
        private const val MAX_CHUNK_BYTES = 2 * 1024 * 1024L

        /**
         * The `bytes=start-end` decision. Null means "serve full content":
         * no header, a foreign unit, or a spec without a dash.
         */
        internal fun resolveRange(rangeHeader: String?, totalSize: Long): ResolvedRange? {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                val dashIdx = rangeSpec.indexOf('-')
                if (dashIdx >= 0) {
                    val startStr = rangeSpec.substring(0, dashIdx).trim()
                    val endStr = rangeSpec.substring(dashIdx + 1).trim()
                    val start = if (startStr.isNotEmpty()) startStr.toLongOrNull() ?: 0L else 0L
                    val end = if (endStr.isNotEmpty()) {
                        (endStr.toLongOrNull() ?: (totalSize - 1)).coerceAtMost(totalSize - 1)
                    } else {
                        // Limit chunk size to 2MB to avoid excessive memory use
                        (start + MAX_CHUNK_BYTES - 1).coerceAtMost(totalSize - 1)
                    }
                    return ResolvedRange(start, end)
                }
            }
            return null
        }

        internal fun parseSnapshotQuery(query: String?): SnapshotOptions {
            val params = query ?: ""
            return SnapshotOptions(
                highRes = params.contains("highres=1") || params.contains("high_res=1"),
                saveToDisk = params.contains("save=1") || params.contains("save_to_disk=1"),
            )
        }
    }
}
