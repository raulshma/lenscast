package com.raulshma.lenscast.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.raulshma.lenscast.capture.CaptureMediaResolver
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureMediaFormat
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.core.AppJson
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class CaptureHistoryStore(private val context: Context) {

    private val listType = Types.newParameterizedType(
        MutableList::class.java, CaptureHistory::class.java
    )
    private val adapter = AppJson.moshi.adapter<List<CaptureHistory>>(listType)

    private val historyFile = File(context.filesDir, "capture_history.json")
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CaptureHistoryIO").apply { isDaemon = true }
    }
    private val mediaResolver = CaptureMediaResolver(context.contentResolver)

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
        _history.value = mergeEntry(_history.value, entry)
        enforceQuota()
        save()
    }

    /** Storage manager: total bytes + quota verdicts, pure for tests. */
    fun totalBytes(): Long = _history.value.sumOf { it.fileSizeBytes.coerceAtLeast(0) }

    fun storageBar(quotaBytes: Long): StorageBar =
        StorageManager.storageBar(totalBytes(), quotaBytes)

    /** Guard before a capture: false when free space is below the safety floor. */
    fun hasFreeSpace(minFreeBytes: Long = StorageManager.LOW_SPACE_FLOOR_BYTES): Boolean {
        return try {
            val free = context.filesDir.freeSpace + context.cacheDir.freeSpace
            free >= minFreeBytes
        } catch (_: Exception) {
            true
        }
    }

    /** Auto-delete-oldest until under quota; returns evicted ids. */
    fun enforceQuota(quotaBytes: Long = StorageManager.DEFAULT_QUOTA_BYTES): List<String> {
        val victims = StorageManager.evictionOrder(_history.value, totalBytes(), quotaBytes)
        if (victims.isEmpty()) return emptyList()
        return deleteAll(victims.map { it.id })
    }

    data class StorageBar(val usedBytes: Long, val quotaBytes: Long, val percent: Int)

    fun refreshFromMediaStore() {
        ioExecutor.execute {
            try {
                val merged = mergeWithDeviceMedia(_history.value, queryCapturedMedia())
                if (merged != _history.value) {
                    _history.value = merged
                    save()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh capture history from MediaStore", e)
            }
        }
    }

    /** Single-entry convenience over [deleteAll]. */
    fun deleteMedia(id: String) {
        deleteAll(listOf(id))
    }

    /**
     * Deletes the backing media for every [id] that resolves to a history
     * entry and returns the ids that were actually removed (backing media
     * deleted, or already gone). The history is rewritten once, after the
     * batch, when anything was removed. The scheme ladder is
     * [CaptureMediaResolver]'s — content URIs through the resolver, `file://`
     * URIs and plain paths through their file.
     */
    fun deleteAll(ids: List<String>): List<String> {
        val deleted = mutableListOf<String>()
        for (id in ids) {
            val entry = _history.value.find { it.id == id } ?: continue
            val ok = mediaResolver.delete(entry.filePath)
            if (ok || !mediaResolver.exists(entry.filePath)) {
                deleted.add(id)
            } else {
                Log.w(TAG, "Failed to delete media for history entry ${entry.filePath}")
            }
        }
        if (deleted.isNotEmpty()) {
            val idSet = deleted.toSet()
            _history.value = _history.value.filterNot { it.id in idSet }
            save()
        }
        return deleted
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

    companion object {
        private const val TAG = "CaptureHistoryStore"

        /**
         * The one field-wise merge policy: keep the richer entry. The incoming
         * name wins unless blank, timestamps take the max, and zero size or
         * duration never overwrites a real one.
         */
        internal fun mergeFields(existing: CaptureHistory, incoming: CaptureHistory): CaptureHistory =
            existing.copy(
                fileName = incoming.fileName.ifBlank { existing.fileName },
                timestamp = maxOf(existing.timestamp, incoming.timestamp),
                fileSizeBytes = incoming.fileSizeBytes.takeIf { it > 0 } ?: existing.fileSizeBytes,
                durationMs = incoming.durationMs.takeIf { it > 0 } ?: existing.durationMs,
            )

        /**
         * Pure merge of a new entry into the history list: dedupes by
         * normalized file path (merging through [mergeFields]) and returns
         * the list sorted newest-first. Visible for tests.
         */
        internal fun mergeEntry(
            existing: List<CaptureHistory>,
            entry: CaptureHistory,
        ): List<CaptureHistory> {
            val index = existing.indexOfFirst {
                normalizePath(it.filePath) == normalizePath(entry.filePath)
            }
            val current = existing.toMutableList()

            if (index >= 0) {
                current[index] = mergeFields(current[index], entry)
            } else {
                current.add(0, entry)
            }

            return current.sortedByDescending { it.timestamp }
        }

        /**
         * Pure reconciliation of the persisted history with the device's
         * MediaStore entries: matched paths merge through [mergeFields]
         * (existing entry upgraded from device media), unseen media is
         * adopted with a fresh id, and the result is sorted newest-first.
         * Visible for tests; the instance-side MediaStore cursor work is a
         * thin adapter feeding this.
         */
        internal fun mergeWithDeviceMedia(
            current: List<CaptureHistory>,
            deviceMedia: List<CaptureHistory>,
        ): List<CaptureHistory> {
            if (deviceMedia.isEmpty()) {
                return current.sortedByDescending { it.timestamp }
            }

            val deviceByPath = deviceMedia.associateBy { normalizePath(it.filePath) }
            val merged = current.map { existing ->
                deviceByPath[normalizePath(existing.filePath)]
                    ?.let { mergeFields(existing, it) }
                    ?: existing
            }.toMutableList()

            val existingPaths = merged.mapTo(mutableSetOf()) { normalizePath(it.filePath) }
            deviceMedia.forEach { media ->
                if (existingPaths.add(normalizePath(media.filePath))) {
                    merged.add(media.copy(id = UUID.randomUUID().toString()))
                }
            }

            return merged.sortedByDescending { it.timestamp }
        }

        private fun normalizePath(filePath: String): String = filePath.trim()
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
            ),
            folderPath = CaptureMediaFormat.PHOTOS_RELATIVE_PATH,
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
                MediaStore.Video.Media.DURATION,
            ),
            folderPath = CaptureMediaFormat.VIDEOS_RELATIVE_PATH,
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
}
