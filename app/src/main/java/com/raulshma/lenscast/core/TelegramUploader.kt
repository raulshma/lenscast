package com.raulshma.lenscast.core

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom

/**
 * Telegram backup target: Bot API `sendDocument` as one multipart POST —
 * the hosted fallback next to WebDAV, over the same raw
 * `HttpURLConnection` transport [WebDavUploader] uses (no new dependencies).
 *
 * [TelegramMultipart] is the pure byte layout, JVM-tested; the network I/O
 * is not (it is exercised against the real Bot API). The file part streams
 * straight from its source — recordings are far too big to hold in RAM.
 */
class TelegramUploader(
    private val botToken: String,
    private val chatId: String,
) : BackupTargetUploader {

    override suspend fun upload(source: BackupUploadSource, remoteName: String): Boolean {
        val sized = source.openWithSize() ?: return false
        val (size, input) = sized
        return try {
            if (size >= 0) {
                sendStreaming(remoteName, size, input)
            } else {
                // Size unresolvable — the fixed-length POST cannot be
                // framed, so fall back to memory. Rare, and the memory
                // path is tolerable exactly when the artifact is small.
                // The name is sanitized here too: it lands inside a quoted
                // Content-Disposition either way.
                post(sanitizeFileName(remoteName), input.use { it.readBytes() })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Telegram upload failed for $remoteName: ${e.message}")
            false
        } finally {
            // The source descriptor must close even when the POST fails
            // before any body byte is written (connect/timeout).
            runCatching { input.close() }
        }
    }

    /**
     * One streaming `sendDocument` POST: headers and the closing boundary
     * from memory, the file part copied through in chunks. [input] is
     * consumed but not closed — [upload] owns its lifetime.
     */
    private fun sendStreaming(remoteName: String, partSize: Long, input: InputStream): Boolean {
        val boundary = newBoundary()
        val head = TelegramMultipart.head(
            boundary = boundary,
            chatId = chatId,
            fileName = sanitizeFileName(remoteName),
            contentType = contentTypeFor(remoteName),
        )
        val tail = TelegramMultipart.tail(boundary)
        return postMultipart(boundary, head.size.toLong() + partSize + tail.size.toLong()) { output ->
            output.write(head)
            input.copyTo(output, COPY_BUFFER_BYTES)
            output.write(tail)
        }
    }

    /** The memory path for sources with no resolvable size — see [upload]. */
    private fun post(fileName: String, fileBytes: ByteArray): Boolean {
        val boundary = newBoundary()
        val body = TelegramMultipart.build(
            boundary = boundary,
            chatId = chatId,
            fileName = fileName,
            fileBytes = fileBytes,
            contentType = contentTypeFor(fileName),
        )
        return postMultipart(boundary, body.size.toLong()) { output -> output.write(body) }
    }

    /**
     * The one `sendDocument` POST: fixed-length streaming with the given body
     * size, the parts written by [writeBody], then the status-code verdict —
     * shared by the streaming and memory paths.
     */
    private fun postMultipart(
        boundary: String,
        bodyLength: Long,
        writeBody: (java.io.OutputStream) -> Unit,
    ): Boolean {
        val connection = (
            URI("https://api.telegram.org/bot$botToken/sendDocument").toURL().openConnection()
                as HttpURLConnection
            ).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setFixedLengthStreamingMode(bodyLength)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            connection.outputStream.use(writeBody)
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Telegram sendDocument answered HTTP $code")
            }
            code in 200..299
        } finally {
            connection.disconnect()
        }
    }

    /** A fresh `----LensCast` + hex boundary per upload; the builder is boundary-parameterized. */
    private fun newBoundary(): String {
        val bytes = ByteArray(BOUNDARY_RANDOM_BYTES).also { SecureRandom().nextBytes(it) }
        return "----LensCast" + bytes.toHexString()
    }

    internal fun contentTypeFor(name: String): String =
        com.raulshma.lenscast.capture.model.CaptureMediaFormat.contentTypeFor(name)

    companion object {
        private const val TAG = "TelegramUploader"

        /** Bot API uploads are slow; the spec calls for 60 s on both phases. */
        private const val TIMEOUT_MS = 60_000
        private const val BOUNDARY_RANDOM_BYTES = 12
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** A filename lands inside a quoted Content-Disposition header — strip what would break it. */
        internal fun sanitizeFileName(name: String): String =
            name.replace("\"", "").replace("\r", "").replace("\n", "").ifEmpty { "capture" }
    }
}

/**
 * The pure multipart byte layout for `sendDocument`: the `chat_id` field
 * first, then the `document` file part, then the final `--boundary--`
 * terminator — the exact order Telegram's Bot API expects. [head]/[tail]
 * frame the streamed variant; [build] assembles the whole body in memory.
 */
internal object TelegramMultipart {

    fun head(boundary: String, chatId: String, fileName: String, contentType: String): ByteArray =
        ByteArrayOutputStream().apply {
            write(
                (
                    "--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"chat_id\"\r\n" +
                        "\r\n" +
                        "$chatId\r\n"
                    ).toByteArray(Charsets.UTF_8),
            )
            write(
                (
                    "--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"document\"; filename=\"$fileName\"\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.UTF_8),
            )
        }.toByteArray()

    fun tail(boundary: String): ByteArray = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    fun build(
        boundary: String,
        chatId: String,
        fileName: String,
        fileBytes: ByteArray,
        contentType: String = "application/octet-stream",
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(head(boundary, chatId, fileName, contentType))
        out.write(fileBytes)
        out.write(tail(boundary))
        return out.toByteArray()
    }
}
