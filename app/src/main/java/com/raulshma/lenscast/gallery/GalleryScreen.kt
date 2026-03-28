package com.raulshma.lenscast.gallery

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gallery") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
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
                    onItemClick = { onViewMedia(it.id) },
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
    onItemClick: (CaptureHistory) -> Unit,
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
                onClick = { onItemClick(item) },
            )
        }
    }
}

@Composable
private fun MediaGridItem(
    item: CaptureHistory,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
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
                    modifier = Modifier.fillMaxSize(),
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

            if (item.type == CaptureType.VIDEO) {
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
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
