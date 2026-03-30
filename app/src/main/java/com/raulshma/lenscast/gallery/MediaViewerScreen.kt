package com.raulshma.lenscast.gallery

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType

@Composable
fun MediaViewerScreen(
    mediaItem: CaptureHistory?,
    currentIndex: Int,
    totalCount: Int,
    canViewPrevious: Boolean,
    canViewNext: Boolean,
    onViewPrevious: () -> Unit,
    onViewNext: () -> Unit,
    onNavigateBack: () -> Unit,
    onDeleteCurrent: () -> Unit,
) {
    if (mediaItem == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Media not found", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(true) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete") },
            text = { Text("Delete \"${mediaItem.fileName}\"?") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDeleteCurrent() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (mediaItem.type) {
            CaptureType.PHOTO -> PhotoViewer(filePath = mediaItem.filePath)
            CaptureType.VIDEO -> VideoViewer(filePath = mediaItem.filePath)
        }

        Surface(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = Color.Black.copy(alpha = 0.42f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(mediaItem.fileName, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("${(currentIndex + 1).coerceAtLeast(1)} of $totalCount", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Icon(Icons.Default.Info, contentDescription = "Toggle details", tint = Color.White)
                }
                IconButton(onClick = { shareGalleryMedia(context, listOf(mediaItem)) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                }
                IconButton(onClick = { openMediaExternal(context, mediaItem) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open externally", tint = Color.White)
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                }
            }
        }

        ViewerNavButton(
            visible = canViewPrevious,
            icon = Icons.Default.ChevronLeft,
            contentDescription = "Previous item",
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            onClick = onViewPrevious,
        )
        ViewerNavButton(
            visible = canViewNext,
            icon = Icons.Default.ChevronRight,
            contentDescription = "Next item",
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            onClick = onViewNext,
        )

        AnimatedVisibility(
            visible = detailsExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                color = Color.Black.copy(alpha = 0.58f),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(formatViewerDateTime(mediaItem.timestamp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ViewerMetaChip(if (mediaItem.type == CaptureType.PHOTO) "Photo" else "Video")
                        if (mediaItem.fileSizeBytes > 0) ViewerMetaChip(formatFileSize(mediaItem.fileSizeBytes))
                        if (mediaItem.type == CaptureType.VIDEO && mediaItem.durationMs > 0) ViewerMetaChip(formatDuration(mediaItem.durationMs))
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoViewer(filePath: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val imageModel = resolveMediaModel(filePath)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.2f) {
                        scale = 1f; offsetX = 0f; offsetY = 0f
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val updatedScale = (scale * zoom).coerceIn(1f, 5f)
                    val maxX = ((viewport.width * (updatedScale - 1f)) / 2f).coerceAtLeast(0f)
                    val maxY = ((viewport.height * (updatedScale - 1f)) / 2f).coerceAtLeast(0f)
                    scale = updatedScale
                    offsetX = if (updatedScale == 1f) 0f else (offsetX + pan.x).coerceIn(-maxX, maxX)
                    offsetY = if (updatedScale == 1f) 0f else (offsetY + pan.y).coerceIn(-maxY, maxY)
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
            ViewerUnavailable(icon = Icons.Default.PhotoCamera, label = "Image not available")
        }
    }
}

@Composable
private fun VideoViewer(filePath: String) {
    val resolved = resolveMediaModel(filePath)
    var videoView: VideoView? by remember { mutableStateOf(null) }

    DisposableEffect(videoView) {
        onDispose { videoView?.stopPlayback() }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (resolved != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    FrameLayout(context).apply {
                        val view = VideoView(context).also { videoView = it }
                        view.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            gravity = Gravity.CENTER
                        }
                        val controls = MediaController(context).apply {
                            setAnchorView(view)
                            setMediaPlayer(view)
                        }
                        view.setMediaController(controls)
                        when (resolved) {
                            is android.net.Uri -> view.setVideoURI(resolved)
                            is java.io.File -> view.setVideoPath(resolved.absolutePath)
                        }
                        view.setOnPreparedListener { it.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT); view.start() }
                        addView(view)
                    }
                },
            )
        } else {
            ViewerUnavailable(icon = Icons.Default.Videocam, label = "Video not available")
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
