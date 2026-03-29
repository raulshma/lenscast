package com.raulshma.lenscast.data

import android.content.Context
import android.util.Log
import com.raulshma.lenscast.capture.model.CaptureHistory
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
    private val saveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CaptureHistoryIO").apply { isDaemon = true }
    }

    private val _history = MutableStateFlow<List<CaptureHistory>>(emptyList())
    val history: StateFlow<List<CaptureHistory>> = _history.asStateFlow()

    init {
        load()
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
        saveExecutor.execute {
            try {
                val json = adapter.toJson(snapshot)
                historyFile.writeText(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save capture history", e)
            }
        }
    }

    fun add(entry: CaptureHistory) {
        val current = _history.value.toMutableList()
        current.add(0, entry)
        _history.value = current
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
            type = com.raulshma.lenscast.capture.model.CaptureType.PHOTO,
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
            type = com.raulshma.lenscast.capture.model.CaptureType.VIDEO,
            fileName = fileName,
            filePath = filePath,
            timestamp = System.currentTimeMillis(),
            fileSizeBytes = fileSizeBytes,
            durationMs = durationMs,
        )
    }

    companion object {
        private const val TAG = "CaptureHistoryStore"
    }
}
