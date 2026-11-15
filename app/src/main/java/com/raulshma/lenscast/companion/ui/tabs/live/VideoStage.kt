package com.raulshma.lenscast.companion.ui.tabs.live

import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.raulshma.lenscast.companion.playback.PlaybackRung
import org.webrtc.SurfaceViewRenderer

/**
 * The real video stage: one AndroidView per rung, chosen by the state's
 * current [PlaybackRung].
 *
 *  - **WHEP** — a [SurfaceViewRenderer] the ViewModel forwards to the active
 *    [com.raulshma.lenscast.companion.playback.WhepViewer]; the renderer's
 *    Android lifecycle is owned here (release on dispose), the media session
 *    by the viewer.
 *  - **HLS** — a media3 [PlayerView] hosting the ViewModel's active player
 *    (built by [com.raulshma.lenscast.companion.playback.HlsRung.create] on
 *    the shared, pinned engine).
 *  - **MJPEG** — an [ImageView] repainted from the state's newest decoded
 *    frame (the recomposition driver is the frame mirror in the ViewModel).
 *
 * The slot is injectable in [LiveContent] so the Robolectric render test never
 * inflates the native-backed renderer views.
 */
@Composable
fun DefaultVideoSurface(state: LiveUiState.Ready, runtime: LiveRuntime, modifier: Modifier = Modifier) {
    when (state.rung) {
        PlaybackRung.WHEP -> WhepSurface(runtime, modifier)
        PlaybackRung.HLS -> HlsSurface(runtime.hlsPlayer, modifier)
        PlaybackRung.MJPEG -> MjpegSurface(state.mjpegFrame, modifier)
    }
}

@Composable
private fun WhepSurface(runtime: LiveRuntime, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember(context) { SurfaceViewRenderer(context) }
    DisposableEffect(renderer) {
        onDispose {
            runtime.attachWhepSurface(null)
            renderer.release()
        }
    }
    AndroidView(
        factory = { renderer },
        modifier = modifier,
        update = { runtime.attachWhepSurface(it) },
    )
}

@Composable
private fun HlsSurface(player: androidx.media3.common.Player?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember(context) {
        PlayerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            useController = false
        }
    }
    DisposableEffect(view) {
        onDispose { view.player = null }
    }
    AndroidView(
        factory = { view },
        modifier = modifier,
        update = { it.player = player },
    )
}

@Composable
private fun MjpegSurface(frame: com.raulshma.lenscast.companion.playback.DecodedFrame?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember(context) {
        ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }
    AndroidView(
        factory = { view },
        modifier = modifier,
        update = { it.setImageBitmap(frame?.bitmap) },
    )
}
