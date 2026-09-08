package com.raulshma.lenscast.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.data.CaptureHistoryStore
import com.raulshma.lenscast.data.StorageManager
import com.raulshma.lenscast.camera.model.CameraDashboardPolicy.formatBytes

/**
 * Storage manager row: quota bar + free-space action.
 * Quota is the default 2GB; per-setting quota is a follow-up.
 */
@Composable
fun StorageBarRow(store: CaptureHistoryStore) {
    val history by store.history.collectAsState()
    val bar = remember(history) {
        val used = history.sumOf { it.fileSizeBytes.coerceAtLeast(0) }
        StorageManager.storageBar(used, StorageManager.DEFAULT_QUOTA_BYTES)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Storage: ${formatBytes(bar.usedBytes)} / ${formatBytes(bar.quotaBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { store.enforceQuota() }) {
                Text("Free space")
            }
        }
        LinearProgressIndicator(
            progress = { bar.percent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
