package com.raulshma.lenscast.gallery

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.raulshma.lenscast.R
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.ui.animation.LocalAnimatedVisibilityScope
import com.raulshma.lenscast.ui.animation.LocalSharedTransitionScope

@Composable
fun GalleryMediaGrid(
    sections: List<GallerySection>,
    selectMode: Boolean,
    selectedIds: Set<String>,
    onItemClick: (CaptureHistory) -> Unit,
    onItemLongClick: (CaptureHistory) -> Unit,
    /** Encrypted-at-rest photos' decrypted cache files, keyed by history id. */
    decryptedPhotos: Map<String, java.io.File> = emptyMap(),
    /** Encrypted-at-rest videos: previewed as a placeholder (playback decrypts). */
    encryptedVideoIds: Set<String> = emptySet(),
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 124.dp),
        contentPadding = PaddingValues(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { section ->
            item(key = "header-${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                GallerySectionHeader(section)
            }
            items(section.items, key = { it.id }) { item ->
                GalleryMediaCard(
                    item = item,
                    selectMode = selectMode,
                    isSelected = item.id in selectedIds,
                    decryptedModel = decryptedPhotos[item.id],
                    showPlaceholder = item.type == CaptureType.VIDEO && item.id in encryptedVideoIds,
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                )
            }
        }
    }
}

@Composable
fun GalleryEmptyState(
    currentFilter: GalleryFilter,
    hasAnyMedia: Boolean,
) {
    val state = when {
        !hasAnyMedia -> Triple(
            stringResource(R.string.gallery_empty_none_title),
            stringResource(R.string.gallery_empty_none_body),
            Icons.Default.PhotoLibrary
        )
        currentFilter == GalleryFilter.PHOTOS -> Triple(
            stringResource(R.string.gallery_empty_photos_title),
            stringResource(R.string.gallery_empty_photos_body),
            Icons.Default.Image
        )
        currentFilter == GalleryFilter.VIDEOS -> Triple(
            stringResource(R.string.gallery_empty_videos_title),
            stringResource(R.string.gallery_empty_videos_body),
            Icons.Default.Movie
        )
        else -> Triple(
            stringResource(R.string.gallery_empty_other_title),
            stringResource(R.string.gallery_empty_other_body),
            Icons.Default.PhotoLibrary
        )
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(88.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(state.third, contentDescription = null, modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(state.first, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            Text(state.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GallerySectionHeader(section: GallerySection) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            Text(section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
            Text(section.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = pluralStringResource(
                R.plurals.gallery_section_item_count,
                section.items.size,
                section.items.size,
                formatFileSize(section.totalBytes)
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun GalleryMediaCard(
    item: CaptureHistory,
    selectMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** Overrides the resolved model with a decrypted cache file when present. */
    decryptedModel: Any? = null,
    /** Forces the placeholder icon (encrypted video: no frameable bytes for Coil). */
    showPlaceholder: Boolean = false,
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.86f)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val imageModel = decryptedModel ?: resolveMediaModel(item.filePath)
            if (imageModel != null && !showPlaceholder) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(imageModel).crossfade(true).build(),
                    contentDescription = item.fileName,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                with(sharedTransitionScope) {
                                    Modifier.sharedElement(
                                        rememberSharedContentState(key = "media-${item.id}"),
                                        animatedVisibilityScope,
                                    )
                                }
                            } else Modifier
                        ),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (item.type == CaptureType.PHOTO) Icons.Default.Image else Icons.Default.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.42f), Color.Transparent, Color.Black.copy(alpha = 0.72f))
                    )
                )
            )
            if (isSelected) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)))
            }

            GalleryTypeBadge(item, Modifier.align(Alignment.TopStart).padding(10.dp))
            if (item.type == CaptureType.VIDEO) {
                GalleryDurationBadge(
                    durationMs = item.durationMs,
                    modifier = Modifier.align(if (selectMode) Alignment.BottomEnd else Alignment.TopEnd).padding(10.dp),
                )
                if (!selectMode) {
                    Surface(modifier = Modifier.align(Alignment.Center).size(44.dp), color = Color.Black.copy(alpha = 0.58f), shape = CircleShape) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.gallery_play_video_cd), tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
            if (selectMode) {
                GallerySelectionCheckbox(isSelected, Modifier.align(Alignment.TopEnd).padding(10.dp))
            }
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(item.fileName, style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(buildCardMetaLine(item), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GalleryTypeBadge(item: CaptureHistory, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(999.dp), color = Color.Black.copy(alpha = 0.54f)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (item.type == CaptureType.PHOTO) Icons.Default.Image else Icons.Default.Movie,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            Text(
                if (item.type == CaptureType.PHOTO) {
                    stringResource(R.string.gallery_type_photo)
                } else {
                    stringResource(R.string.gallery_type_video)
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }
    }
}

@Composable
private fun GalleryDurationBadge(durationMs: Long, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(999.dp), color = Color.Black.copy(alpha = 0.56f)) {
        Text(
            text = formatDuration(durationMs),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun GallerySelectionCheckbox(isSelected: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(28.dp),
        shape = CircleShape,
        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.78f),
        border = if (isSelected) null else BorderStroke(1.5.dp, Color.White.copy(alpha = 0.5f)),
    ) {
        if (isSelected) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.gallery_selected_cd), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun buildCardMetaLine(item: CaptureHistory): String {
    val parts = mutableListOf(formatGalleryTime(item.timestamp))
    if (item.fileSizeBytes > 0) parts += formatFileSize(item.fileSizeBytes)
    if (item.type == CaptureType.VIDEO && item.durationMs > 0) parts += formatDuration(item.durationMs)
    return parts.joinToString("  •  ")
}
