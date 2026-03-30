package com.raulshma.lenscast.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.ui.components.LensCastTopBar

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

    val allItems by viewModel.allItems.collectAsState()
    val galleryItems by viewModel.galleryItems.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val selectMode by viewModel.selectMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val batchDeleting by viewModel.batchDeleting.collectAsState()

    val overview = remember(allItems) { buildGalleryOverview(allItems) }
    val sections = remember(galleryItems) { buildGallerySections(galleryItems) }
    val visibleBytes = remember(galleryItems) { galleryItems.sumOf { it.fileSizeBytes.coerceAtLeast(0L) } }

    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("Delete selected") },
            text = {
                Text("Delete ${selectedIds.size} selected item${if (selectedIds.size == 1) "" else "s"}?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        showBatchDeleteDialog = false
                    },
                    enabled = !batchDeleting,
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            if (selectMode) {
                GallerySelectModeTopBar(
                    selectedCount = selectedIds.size,
                    allCount = galleryItems.size,
                    onSelectAll = { viewModel.selectAll() },
                    onDeselectAll = { viewModel.selectNone() },
                    onExitSelectMode = { viewModel.setSelectMode(false) },
                )
            } else {
                LensCastTopBar(
                    title = "Gallery",
                    onNavigateBack = onNavigateBack,
                    actions = {
                        IconButton(
                            onClick = { viewModel.setSelectMode(true) },
                            enabled = allItems.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = "Select media",
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selectMode,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                GallerySelectModeBottomBar(
                    selectedCount = selectedIds.size,
                    batchDeleting = batchDeleting,
                    onShareSelected = {
                        shareGalleryMedia(context, allItems.filter { it.id in selectedIds })
                    },
                    onDeleteSelected = { showBatchDeleteDialog = true },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            GalleryOverviewCard(
                overview = overview,
                visibleCount = galleryItems.size,
                visibleBytes = visibleBytes,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            GalleryFilterRow(
                currentFilter = filter,
                onFilterChanged = viewModel::setFilter,
                overview = overview,
            )

            if (galleryItems.isEmpty()) {
                GalleryEmptyState(
                    currentFilter = filter,
                    hasAnyMedia = allItems.isNotEmpty(),
                )
            } else {
                GalleryMediaGrid(
                    sections = sections,
                    selectMode = selectMode,
                    selectedIds = selectedIds,
                    onItemClick = { item ->
                        if (selectMode) viewModel.toggleSelect(item.id) else onViewMedia(item.id)
                    },
                    onItemLongClick = { item ->
                        if (!selectMode) viewModel.setSelectMode(true)
                        viewModel.toggleSelect(item.id)
                    },
                )
            }
        }
    }
}
