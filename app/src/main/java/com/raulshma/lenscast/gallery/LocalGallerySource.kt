package com.raulshma.lenscast.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.raulshma.lenscast.R
import com.raulshma.lenscast.capture.CaptureMediaResolver
import com.raulshma.lenscast.capture.DecryptedPhotoCache
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.core.MediaCrypto
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

/**
 * The gallery's LOCAL backend: [GallerySource] over the graph singletons the
 * screens have always read — [CaptureHistoryStore] (the library truth, the
 * favorite write, the quota bar) and the app-scoped [GallerySession] (the
 * soft-delete staging, the warm-decryption maps). Behavior is today's
 * verbatim: deletes stage for the undo window ([deleteIsUndoable] = true),
 * the store loads the whole library so [hasMore] stays false and the
 * media-type filter stays a client-side concern, and every mutation gate
 * reads true — the local store honors all of them.
 *
 * The media slots decode this phone's bytes: Coil over the recorded path /
 * content URI (or the decrypted cache file for encrypted-at-rest photos),
 * and the same ExoPlayer ladder the viewer always used, including the
 * decrypting stream for encrypted videos. The staging/decryption flows are
 * read through [session] inside the slots — they stay session-owned, and
 * the grid/viewer routes keep seeing one shared truth without re-exposing
 * the maps on the interface (nothing outside the slots consumes them).
 */
class LocalGallerySource(
    private val store: CaptureHistoryStore,
    private val session: GallerySession,
    /** The media key provider behind the encrypted-video playback stream. */
    private val mediaKeyProvider: MediaCrypto.KeyProvider,
    /** The scope the derived quota bar and the exit-flush deletes run on. */
    private val scope: CoroutineScope,
) : GallerySource {

    override val items: StateFlow<List<CaptureHistory>> = store.history

    override val hasMore: StateFlow<Boolean> = MutableStateFlow(false)

    // The store's list IS the whole library — absence is its own
    // confirmation, and staging clears the selection synchronously, so no
    // separate confirmed-delete signal exists locally.
    override val confirmedDeletedIds: StateFlow<Set<String>> = MutableStateFlow(emptySet())

    override val stagedIds: StateFlow<Set<String>> = session.stagedIds

    override val storageBar: StateFlow<CaptureHistoryStore.StorageBar?> =
        store.history
            .map { store.storageBar() }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), store.storageBar())

    override val canDelete: Boolean = true
    override val canFavorite: Boolean = true
    override val canShareExternally: Boolean = true
    override val deleteIsUndoable: Boolean = true

    override fun refresh() {
        store.refreshFromMediaStore()
    }

    override fun loadMore() = Unit // the store holds the whole library; hasMore is pinned false

    override fun setMediaTypeFilter(type: CaptureType?) = Unit

    override fun toggleFavorite(id: String) {
        val entry = store.history.value.find { it.id == id } ?: return
        store.setFavorite(id, !entry.favorite)
    }

    override fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        session.stage(ids)
    }

    override fun undoStaged(ids: List<String>) {
        session.undoStaged(ids)
    }

    override fun enforceQuota() {
        store.enforceQuota()
    }

    override fun flush() {
        session.flush()
    }

    // ── Media render slots ──

    @Composable
    override fun thumbnail(item: CaptureHistory, modifier: Modifier) {
        val decrypted by session.decryptedPhotos.collectAsState()
        val encryptedVideos by session.encryptedVideoIds.collectAsState()
        // Encrypted-at-rest videos have no frameable bytes for Coil — the
        // placeholder stays until playback decrypts; encrypted photos load
        // through their decrypted cache file instead.
        val showPlaceholder = item.type == CaptureType.VIDEO && item.id in encryptedVideos
        val imageModel = decrypted[item.id] ?: resolveMediaModel(item.filePath)
        if (imageModel != null && !showPlaceholder) {
            // The fallback icon rides the whole load, not just the absent
            // model: Coil paints nothing while a request is in flight and on
            // a failed decode (an encrypted photo ahead of its warm entry, a
            // MediaStore-evicted file), and both used to read as a blank
            // tile. The icon stays up until a Success paints over it, on the
            // else-branch's backdrop; a model change (the decrypt landing)
            // restarts the state with it.
            var decoded by remember(imageModel) { mutableStateOf(false) }
            Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(imageModel).crossfade(true).build(),
                    contentDescription = item.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onState = { state -> decoded = state is AsyncImagePainter.State.Success },
                )
                if (!decoded) {
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (item.type == CaptureType.PHOTO) Icons.Default.PhotoCamera else Icons.Default.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (item.type == CaptureType.PHOTO) Icons.Default.PhotoCamera else Icons.Default.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    override fun photo(item: CaptureHistory, modifier: Modifier) {
        val context = LocalContext.current
        val decrypted by session.decryptedPhotos.collectAsState()
        // Cold-cache direct entry: the session's warm loop fills
        // decryptedPhotos from the store, but a viewer opened straight onto an
        // encrypted photo can beat it (no grid tile composes first to warm
        // the map), and the old fallback then handed Coil the ciphertext
        // path, which never decodes. When the warm map has no entry yet, run
        // the established decrypt HERE and bind the cache file it lands —
        // this handle targets the same cache directory the session's ensure
        // writes, so the two are one cache (per-call uniquely named .part
        // files keep concurrent ensures safe). An already-cached photo keeps
        // today's model; a null verdict (plaintext, missing) falls through to
        // the original model and the unavailable slot as before.
        val photoCache = remember(context) {
            DecryptedPhotoCache(context, CaptureMediaResolver(context.contentResolver, mediaKeyProvider))
        }
        var decryptedHere by remember(item.id) { mutableStateOf<File?>(null) }
        LaunchedEffect(item.id) {
            if (decrypted[item.id] == null) {
                decryptedHere = photoCache.ensure(item.id, item.fileName, item.filePath)
            }
        }
        val imageModel = decrypted[item.id] ?: decryptedHere ?: resolveMediaModel(item.filePath)
        if (imageModel != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(imageModel).crossfade(true).build(),
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.Fit,
            )
        } else {
            ViewerUnavailable(icon = Icons.Default.PhotoCamera, label = stringResource(R.string.gallery_image_unavailable))
        }
    }

    @Composable
    // media3's MediaSource surface (the encrypted-video factory) is
    // @UnstableApi-annotated — the same deliberate opt-in as the rungs.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun video(item: CaptureHistory, modifier: Modifier) {
        val context = LocalContext.current
        val encryptedVideos by session.encryptedVideoIds.collectAsState()
        // The four-way display verdict, resolved as a type: encryption wins by
        // TYPE inside resolveViewerMedia, so this when is exhaustive and
        // order-free — the screen's old branch order, verbatim.
        val resolved = resolveViewerMedia(item.filePath, item.id in encryptedVideos)
        val exoPlayer = remember {
            androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            }
        }
        // Keyed on the resolved verdict AND the file path: every encrypted
        // video resolves to the same EncryptedVideo data object, so a
        // same-composition item swap — a confirmed delete shifting the pager's
        // current page onto the neighbor, with no per-item page key in the
        // pager — must still restart this binding for the new media source.
        // The plain-file/scheme verdicts already differ by equality; the path
        // keys every swap explicitly.
        DisposableEffect(resolved, item.filePath) {
            when (resolved) {
                // Encrypted at rest: the media never exists as plaintext bytes
                // the player could open, so playback rides the decrypting
                // DataSource (seeks are sequential decrypt-and-discard — see
                // the source).
                ResolvedMedia.EncryptedVideo -> {
                    val resolver = CaptureMediaResolver(
                        context.contentResolver,
                        mediaKeyProvider,
                    )
                    val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                        DecryptingDataSourceFactory(resolver, item.filePath),
                    ).createMediaSource(
                        androidx.media3.common.MediaItem.fromUri(
                            android.net.Uri.parse("lenscast://decrypted/${item.filePath}")
                        ),
                    )
                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
                is ResolvedMedia.SchemeUri -> {
                    exoPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(resolved.uri))
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
                is ResolvedMedia.PlainFile -> {
                    exoPlayer.setMediaItem(
                        androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(resolved.file))
                    )
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
                ResolvedMedia.Missing -> Unit
            }
            onDispose { exoPlayer.stop() }
        }
        DisposableEffect(Unit) {
            onDispose { exoPlayer.release() }
        }

        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            when (resolved) {
                ResolvedMedia.Missing ->
                    ViewerUnavailable(icon = Icons.Default.Videocam, label = stringResource(R.string.gallery_video_unavailable))
                else ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            androidx.media3.ui.PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = true
                            }
                        },
                    )
            }
        }
    }
}

/**
 * The viewer's media-unavailable slot (shared by the local photo/video slots;
 * the screen's private composable, promoted to package-internal when the
 * slot seam moved the decode out of the screen).
 */
@Composable
internal fun ViewerUnavailable(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
    }
}
