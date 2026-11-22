package com.raulshma.lenscast.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.raulshma.lenscast.R
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.ui.animation.LocalAnimatedVisibilityScope
import com.raulshma.lenscast.ui.animation.LocalSharedTransitionScope

/**
 * The viewer's delete dialog snapshot: the item the dialog was opened for —
 * its id (what the confirm deletes) and its file name (what the text shows).
 * Taken when the delete button opens the dialog, so a list change while the
 * dialog is open can neither re-label it with another item's name nor
 * re-target the confirm at whatever the pager now shows.
 */
private data class DeleteTarget(val id: String, val fileName: String)

@Composable
fun MediaViewerScreen(
    allItems: List<CaptureHistory>,
    initialMediaId: String,
    pagerState: PagerState,
    /**
     * The [GallerySource] whose render slots decode the paged media and whose
     * verdicts gate the mutating affordances (favorite toggle, share,
     * open-externally, delete copy).
     */
    source: GallerySource,
    /** The snapshotted id the delete dialog confirmed against. */
    onDelete: (String) -> Unit,
    onToggleFavorite: (String) -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    if (allItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.gallery_media_not_found), style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
        return
    }

    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    var detailsExpanded by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    val currentIndex = pagerState.currentPage
    val mediaItem = allItems.getOrElse(currentIndex) { allItems.first() }

    val pendingDelete = deleteTarget
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.gallery_delete)) },
            text = {
                // Staging deletes read as a simple confirm (the undo snackbar
                // follows in the grid); the remote wire delete is permanent —
                // the companion media tab's "cannot be undone" copy says so.
                if (source.deleteIsUndoable) {
                    Text(stringResource(R.string.gallery_delete_item_message, pendingDelete.fileName))
                } else {
                    Text(stringResource(R.string.companion_media_delete_confirm_single, pendingDelete.fileName))
                }
            },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDelete(pendingDelete.id) }) {
                    Text(stringResource(R.string.gallery_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.gallery_cancel)) }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = allItems.getOrElse(page) { return@HorizontalPager }
            when (item.type) {
                CaptureType.PHOTO -> PhotoViewer(
                    item = item,
                    enableSharedElement = item.id == initialMediaId,
                    source = source,
                )
                CaptureType.VIDEO -> VideoViewer(
                    item = item,
                    source = source,
                )
            }
        }

        Surface(modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            color = Color.Black.copy(alpha = 0.42f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back), tint = Color.White)
                    }
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(mediaItem.fileName, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(
                            stringResource(
                                R.string.gallery_viewer_position,
                                (currentIndex + 1).coerceAtLeast(1),
                                allItems.size
                            ),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                IconButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Icon(Icons.Default.Info, contentDescription = stringResource(R.string.gallery_toggle_details_cd), tint = Color.White)
                }
                // Share / open-externally hand local bytes to other apps —
                // hidden where the backend's media has none (remote items).
                if (source.canShareExternally) {
                    IconButton(onClick = { shareGalleryMedia(context, listOf(mediaItem)) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.gallery_share_cd), tint = Color.White)
                    }
                    IconButton(onClick = { openMediaExternal(context, mediaItem) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.gallery_open_externally_cd), tint = Color.White)
                    }
                }
                IconButton(onClick = { onToggleFavorite(mediaItem.id) }, enabled = source.canFavorite) {
                    // The viewer's star toggle; the grid badge and the favorites
                    // filter read the same persisted flag. Where the backend
                    // carries it read-only (the remote wire), the star stays as
                    // a state indicator, disabled rather than hidden.
                    Icon(
                        if (mediaItem.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = stringResource(
                            if (mediaItem.favorite) R.string.gallery_unfavorite_cd
                            else R.string.gallery_favorite_cd
                        ),
                        tint = if (mediaItem.favorite) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                // The delete dialog snapshots the item it is opened for; the
                // dialog renders and confirms against that snapshot.
                IconButton(onClick = { deleteTarget = DeleteTarget(mediaItem.id, mediaItem.fileName) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.gallery_delete_cd), tint = Color.White)
                }
            }
        }

        ViewerNavButton(
            visible = currentIndex > 0,
            icon = Icons.Default.ChevronLeft,
            contentDescription = stringResource(R.string.gallery_previous_item_cd),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(currentIndex - 1) } },
        )
        ViewerNavButton(
            visible = currentIndex < allItems.lastIndex,
            icon = Icons.Default.ChevronRight,
            contentDescription = stringResource(R.string.gallery_next_item_cd),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(currentIndex + 1) } },
        )

        AnimatedVisibility(
            visible = detailsExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                color = Color.Black.copy(alpha = 0.58f),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(formatViewerDateTime(mediaItem.timestamp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ViewerMetaChip(
                            if (mediaItem.type == CaptureType.PHOTO) {
                                stringResource(R.string.gallery_type_photo)
                            } else {
                                stringResource(R.string.gallery_type_video)
                            }
                        )
                        if (mediaItem.fileSizeBytes > 0) ViewerMetaChip(formatFileSize(mediaItem.fileSizeBytes))
                        if (mediaItem.type == CaptureType.VIDEO && mediaItem.durationMs > 0) ViewerMetaChip(formatDuration(mediaItem.durationMs))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PhotoViewer(
    item: CaptureHistory,
    enableSharedElement: Boolean,
    source: GallerySource,
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    // One holder for the gesture state: the scale/pan triple every handler
    // writes atomically through [ViewerZoomPolicy.Transform] copies. The
    // viewport measurement stays a separate state — Transform carries only
    // the transform the policy returns; the viewport is the input it clamps
    // against, not part of the output.
    var transform by remember { mutableStateOf(ViewerZoomPolicy.Transform(1f, 0f, 0f)) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // The gesture math is ViewerZoomPolicy's — the screen only
                // normalizes coordinates and applies the returned transform.
                detectTapGestures(onDoubleTap = {
                    transform = ViewerZoomPolicy.onDoubleTap(transform.scale)
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    transform = ViewerZoomPolicy.onTransform(
                        currentScale = transform.scale,
                        currentOffsetX = transform.offsetX,
                        currentOffsetY = transform.offsetY,
                        zoom = zoom,
                        panX = pan.x,
                        panY = pan.y,
                        viewportWidth = viewport.width.toFloat(),
                        viewportHeight = viewport.height.toFloat(),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The decode is the source's slot (Coil over this phone's bytes, or
        // the authed fetch over the device's media route); the screen owns
        // the gesture transform and the shared-element binding.
        source.photo(
            item,
            Modifier
                .fillMaxSize()
                .then(
                    if (enableSharedElement && sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(key = "media-${item.id}"),
                                animatedVisibilityScope,
                            )
                        }
                    } else Modifier
                )
                .graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.offsetX
                    translationY = transform.offsetY
                }
                .onSizeChanged { viewport = it },
        )
    }
}

@Composable
private fun VideoViewer(item: CaptureHistory, source: GallerySource) {
    // Playback binding is the source's slot: this phone's files/decrypting
    // stream locally, the authed media route over the device's engine
    // remotely. The slot owns its unavailable state too.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        source.video(item, Modifier.fillMaxSize())
    }
}

@Composable
private fun ViewerMetaChip(text: String) {
    Surface(shape = RoundedCornerShape(999.dp), color = Color.White.copy(alpha = 0.12f)) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerNavButton(
    visible: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (!visible) return
    Surface(modifier = modifier, shape = CircleShape, color = Color.Black.copy(alpha = 0.52f), onClick = onClick) {
        Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}
