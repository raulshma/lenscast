package com.raulshma.lenscast.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GallerySelectModeTopBar(
    selectedCount: Int,
    allCount: Int,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onExitSelectMode: () -> Unit,
) {
    val allSelected = allCount > 0 && selectedCount == allCount
    TopAppBar(
        title = {
            Text(
                text = "${selectedCount.coerceAtMost(allCount)} of $allCount selected",
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = {
            IconButton(onClick = onExitSelectMode) {
                Icon(Icons.Default.Deselect, contentDescription = "Exit selection mode")
            }
        },
        actions = {
            IconButton(
                onClick = { if (allSelected) onDeselectAll() else onSelectAll() },
                enabled = allCount > 0,
            ) {
                Icon(
                    imageVector = if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                    contentDescription = if (allSelected) "Deselect all" else "Select all",
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
fun GallerySelectModeBottomBar(
    selectedCount: Int,
    batchDeleting: Boolean,
    onShareSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    Surface(
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "$selectedCount selected",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (selectedCount == 0) "Choose items to share or delete" else "Batch actions are ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryActionPill(
                    label = "Share",
                    icon = Icons.Default.Share,
                    enabled = selectedCount > 0,
                    destructive = false,
                    loading = false,
                    onClick = onShareSelected,
                )
                GalleryActionPill(
                    label = if (batchDeleting) "Deleting" else "Delete",
                    icon = Icons.Default.Delete,
                    enabled = selectedCount > 0 && !batchDeleting,
                    destructive = true,
                    loading = batchDeleting,
                    onClick = onDeleteSelected,
                )
            }
        }
    }
}

@Composable
fun GalleryOverviewCard(
    overview: GalleryOverview,
    visibleCount: Int,
    visibleBytes: Long,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Your LensCast library",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (overview.totalCount == 0) {
                        "Captured photos and videos will appear here."
                    } else {
                        "Showing $visibleCount of ${overview.totalCount} items across ${overview.dayCount} day${if (overview.dayCount == 1) "" else "s"}."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GalleryStatTile("Photos", overview.photoCount.toString(), Icons.Default.Image)
            GalleryStatTile("Videos", overview.videoCount.toString(), Icons.Default.Movie)
            GalleryStatTile(
                title = "Visible",
                value = visibleCount.toString(),
                icon = Icons.Default.PhotoLibrary,
                supporting = formatFileSize(visibleBytes),
            )
            GalleryStatTile(
                title = "Storage",
                value = formatFileSize(overview.totalBytes),
                icon = Icons.Default.Folder,
                supporting = "In app library",
            )
        }
    }
}

@Composable
fun GalleryFilterRow(
    currentFilter: GalleryFilter,
    onFilterChanged: (GalleryFilter) -> Unit,
    overview: GalleryOverview,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GalleryFilter.entries.forEach { filter ->
            val label = when (filter) {
                GalleryFilter.ALL -> "All (${overview.totalCount})"
                GalleryFilter.PHOTOS -> "Photos (${overview.photoCount})"
                GalleryFilter.VIDEOS -> "Videos (${overview.videoCount})"
            }
            FilterChip(
                selected = currentFilter == filter,
                onClick = { onFilterChanged(filter) },
                label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(999.dp),
            )
        }
    }
}

@Composable
private fun GalleryStatTile(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                if (!supporting.isNullOrBlank()) {
                    Text(supporting, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryActionPill(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    destructive: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh
        destructive -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        destructive -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(18.dp), color = bg) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = fg)
            } else {
                Icon(icon, contentDescription = label, tint = fg)
            }
            Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
        }
    }
}
