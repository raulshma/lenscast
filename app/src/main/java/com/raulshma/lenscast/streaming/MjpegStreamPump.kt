package com.raulshma.lenscast.streaming

import android.util.Log
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.streaming.HttpResult.ResponseBody.Stream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * The MJPEG stream pump: frame state, client bookkeeping, the enabled
 * flag, and the per-client multipart stream. Answers [openStream] as an
 * [HttpResult] — null-body disabled case included — so the
 * [StreamingServer] module only translates the value onto a response.
 */
class MjpegStreamPump(
    private val networkQualityMonitor: NetworkQualityMonitor,
    private val boundary: String,
) {

    private val clientCount = AtomicInteger(0)
    private val clientCounter = AtomicInteger(0)
    @Volatile private var latestJpeg: ByteArray? = null
    private val frameLock = Object()
    private var latestFrameVersion = 0L
    @Volatile private var enabled = true

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun isEnabled(): Boolean = enabled

    fun updateFrame(jpegData: ByteArray) {
        synchronized(frameLock) {
            latestJpeg = jpegData
            latestFrameVersion++
            frameLock.notifyAll()
        }
    }

    fun latestFrame(): ByteArray? = latestJpeg

    fun getClientCount(): Int = clientCount.get()

    fun openStream(): HttpResult {
        if (!enabled) {
            return HttpResult.streamingDisabled()
        }

        val clientNum = clientCount.incrementAndGet()
        val clientId = "mjpeg_${clientCounter.incrementAndGet()}"
        Log.d(TAG, "Client connected: $clientId. Total: $clientNum")

        networkQualityMonitor.registerClient(clientId)

        return HttpResult(
            statusCode = 200,
            mimeType = "multipart/x-mixed-replace; boundary=$boundary",
            body = Stream(MjpegInputStream(clientId), contentLength = null),
            headers = HttpResult.NO_STORE_HEADERS + mapOf(
                "X-Accel-Buffering" to "no",
            ),
        )
    }

    private inner class MjpegInputStream(val clientId: String) : InputStream() {
        private var currentFrame: ByteArray? = null
        private var currentFrameVersion = -1L
        private var frameOffset = 0
        private var headerBytes = ByteArray(0)
        private var headerOffset = 0
        private var footerOffset = 0
        private var isFirstPart = true
        @Volatile
        private var closed = false
        private var frameSendStartTime = 0L
        private var currentFrameTotalBytes = 0

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n <= 0) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
            if (len == 0) return 0

            var totalRead = 0
            while (totalRead < len) {
                if (!ensureCurrentPart()) {
                    return if (totalRead > 0) totalRead else -1
                }

                val writtenHeader = copyChunk(
                    source = headerBytes,
                    sourceOffset = headerOffset,
                    target = b,
                    targetOffset = off + totalRead,
                    maxLength = len - totalRead,
                )
                headerOffset += writtenHeader
                totalRead += writtenHeader
                if (totalRead == len) break

                val frame = currentFrame ?: continue
                val writtenFrame = copyChunk(
                    source = frame,
                    sourceOffset = frameOffset,
                    target = b,
                    targetOffset = off + totalRead,
                    maxLength = len - totalRead,
                )
                frameOffset += writtenFrame
                totalRead += writtenFrame
                if (totalRead == len) break

                val writtenFooter = copyChunk(
                    source = MJPEG_FOOTER,
                    sourceOffset = footerOffset,
                    target = b,
                    targetOffset = off + totalRead,
                    maxLength = len - totalRead,
                )
                footerOffset += writtenFooter
                totalRead += writtenFooter

                if (headerOffset >= headerBytes.size &&
                    frameOffset >= currentFrame!!.size &&
                    footerOffset >= MJPEG_FOOTER.size
                ) {
                    val sendDuration = System.currentTimeMillis() - frameSendStartTime
                    networkQualityMonitor.recordFrameSent(
                        clientId = clientId,
                        frameSizeBytes = currentFrameTotalBytes,
                        sendDurationMs = sendDuration,
                    )
                    currentFrame = null
                }
            }

            return totalRead
        }

        private fun ensureCurrentPart(): Boolean {
            if (closed) return false
            val frame = currentFrame
            if (frame != null && (
                headerOffset < headerBytes.size ||
                    frameOffset < frame.size ||
                    footerOffset < MJPEG_FOOTER.size
                )
            ) {
                return true
            }

            synchronized(frameLock) {
                while (!closed) {
                    val nextFrame = latestJpeg
                    if (nextFrame != null && latestFrameVersion != currentFrameVersion) {
                        currentFrame = nextFrame
                        currentFrameVersion = latestFrameVersion
                        frameOffset = 0
                        footerOffset = 0
                        headerOffset = 0
                        headerBytes = buildPartHeader(boundary, isFirstPart, nextFrame.size)
                        isFirstPart = false
                        currentFrameTotalBytes = nextFrame.size + headerBytes.size + MJPEG_FOOTER.size
                        frameSendStartTime = System.currentTimeMillis()
                        return true
                    }
                    frameLock.wait(250)
                }
            }

            return false
        }

        override fun close() {
            closed = true
            synchronized(frameLock) { frameLock.notifyAll() }
            networkQualityMonitor.unregisterClient(clientId)
            val num = clientCount.decrementAndGet()
            Log.d(TAG, "Client disconnected: $clientId. Total: $num")
        }
    }

    companion object {
        private const val TAG = "MjpegStreamPump"
        private val MJPEG_FOOTER = "\r\n".toByteArray()

        /**
         * One multipart part header: first-part vs subsequent prefix, the
         * decimal frame size, and the blank-line suffix.
         */
        internal fun buildPartHeader(
            boundary: String,
            isFirstPart: Boolean,
            frameSize: Int,
        ): ByteArray {
            val prefix = if (isFirstPart) {
                "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: "
            } else {
                "\r\n--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: "
            }
            return (prefix + frameSize.toString() + "\r\n\r\n").toByteArray()
        }

        internal fun copyChunk(
            source: ByteArray,
            sourceOffset: Int,
            target: ByteArray,
            targetOffset: Int,
            maxLength: Int,
        ): Int {
            if (sourceOffset >= source.size || maxLength <= 0) return 0
            val copyLength = minOf(source.size - sourceOffset, maxLength)
            System.arraycopy(source, sourceOffset, target, targetOffset, copyLength)
            return copyLength
        }
    }
}
