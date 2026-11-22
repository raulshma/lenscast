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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.ui.animation.LocalAnimatedVisibilityScope
import com.raulshma.lenscast.ui.animation.LocalSharedTransitionScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** The grid scrolls this close to its end before the next page fires. */
private const val LOAD_MORE_THRESHOLD = 8

@Composable
fun GalleryMediaGrid(
    sections: List<GallerySection>,
    selectMode: Boolean,
    selectedIds: Set<String>,
    /**
     * The tile media slot — the [GallerySource]'s thumbnail renderer. The
     * grid owns the frame (shared-element modifier, overlays, badges); the
     * slot owns the decode and its placeholder.
     */
    thumbnail: @Composable (item: CaptureHistory, modifier: Modifier) -> Unit,
    onItemClick: (CaptureHistory) -> Unit,
    onItemLongClick: (CaptureHistory) -> Unit,
    /** Star tap: flips the item's persisted favorite flag. */
    onToggleFavorite: (CaptureHistory) -> Unit = {},
    /**
     * Whether the star badge is an actual toggle. The remote wire carries
     * `favorite` read-only: the badge keeps showing as an indicator (a
     * device-side star), but tapping it must not advertise a write that
     * cannot happen.
     */
    canToggleFavorite: Boolean = true,
    /** The grid neared its end — fetch the next page (a no-op on the local source). */
    onLoadMore: () -> Unit = {},
    /**
     * The underlying library's size — the paging signal: every fetched page
     * appends to it, so a page that filters to no visible tiles (Favorites
     * and search narrow client-side; the wire has no filter for them) still
     * re-arms the near-end trigger even though the grid's own counts never
     * changed. Without it, paging would stall on the first nonmatching page.
     */
    librarySize: Int = 0,
) {
    val gridState = rememberLazyGridState()

    // Load-more on scroll end: fires when the last visible tile comes within
    // the threshold of the grid's end. The flow emits the total item count
    // TOGETHER with the last visible index — not a collapsed boolean — so a
    // page that lands while the grid is still near its end re-emits (the
    // changed count), letting the next page request through even though the
    // near-end verdict never flipped false in between. The effect also
    // restarts on [librarySize]: a page whose items all filter out leaves
    // the grid's own counts unchanged, and the library's growth is what
    // re-arms the trigger so the following page is still requested. The
    // near-end condition reads downstream of the emission; the source
    // re-entrancy-guards, so the snapshot firing repeatedly costs nothing
    // until a page actually lands.
    LaunchedEffect(gridState, sections.size, librarySize, onLoadMore) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            info.totalItemsCount to lastVisible
        }
            .distinctUntilChanged()
            .filter { (totalItems, lastVisible) ->
                totalItems > 0 && lastVisible >= totalItems - LOAD_MORE_THRESHOLD
            }
            .collect { onLoadMore() }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 124.dp),
        state = gridState,
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
                    thumbnail = thumbnail,
                    onClick = { onItemClick(item) },
                    onLongClick = { onItemLongClick(item) },
                    onToggleFavorite = { onToggleFavorite(item) },
                    canToggleFavorite = canToggleFavorite,
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
            // The section model's title token ("Today"/"Yesterday") maps to
            // localized text here at the render layer, not in the pure math.
            Text(localizedSectionTitle(section.title), style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.onBackground)
            Text(section.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = pluralStringResource(
                R.plurals.gallery_section_item_count,
                section.items.size,
                section.items.size,
                localizedFileSize(section.totalBytes)
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
    /** The source's tile media slot (decode + placeholder live there). */
    thumbnail: @Composable (item: CaptureHistory, modifier: Modifier) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit = {},
    /** Whether the star badge doubles as a real toggle (wire-read-only: false). */
    canToggleFavorite: Boolean = true,
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
            thumbnail(
                item,
                Modifier
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
            )

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
            if (!selectMode && item.favorite) {
                // The favorite badge doubles as the grid's star toggle, riding
                // the type badge's top-start corner. Where the backend carries
                // the flag read-only (the remote wire), the badge stays as an
                // indicator and the toggle affordance is disabled, not hidden
                // — a device-side star is still information, so its label reads
                // as the STATE ("Favorited"), never as the unfavorite action a
                // tap there cannot perform.
                Surface(
                    onClick = onToggleFavorite,
                    enabled = canToggleFavorite,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 64.dp, top = 10.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.54f),
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = stringResource(
                            if (canToggleFavorite) {
                                R.string.gallery_unfavorite_cd
                            } else {
                                R.string.gallery_favorited_cd
                            },
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(5.dp).size(16.dp),
                    )
                }
            }
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
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = Color.White,
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
