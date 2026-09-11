package com.raulshma.lenscast.capture

import android.content.ContentResolver
import android.net.Uri
import com.raulshma.lenscast.capture.model.CaptureMediaFormat
import com.raulshma.lenscast.core.MediaCrypto
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream

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
 *
 * Decrypt is transparent: [openStream] (alias [openDecryptedStream]) sniffs
 * the file's `LCE1` header ([MediaCrypto]) and wraps encrypted media in a
 * GCM-decrypting stream, while plaintext passes through byte-identical — the
 * migration discipline that lets an opt-in encryption toggle mix old and new
 * files in one library. Plaintext snippets already consumed by the sniff are
 * pushed back in front of the stream, so the caller sees the whole bytes.
 * Without a [MediaCrypto.KeyProvider] an encrypted file answers null (fail
 * closed: ciphertext is never served as media).
 */
class CaptureMediaResolver(
    private val contentResolver: ContentResolver? = null,
    private val keyProvider: MediaCrypto.KeyProvider? = null,
) {

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
     * Opens the media at [path], decrypted: through the ContentResolver for
     * content URIs, straight from disk for file URIs and plain paths. Media
     * carrying the encrypted-at-rest header ([isEncryptedAtRest]) is wrapped
     * in the GCM stream; anything else is passed through untouched. Null
     * when the media cannot be opened (missing file, provider rejection,
     * IO error) or is encrypted with no [keyProvider] wired.
     */
    fun openStream(path: String): InputStream? = openDecryptedStream(path)

    /** The explicit name for [openStream]'s decrypting contract. */
    fun openDecryptedStream(path: String): InputStream? {
        val raw = openRawStream(path) ?: return null
        return try {
            decryptWrap(path, raw)
        } catch (_: Exception) {
            runCatching { raw.close() }
            null
        }
    }

    /**
     * Whether the bytes at rest behind [path] carry the encrypted-media
     * header. A four-byte sniff; false for unreadable media (and for
     * plaintext), so callers treat "false" as "serve as-is".
     */
    fun isEncryptedAtRest(path: String): Boolean = try {
        openRawStream(path)?.use { input ->
            val magic = ByteArray(MediaCrypto.MAGIC_BYTES.size)
            readFully(input, magic) == magic.size && MediaCrypto.isEncryptedHeader(magic)
        } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * Opens the media plus the size to report for it: content URIs report the
     * caller's recorded size (MediaStore thumbnails/sizes may not be known at
     * open time), file-backed paths report the actual file length. When the
     * media is encrypted at rest, the stored size is the ciphertext length
     * and the reported size is the plaintext the stream will actually yield.
     */
    fun openMedia(path: String, recordedSizeBytes: Long): OpenedMedia? {
        val stream = openDecryptedStream(path) ?: return null
        val storedSize = when (classify(path)) {
            PathKind.CONTENT_URI -> recordedSizeBytes
            PathKind.FILE_URI, PathKind.PLAIN_PATH -> fileOf(path)?.length() ?: recordedSizeBytes
        }
        val sizeBytes = if (isEncryptedAtRest(path)) {
            MediaCrypto.plaintextSize(storedSize)
        } else {
            storedSize
        }
        return OpenedMedia(stream, sizeBytes)
    }

    private fun openRawStream(path: String): InputStream? = try {
        when (classify(path)) {
            PathKind.CONTENT_URI -> contentResolver?.openInputStream(Uri.parse(path))
            PathKind.FILE_URI, PathKind.PLAIN_PATH ->
                fileOf(path)?.takeIf { it.exists() }?.inputStream()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The sniff-and-wrap half of the transparent decrypt: reads the header
     * bytes off the raw stream, hands encrypted media to [MediaCrypto]
     * (throwing when no key provider is wired — ciphertext is never served),
     * and re-attaches the consumed bytes to plaintext media.
     */
    private fun decryptWrap(path: String, raw: InputStream): InputStream {
        val header = ByteArray(MediaCrypto.HEADER_SIZE_BYTES)
        val read = readFully(raw, header)
        if (!MediaCrypto.isEncryptedHeader(header)) {
            // Plaintext (or too small to be ours): the sniffed bytes are real
            // media bytes — put them back in front of the remainder.
            return SequenceInputStream(ByteArrayInputStream(header, 0, read), raw)
        }
        val provider = keyProvider
            ?: throw IllegalStateException("Encrypted media but no key provider wired for $path")
        return MediaCrypto.decryptFrom(provider.getOrCreateKey(), header.copyOf(read), raw)
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

        /** The bounded header read the sniff and wrap share. */
        internal fun readFully(input: InputStream, target: ByteArray): Int {
            var filled = 0
            while (filled < target.size) {
                val read = input.read(target, filled, target.size - filled)
                if (read < 0) break
                filled += read
            }
            return filled
        }
    }
}
