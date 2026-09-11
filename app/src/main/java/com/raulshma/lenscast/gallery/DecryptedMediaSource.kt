package com.raulshma.lenscast.gallery

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.raulshma.lenscast.capture.CaptureMediaResolver
import java.io.IOException
import java.io.InputStream

/**
 * The ExoPlayer seam for encrypted-at-rest video: a [DataSource] whose bytes
 * come from [CaptureMediaResolver.openDecryptedStream] — the same transparent
 * decrypt every other consumer uses. Player seeks become fresh opens whose
 * `position` is consumed by read-and-discard (GCM decrypts sequentially), so
 * a seek costs a full pass over the file; sequential AES-GCM is fast enough
 * that this stays invisible for capture-length clips, and it is the price of
 * never writing plaintext to disk. Placeholder thumbnails for encrypted
 * videos remain the viewer's job — this source only serves playback.
 */
class DecryptingDataSourceFactory(
    private val mediaResolver: CaptureMediaResolver,
    private val filePath: String,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = DecryptingDataSource(mediaResolver, filePath)
}

private class DecryptingDataSource(
    private val mediaResolver: CaptureMediaResolver,
    private val filePath: String,
) : BaseDataSource(false) {

    private var stream: InputStream? = null
    private var openedUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val input = mediaResolver.openDecryptedStream(filePath)
            ?: throw IOException("Cannot open decrypted media for $filePath")
        skipFully(input, dataSpec.position)
        stream = input
        openedUri = dataSpec.uri
        transferStarted(dataSpec)
        // Unbounded: the length is the decrypted stream's to declare (EOF).
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val input = stream ?: throw IOException("Read on closed decrypting source")
        val read = input.read(target, offset, length)
        if (read < 0) return C.RESULT_END_OF_INPUT
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        stream?.close()
        stream = null
    }

    private fun skipFully(input: InputStream, position: Long) {
        var remaining = position
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) throw IOException("Seek past end of decrypted media")
            remaining -= skipped
        }
    }
}
