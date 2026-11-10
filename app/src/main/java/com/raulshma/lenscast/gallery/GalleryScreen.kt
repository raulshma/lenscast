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
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.R
import com.raulshma.lenscast.ui.components.LensCastTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onNavigateBack: () -> Unit,
    onViewMedia: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppGraph
    val viewModel: GalleryViewModel = viewModel(
        factory = GalleryViewModel.Factory(app.captureHistoryStore, app.gallerySession)
    )

    val allItems by viewModel.allItems.collectAsState()
    val galleryItems by viewModel.galleryItems.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectMode by viewModel.selectMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val decryptedPhotos by viewModel.decryptedPhotos.collectAsState()
    val encryptedVideoIds by viewModel.encryptedVideoIds.collectAsState()

    // The trash/undo surface: each staged set arms one snackbar state holding
    // the ids that staging burst put in the trash.
    val undoSnackbar by viewModel.undoSnackbar.collectAsState()

    val overview = remember(allItems) { buildGalleryOverview(allItems) }
    val sections = remember(galleryItems) { buildGallerySections(galleryItems) }

    // The batch-delete dialog's snapshot of the selection at open time: the
    // plural text and the confirm render from it, so the dialog can never
    // re-label or delete against a selection that moved under it.
    var pendingDeleteSelection by remember { mutableStateOf<Set<String>?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    // One snackbar per staged set: "N moved to trash" with the Undo action —
    // undoing restores exactly the snapshotted ids, never ids staged after
    // this snackbar appeared; expiry (the set emptying) dismisses on its own.
    // The strings resolve in composition (they are composable reads); the
    // effect only uses the resolved values and the snapshot.
    val trashMessage = undoSnackbar?.let {
        pluralStringResource(R.plurals.gallery_trash_snackbar, it.count, it.count)
    } ?: ""
    val undoLabel = stringResource(R.string.gallery_undo)
    LaunchedEffect(undoSnackbar) {
        val snapshot = undoSnackbar
        if (snapshot == null) {
            snackbarHostState.currentSnackbarData?.dismiss()
        } else {
            val result = snackbarHostState.showSnackbar(
                message = trashMessage,
                actionLabel = undoLabel,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoStaged(snapshot.stagedIds.toList())
            }
        }
    }

    val deleteSelection = pendingDeleteSelection
    if (deleteSelection != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteSelection = null },
            title = { Text(stringResource(R.string.gallery_delete_selected_title)) },
            text = {
                Text(pluralStringResource(R.plurals.gallery_delete_selected_message, deleteSelection.size, deleteSelection.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        pendingDeleteSelection = null
                    },
                ) {
                    Text(stringResource(R.string.gallery_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSelection = null }) {
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
                    // Open the dialog against the current selection — only
                    // when non-empty (the pill's own guard stays).
                    onDeleteSelected = {
                        pendingDeleteSelection = selectedIds.takeIf { it.isNotEmpty() }
                    },
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
