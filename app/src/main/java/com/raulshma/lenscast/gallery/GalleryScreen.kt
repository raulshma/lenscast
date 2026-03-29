package com.raulshma.lenscast.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GALLERY_DATE_FORMAT = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onNavigateBack: () -> Unit,
    onViewMedia: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val viewModel: GalleryViewModel = viewModel(
        factory = GalleryViewModel.Factory(app.captureHistoryStore)
    )

    val galleryItems by viewModel.galleryItems.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val selectMode by viewModel.selectMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val batchDeleting by viewModel.batchDeleting.collectAsState()

    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("Delete selected") },
            text = { Text("Delete ${selectedIds.size} item${if (selectedIds.size != 1) "s" else ""}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        showBatchDeleteDialog = false
                    },
                    enabled = !batchDeleting
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            if (selectMode) {
                SelectModeTopBar(
                    selectedCount = selectedIds.size,
                    allCount = galleryItems.size,
                    allSelected = selectedIds.size == galleryItems.size && galleryItems.isNotEmpty(),
                    onSelectAll = { viewModel.selectAll() },
                    onDeselectAll = { viewModel.selectNone() },
                    onExitSelectMode = { viewModel.setSelectMode(false) },
                )
            } else {
                TopAppBar(
                    title = { Text("Gallery") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.setSelectMode(true) }) {
                            Icon(
                                Icons.Default.PhotoCamera,
                                contentDescription = "Select"
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selectMode,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                SelectModeBottomBar(
                    selectedCount = selectedIds.size,
                    batchDeleting = batchDeleting,
                    onShareSelected = {
                        shareMultipleMedia(context, galleryItems.filter { it.id in selectedIds })
                    },
                    onDeleteSelected = { showBatchDeleteDialog = true },
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            FilterRow(
                currentFilter = filter,
                onFilterChanged = { viewModel.setFilter(it) },
                photoCount = galleryItems.count { it.type == CaptureType.PHOTO },
                videoCount = galleryItems.count { it.type == CaptureType.VIDEO },
                allCount = galleryItems.size,
            )

            if (galleryItems.isEmpty()) {
                EmptyGalleryState()
            } else {
                MediaGrid(
                    items = galleryItems,
                    selectMode = selectMode,
                    selectedIds = selectedIds,
                    onItemClick = { item ->
                        if (selectMode) {
                            viewModel.toggleSelect(item.id)
                        } else {
                            onViewMedia(item.id)
                        }
                    },
                    onItemLongClick = { item ->
                        if (!selectMode) {
                            viewModel.setSelectMode(true)
                            viewModel.toggleSelect(item.id)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectModeTopBar(
    selectedCount: Int,
    allCount: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onExitSelectMode: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = "$selectedCount selected",
                fontWeight = FontWeight.Medium,
            )
        },
        navigationIcon = {
            IconButton(onClick = onExitSelectMode) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        },
        actions = {
            IconButton(onClick = { if (allSelected) onDeselectAll() else onSelectAll() }) {
                Icon(
                    if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                    contentDescription = if (allSelected) "Deselect all" else "Select all"
                )
            }
        }
    )
}

@Composable
private fun SelectModeBottomBar(
    selectedCount: Int,
    batchDeleting: Boolean,
    onShareSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(
                    enabled = selectedCount > 0,
                    onClick = onShareSelected
                ),
            ) {
                IconButton(
                    onClick = onShareSelected,
                    enabled = selectedCount > 0,
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Share",
                        tint = if (selectedCount > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    )
                }
                Text(
                    text = "Share",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selectedCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(
                    enabled = selectedCount > 0 && !batchDeleting,
                    onClick = onDeleteSelected
                ),
            ) {
                IconButton(
                    onClick = onDeleteSelected,
                    enabled = selectedCount > 0 && !batchDeleting,
                ) {
                    if (batchDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = if (selectedCount > 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        )
                    }
                }
                Text(
                    text = if (batchDeleting) "Deleting..." else "Delete",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (batchDeleting) MaterialTheme.colorScheme.onSurfaceVariant
                        else if (selectedCount > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
            }
        }
    }
}

@Composable
private fun FilterRow(
    currentFilter: GalleryFilter,
    onFilterChanged: (GalleryFilter) -> Unit,
    photoCount: Int,
    videoCount: Int,
    allCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GalleryFilter.entries.forEach { filter ->
            val label = when (filter) {
                GalleryFilter.ALL -> "All ($allCount)"
                GalleryFilter.PHOTOS -> "Photos ($photoCount)"
                GalleryFilter.VIDEOS -> "Videos ($videoCount)"
            }
            FilterChip(
                selected = currentFilter == filter,
                onClick = { onFilterChanged(filter) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(50),
            )
        }
    }
}

@Composable
private fun MediaGrid(
    items: List<CaptureHistory>,
    selectMode: Boolean,
    selectedIds: Set<String>,
    onItemClick: (CaptureHistory) -> Unit,
    onItemLongClick: (CaptureHistory) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items, key = { it.id }) { item ->
            MediaGridItem(
                item = item,
                selectMode = selectMode,
                isSelected = item.id in selectedIds,
                onClick = { onItemClick(item) },
                onLongClick = { onItemLongClick(item) },
            )
        }
    }
}

@Composable
private fun MediaGridItem(
    item: CaptureHistory,
    selectMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val imageModel = resolveMediaUri(item.filePath)

            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = item.fileName,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isSelected && !selectMode) Modifier
                            else if (isSelected) Modifier.background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            )
                            else Modifier
                        ),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (item.type == CaptureType.PHOTO)
                            Icons.Default.PhotoCamera else Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (item.type == CaptureType.VIDEO && !selectMode) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color.White,
                        modifier = Modifier
                            .size(24.dp)
                            .padding(4.dp),
                    )
                }
            }

            if (selectMode) {
                SelectionCheckbox(
                    isSelected = isSelected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }

            if (!selectMode) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Color.Black.copy(alpha = 0.5f),
                            RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 9.sp,
                    )
                    Text(
                        text = formatGalleryDate(item.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 8.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionCheckbox(
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(24.dp),
        shape = CircleShape,
        color = if (isSelected) MaterialTheme.colorScheme.primary
            else Color.White.copy(alpha = 0.7f),
        tonalElevation = if (isSelected) 0.dp else 2.dp,
        border = if (!isSelected) androidx.compose.foundation.BorderStroke(
            2.dp,
            Color.White.copy(alpha = 0.5f)
        ) else null,
    ) {
        if (isSelected) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Selected",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun EmptyGalleryState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No media yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Captured photos and videos will appear here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun shareMultipleMedia(context: Context, items: List<CaptureHistory>) {
    if (items.isEmpty()) return

    val uris = items.mapNotNull { item ->
        val resolved = resolveMediaUri(item.filePath)
        when (resolved) {
            is Uri -> resolved
            is File -> try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    resolved,
                )
            } catch (e: Exception) {
                Uri.fromFile(resolved)
            }
            else -> null
        }
    }

    if (uris.isEmpty()) return

    val intent = if (uris.size == 1) {
        val mimeType = when (items.first().type) {
            CaptureType.PHOTO -> "image/jpeg"
            CaptureType.VIDEO -> "video/mp4"
        }
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        val hasPhoto = items.any { it.type == CaptureType.PHOTO }
        val hasVideo = items.any { it.type == CaptureType.VIDEO }
        val mimeType = when {
            hasPhoto && hasVideo -> "*/*"
            hasVideo -> "video/*"
            else -> "image/*"
        }
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }

    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share ${items.size} item${if (items.size > 1) "s" else ""}"))
}

private fun resolveMediaUri(filePath: String): Any? {
    if (filePath.startsWith("content://")) {
        return Uri.parse(filePath)
    }
    if (filePath.startsWith("file://")) {
        return Uri.parse(filePath)
    }
    val file = File(filePath)
    if (file.exists()) {
        return file
    }
    return null
}

private fun formatGalleryDate(timestamp: Long): String {
    return GALLERY_DATE_FORMAT.format(Date(timestamp))
}
