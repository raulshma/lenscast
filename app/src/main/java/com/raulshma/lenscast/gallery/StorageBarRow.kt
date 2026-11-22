package com.raulshma.lenscast.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.camera.model.CameraDashboardPolicy.formatBytes

/**
 * Storage manager row: quota bar + free-space action, against the configured
 * quota. Rendered from the source's [com.raulshma.lenscast.gallery.GallerySource.storageBar]
 * snapshot (the store re-computes it per history change) rather than from the
 * store directly — the remote backend holds the bar null and this row never
 * renders there.
 */
@Composable
fun StorageBarRow(
    bar: com.raulshma.lenscast.data.CaptureHistoryStore.StorageBar,
    onFreeSpace: () -> Unit,
) {
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
                stringResource(R.string.gallery_storage_usage, formatBytes(bar.usedBytes), formatBytes(bar.quotaBytes)),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onFreeSpace) {
                Text(stringResource(R.string.gallery_free_space))
            }
        }
        LinearWavyProgressIndicator(
            progress = { bar.percent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
