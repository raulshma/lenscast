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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.R
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
        factory = GalleryViewModel.Factory(app.captureHistoryStore, app.decryptedPhotoCache, app.appScope)
    )

    val allItems by viewModel.allItems.collectAsState()
    val galleryItems by viewModel.galleryItems.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectMode by viewModel.selectMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val decryptedPhotos by viewModel.decryptedPhotos.collectAsState()
    val encryptedVideoIds by viewModel.encryptedVideoIds.collectAsState()

    // The trash/undo surface: staged ids + their count drive the snackbar.
    val pendingUndoCount by viewModel.pendingUndoCount.collectAsState()
    val stagedIds by viewModel.stagedIds.collectAsState()

    val overview = remember(allItems) { buildGalleryOverview(allItems) }
    val sections = remember(galleryItems) { buildGallerySections(galleryItems) }

    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    // One snackbar per staging burst: "N moved to trash" with the Undo action —
    // undoing restores every id still staged; expiry dismisses on its own.
    // The strings resolve in composition (they are composable reads); the
    // effect only uses the resolved values.
    val trashMessage = if (pendingUndoCount > 0) {
        pluralStringResource(R.plurals.gallery_trash_snackbar, pendingUndoCount, pendingUndoCount)
    } else {
        ""
    }
    val undoLabel = stringResource(R.string.gallery_undo)
    LaunchedEffect(pendingUndoCount) {
        if (pendingUndoCount == 0) {
            snackbarHostState.currentSnackbarData?.dismiss()
        } else {
            val result = snackbarHostState.showSnackbar(
                message = trashMessage,
                actionLabel = undoLabel,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoStaged(stagedIds.toList())
            }
        }
    }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text(stringResource(R.string.gallery_delete_selected_title)) },
            text = {
                Text(pluralStringResource(R.plurals.gallery_delete_selected_message, selectedIds.size, selectedIds.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        showBatchDeleteDialog = false
                    },
                ) {
                    Text(stringResource(R.string.gallery_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text(stringResource(R.string.gallery_cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
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
                    title = stringResource(R.string.gallery_title),
                    onNavigateBack = onNavigateBack,
                    actions = {
                        IconButton(
                            onClick = { viewModel.setSelectMode(true) },
                            enabled = allItems.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = stringResource(R.string.gallery_select_media_cd),
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
            if (!selectMode) {
                GallerySearchField(
                    query = searchQuery,
                    onQueryChanged = viewModel::setSearchQuery,
                )
            }
            GalleryFilterRow(
                currentFilter = filter,
                onFilterChanged = viewModel::setFilter,
                overview = overview,
            )
            StorageBarRow(store = app.captureHistoryStore)

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
                    onToggleFavorite = { item -> viewModel.toggleFavorite(item.id) },
                    decryptedPhotos = decryptedPhotos,
                    encryptedVideoIds = encryptedVideoIds,
                )
            }
        }
    }
}
