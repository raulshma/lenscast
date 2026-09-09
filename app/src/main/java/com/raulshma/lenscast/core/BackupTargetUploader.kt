package com.raulshma.lenscast.core

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * The one upload seam the BackupWorker drives per capture. Implementations
 * (WebDavBackupTarget, TelegramUploader) keep it to transport I/O only —
 * WorkManager constraints, retries, backoff, and the Wi-Fi-only gate stay
 * the worker's, unchanged across targets.
 */
interface BackupTargetUploader {

    /** True when the bytes landed; false means the worker should let WorkManager retry. */
    suspend fun upload(source: BackupUploadSource, remoteName: String): Boolean
}

/**
 * The two source shapes the worker already handles, mirrored as data: a
 * captured file on disk, or a MediaStore `content://` recording plus the
 * resolver that opens it. [remoteName] is always the file's display name.
 */
sealed interface BackupUploadSource {
    data class FileSource(val file: File) : BackupUploadSource
    data class ContentSource(val uri: Uri, val resolver: ContentResolver) : BackupUploadSource
}

/**
 * The one size-probe-and-open ladder every uploader needs for framing its
 * fixed-length POST/PUT: best-effort byte size (−1 when unresolvable) plus
 * the opened stream, for both source shapes. Null when the source cannot be
 * opened at all — a revoked MediaStore grant, a deleted recording. One home,
 * so the transports cannot drift on how they measure and read a capture.
 */
fun BackupUploadSource.openWithSize(): Pair<Long, InputStream>? = when (this) {
    is BackupUploadSource.FileSource -> file.length() to file.inputStream()
    is BackupUploadSource.ContentSource -> {
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val input = resolver.openInputStream(uri) ?: return null
        size to input
    }
}
