package com.raulshma.lenscast.gallery

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    mediaItem: CaptureHistory?,
    onNavigateBack: () -> Unit,
    onDelete: (String) -> Unit,
) {
    if (mediaItem == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("Media not found", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete") },
            text = { Text("Delete \"${mediaItem.fileName}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(mediaItem.id)
                        showDeleteDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (mediaItem.type) {
            CaptureType.PHOTO -> PhotoViewer(filePath = mediaItem.filePath)
            CaptureType.VIDEO -> VideoViewer(filePath = mediaItem.filePath)
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding(),
            color = Color.Black.copy(alpha = 0.4f),
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = mediaItem.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        Text(
                            text = formatViewerDate(mediaItem.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        shareMedia(context, mediaItem)
                    }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun PhotoViewer(filePath: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val imageModel = resolveMediaUri(filePath)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageModel)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Image not available",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoViewer(filePath: String) {
    val context = LocalContext.current
    val videoUri = resolveMediaUri(filePath)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (videoUri != null) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        val videoView = VideoView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                gravity = android.view.Gravity.CENTER
                            }
                        }
                        addView(videoView)

                        val mediaController = MediaController(ctx).apply {
                            setAnchorView(videoView)
                            setMediaPlayer(videoView)
                        }
                        videoView.setMediaController(mediaController)

                        when (videoUri) {
                            is Uri -> videoView.setVideoURI(videoUri)
                            is File -> videoView.setVideoPath(videoUri.absolutePath)
                        }
                        videoView.setOnPreparedListener { mp ->
                            mp.setVideoScalingMode(
                                android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
                            )
                            videoView.start()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Color.White.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Video not available",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun shareMedia(context: Context, item: CaptureHistory) {
    val uri = resolveMediaUri(item.filePath) as? Uri ?: run {
        val file = resolveMediaUri(item.filePath) as? File
        if (file != null) {
            try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            } catch (e: Exception) {
                Uri.fromFile(file)
            }
        } else null
    } ?: return

    val mimeType = when (item.type) {
        CaptureType.PHOTO -> "image/jpeg"
        CaptureType.VIDEO -> "video/mp4"
    }

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share ${item.fileName}"))
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

private fun formatViewerDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
