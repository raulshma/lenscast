package com.raulshma.lenscast.gallery

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.raulshma.lenscast.R
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.ui.animation.LocalAnimatedVisibilityScope
import com.raulshma.lenscast.ui.animation.LocalSharedTransitionScope

@Composable
fun MediaViewerScreen(
    allItems: List<CaptureHistory>,
    initialMediaId: String,
    pagerState: PagerState,
    onDeleteCurrent: () -> Unit,
    onNavigateBack: () -> Unit,
    /** Encrypted-at-rest photos' decrypted cache files, keyed by history id. */
    decryptedPhotos: Map<String, java.io.File> = emptyMap(),
    /** Encrypted-at-rest videos: played through the decrypting stream. */
    encryptedVideoIds: Set<String> = emptySet(),
) {
    if (allItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.gallery_media_not_found), style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
        return
    }

    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    val currentIndex = pagerState.currentPage
    val mediaItem = allItems.getOrElse(currentIndex) { allItems.first() }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.gallery_delete)) },
            text = { Text(stringResource(R.string.gallery_delete_item_message, mediaItem.fileName)) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDeleteCurrent() }) {
                    Text(stringResource(R.string.gallery_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.gallery_cancel)) }
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
                    filePath = item.filePath,
                    mediaId = item.id,
                    enableSharedElement = item.id == initialMediaId,
                    decryptedModel = decryptedPhotos[item.id],
                )
                CaptureType.VIDEO -> VideoViewer(
                    filePath = item.filePath,
                    encrypted = item.id in encryptedVideoIds,
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
                IconButton(onClick = { shareGalleryMedia(context, listOf(mediaItem)) }) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.gallery_share_cd), tint = Color.White)
                }
                IconButton(onClick = { openMediaExternal(context, mediaItem) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.gallery_open_externally_cd), tint = Color.White)
                }
                IconButton(onClick = { showDeleteDialog = true }) {
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
    filePath: String,
    mediaId: String,
    enableSharedElement: Boolean,
    /** The decrypted cache file, when the photo is encrypted at rest. */
    decryptedModel: Any? = null,
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val imageModel = decryptedModel ?: resolveMediaModel(filePath)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // The gesture math is ViewerZoomPolicy's — the screen only
                // normalizes coordinates and applies the returned transform.
                detectTapGestures(onDoubleTap = {
                    val next = ViewerZoomPolicy.onDoubleTap(scale)
                    scale = next.scale
                    offsetX = next.offsetX
                    offsetY = next.offsetY
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = ViewerZoomPolicy.onTransform(
                        currentScale = scale,
                        currentOffsetX = offsetX,
                        currentOffsetY = offsetY,
                        zoom = zoom,
                        panX = pan.x,
                        panY = pan.y,
                        viewportWidth = viewport.width.toFloat(),
                        viewportHeight = viewport.height.toFloat(),
                    )
                    scale = next.scale
                    offsetX = next.offsetX
                    offsetY = next.offsetY
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(imageModel).crossfade(true).build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (enableSharedElement && sharedTransitionScope != null && animatedVisibilityScope != null) {
                            with(sharedTransitionScope) {
                                Modifier.sharedElement(
                                    rememberSharedContentState(key = "media-$mediaId"),
                                    animatedVisibilityScope,
                                )
                            }
                        } else Modifier
                    )
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
                    .onSizeChanged { viewport = it },
                contentScale = ContentScale.Fit,
            )
        } else {
            ViewerUnavailable(icon = Icons.Default.PhotoCamera, label = stringResource(R.string.gallery_image_unavailable))
        }
    }
}

@Composable
private fun VideoViewer(filePath: String, encrypted: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.raulshma.lenscast.MainApplication
    val resolved = resolveMediaModel(filePath)
    val exoPlayer = androidx.compose.runtime.remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
        }
    }
    androidx.compose.runtime.DisposableEffect(resolved, encrypted) {
        when {
            // Encrypted at rest: the media never exists as plaintext bytes the
            // player could open, so playback rides the decrypting DataSource
            // (seeks are sequential decrypt-and-discard — see the source).
            encrypted -> {
                val resolver = com.raulshma.lenscast.capture.CaptureMediaResolver(
                    context.contentResolver,
                    app.mediaKeyProvider,
                )
                val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    DecryptingDataSourceFactory(resolver, filePath),
                ).createMediaSource(
                    androidx.media3.common.MediaItem.fromUri(
                        android.net.Uri.parse("lenscast://decrypted/$filePath")
                    ),
                )
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
            resolved is android.net.Uri -> {
                exoPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(resolved))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
            resolved is java.io.File -> {
                exoPlayer.setMediaItem(
                    androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(resolved))
                )
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        }
        onDispose { exoPlayer.stop() }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (resolved != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
            )
        } else {
            ViewerUnavailable(icon = Icons.Default.Videocam, label = stringResource(R.string.gallery_video_unavailable))
        }
    }
}

@Composable
private fun ViewerUnavailable(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
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
