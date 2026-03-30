package com.raulshma.lenscast.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class CaptureHistoryStore(private val context: Context) {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val listType = Types.newParameterizedType(
        MutableList::class.java, CaptureHistory::class.java
    )
    private val adapter = moshi.adapter<List<CaptureHistory>>(listType)

    private val historyFile = File(context.filesDir, "capture_history.json")
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CaptureHistoryIO").apply { isDaemon = true }
    }

    private val _history = MutableStateFlow<List<CaptureHistory>>(emptyList())
    val history: StateFlow<List<CaptureHistory>> = _history.asStateFlow()

    init {
        load()
        refreshFromMediaStore()
    }

    private fun load() {
        try {
            if (historyFile.exists()) {
                val json = historyFile.readText()
                val items = adapter.fromJson(json) ?: emptyList()
                _history.value = items
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load capture history", e)
            _history.value = emptyList()
        }
    }

    private fun save() {
        val snapshot = _history.value
        ioExecutor.execute {
            try {
                val json = adapter.toJson(snapshot)
                historyFile.writeText(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save capture history", e)
            }
        }
    }

    fun add(entry: CaptureHistory) {
        val existingIndex = _history.value.indexOfFirst {
            normalizePath(it.filePath) == normalizePath(entry.filePath)
        }
        val current = _history.value.toMutableList()

        if (existingIndex >= 0) {
            current[existingIndex] = current[existingIndex].copy(
                fileName = entry.fileName,
                timestamp = maxOf(current[existingIndex].timestamp, entry.timestamp),
                fileSizeBytes = entry.fileSizeBytes.takeIf { it > 0 } ?: current[existingIndex].fileSizeBytes,
                durationMs = entry.durationMs.takeIf { it > 0 } ?: current[existingIndex].durationMs,
            )
        } else {
            current.add(0, entry)
        }

        _history.value = current.sortedByDescending { it.timestamp }
        save()
    }

    fun remove(id: String) {
        val current = _history.value.toMutableList()
        current.removeAll { it.id == id }
        _history.value = current
        save()
    }

    fun clear() {
        _history.value = emptyList()
        save()
    }

    fun refreshFromMediaStore() {
        ioExecutor.execute {
            try {
                val merged = mergeWithMediaStore(_history.value)
                if (merged != _history.value) {
                    _history.value = merged
                    save()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh capture history from MediaStore", e)
            }
        }
    }

    fun deleteMedia(id: String) {
        val entry = _history.value.find { it.id == id } ?: return
        val deleted = deleteBackingMedia(entry.filePath)
        if (deleted || !mediaExists(entry.filePath)) {
            remove(id)
        } else {
            Log.w(TAG, "Failed to delete media for history entry ${entry.filePath}")
        }
    }

    fun deleteMediaBatch(ids: List<String>): List<String> {
        val deleted = mutableListOf<String>()
        for (id in ids) {
            val entry = _history.value.find { it.id == id } ?: continue
            val ok = deleteBackingMedia(entry.filePath)
            if (ok || !mediaExists(entry.filePath)) {
                deleted.add(id)
            }
        }
        if (deleted.isNotEmpty()) {
            val idSet = deleted.toSet()
            _history.value = _history.value.filterNot { it.id in idSet }
            save()
        }
        return deleted
    }

    suspend fun deleteOlderThan(beforeTimestamp: Long): Int = withContext(Dispatchers.IO) {
        val current = _history.value
        val remaining = current.filter { it.timestamp >= beforeTimestamp }
        val removed = current.size - remaining.size
        _history.value = remaining
        save()
        removed
    }

    fun createPhotoEntry(
        fileName: String,
        filePath: String,
        fileSizeBytes: Long,
    ): CaptureHistory {
        return CaptureHistory(
            id = UUID.randomUUID().toString(),
            type = CaptureType.PHOTO,
            fileName = fileName,
            filePath = filePath,
            timestamp = System.currentTimeMillis(),
            fileSizeBytes = fileSizeBytes,
        )
    }

    fun createVideoEntry(
        fileName: String,
        filePath: String,
        fileSizeBytes: Long,
        durationMs: Long,
    ): CaptureHistory {
        return CaptureHistory(
            id = UUID.randomUUID().toString(),
            type = CaptureType.VIDEO,
            fileName = fileName,
            filePath = filePath,
            timestamp = System.currentTimeMillis(),
            fileSizeBytes = fileSizeBytes,
            durationMs = durationMs,
        )
    }

    private fun mergeWithMediaStore(current: List<CaptureHistory>): List<CaptureHistory> {
        val deviceMedia = queryCapturedMedia()
        if (deviceMedia.isEmpty()) {
            return current.sortedByDescending { it.timestamp }
        }

        val deviceByPath = deviceMedia.associateBy { normalizePath(it.filePath) }
        val merged = current.map { existing ->
            val deviceMatch = deviceByPath[normalizePath(existing.filePath)]
            if (deviceMatch == null) {
                existing
            } else {
                existing.copy(
                    fileName = deviceMatch.fileName.ifBlank { existing.fileName },
                    timestamp = maxOf(existing.timestamp, deviceMatch.timestamp),
                    fileSizeBytes = deviceMatch.fileSizeBytes.takeIf { it > 0 } ?: existing.fileSizeBytes,
                    durationMs = deviceMatch.durationMs.takeIf { it > 0 } ?: existing.durationMs,
                )
            }
        }.toMutableList()

        val existingPaths = merged.mapTo(mutableSetOf()) { normalizePath(it.filePath) }
        deviceMedia.forEach { media ->
            if (existingPaths.add(normalizePath(media.filePath))) {
                merged.add(media.copy(id = UUID.randomUUID().toString()))
            }
        }

        return merged.sortedByDescending { it.timestamp }
    }

    private fun queryCapturedMedia(): List<CaptureHistory> {
        val photos = queryPhotos()
        val videos = queryVideos()
        return (photos + videos).sortedByDescending { it.timestamp }
    }

    private fun queryPhotos(): List<CaptureHistory> {
        return queryMediaCollection(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATA,
            ),
            folderPath = "${Environment.DIRECTORY_PICTURES}/LensCast/",
            type = CaptureType.PHOTO,
        )
    }

    private fun queryVideos(): List<CaptureHistory> {
        return queryMediaCollection(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATA,
                MediaStore.Video.Media.DURATION,
            ),
            folderPath = "${Environment.DIRECTORY_MOVIES}/LensCast/",
            type = CaptureType.VIDEO,
        )
    }

    private fun queryMediaCollection(
        collection: Uri,
        projection: Array<String>,
        folderPath: String,
        type: CaptureType,
    ): List<CaptureHistory> {
        val entries = mutableListOf<CaptureHistory>()
        val (selection, selectionArgs) = mediaSelection(folderPath)

        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val dateTakenIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val durationIndex = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val displayName = cursor.getString(nameIndex).orEmpty()
                val fileSize = if (!cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                val dateTakenMs = if (dateTakenIndex >= 0 && !cursor.isNull(dateTakenIndex)) {
                    cursor.getLong(dateTakenIndex)
                } else {
                    0L
                }
                val dateAddedMs = if (!cursor.isNull(dateAddedIndex)) {
                    cursor.getLong(dateAddedIndex) * 1000L
                } else {
                    0L
                }
                val durationMs = if (durationIndex >= 0 && !cursor.isNull(durationIndex)) {
                    cursor.getLong(durationIndex)
                } else {
                    0L
                }

                entries.add(
                    CaptureHistory(
                        id = "",
                        type = type,
                        fileName = displayName,
                        filePath = ContentUris.withAppendedId(collection, id).toString(),
                        timestamp = maxOf(dateTakenMs, dateAddedMs),
                        fileSizeBytes = fileSize,
                        durationMs = durationMs,
                    )
                )
            }
        }

        return entries
    }

    private fun mediaSelection(folderPath: String): Pair<String, Array<String>> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Pair(
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$folderPath%")
            )
        } else {
            @Suppress("DEPRECATION")
            Pair(
                "${MediaStore.MediaColumns.DATA} LIKE ?",
                arrayOf("%/$folderPath%")
            )
        }
    }

    /**
     * Deletes the backing media file using proper Uri parsing.
     * Uses Uri.scheme to distinguish content providers from file URIs,
     * rather than brittle string prefix matching.
     */
    private fun deleteBackingMedia(filePath: String): Boolean {
        return runCatching {
            val uri = Uri.parse(filePath)
            when (uri.scheme) {
                "content" -> {
                    context.contentResolver.delete(uri, null, null) > 0
                }
                "file" -> {
                    File(uri.path.orEmpty()).delete()
                }
                else -> {
                    // No scheme — treat as a raw file path
                    val file = File(filePath)
                    !file.exists() || file.delete()
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to delete backing media $filePath", error)
            false
        }
    }

    /**
     * Checks whether the backing media file exists using proper Uri parsing.
     */
    private fun mediaExists(filePath: String): Boolean {
        return runCatching {
            val uri = Uri.parse(filePath)
            when (uri.scheme) {
                "content" -> {
                    context.contentResolver.openInputStream(uri)?.use { true } ?: false
                }
                "file" -> {
                    File(uri.path.orEmpty()).exists()
                }
                else -> File(filePath).exists()
            }
        }.getOrDefault(false)
    }

    private fun normalizePath(filePath: String): String = filePath.trim()

    companion object {
        private const val TAG = "CaptureHistoryStore"
    }
}
