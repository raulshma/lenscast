package com.raulshma.lenscast.data

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.core.StreamDefaults

/**
 * Pure storage-manager policy: quota bar + auto-delete-oldest eviction order.
 * The store keeps persistence/deletion; this owns the numbers so they're JVM-tested.
 */
object StorageManager {
    const val DEFAULT_QUOTA_BYTES: Long = 2048L * 1024 * 1024
    const val LOW_SPACE_FLOOR_BYTES: Long = 200L * 1024 * 1024

    fun quotaBytes(quotaMb: Int): Long =
        quotaMb.coerceIn(StreamDefaults.STORAGE_QUOTA_MB_MIN, StreamDefaults.STORAGE_QUOTA_MB_MAX) * 1024L * 1024

    fun storageBar(usedBytes: Long, quotaBytes: Long): CaptureHistoryStore.StorageBar {
        val pct = if (quotaBytes <= 0) 0 else ((usedBytes * 100) / quotaBytes).toInt().coerceIn(0, 100)
        return CaptureHistoryStore.StorageBar(usedBytes, quotaBytes, pct)
    }

    /** Oldest-first victims until usage fits quota; empty when already under. */
    fun evictionOrder(
        history: List<CaptureHistory>,
        usedBytes: Long,
        quotaBytes: Long,
    ): List<CaptureHistory> {
        if (usedBytes <= quotaBytes) return emptyList()
        val victims = mutableListOf<CaptureHistory>()
        var freed = 0L
        for (entry in history.sortedBy { it.timestamp }) {
            victims.add(entry)
            freed += entry.fileSizeBytes.coerceAtLeast(0)
            if (usedBytes - freed <= quotaBytes) break
        }
        return victims
    }
}
