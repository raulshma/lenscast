package com.raulshma.lenscast.companion.console

import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.playback.PlaybackRung
import org.webrtc.SurfaceViewRenderer

/**
 * The remote console's viewfinder: the paired device's live video rendering
 * (WHEP → HLS → MJPEG, [com.raulshma.lenscast.companion.playback.PlayerLadder]-driven),
 * bound to [DeviceConsoleViewModel]'s concrete playback surface and shaped
 * for embedding INSIDE the camera dashboard's preview box (P3). One
 * AndroidView per rung, the retired companion live tab's video-stage
 * plumbing re-homed:
 *
 *  - **WHEP** — a [SurfaceViewRenderer] bound to the ViewModel's published
 *    [DeviceConsoleViewModel.whepViewer]; the renderer's Android lifecycle is
 *    owned HERE (created for this composition, released on dispose — with the
 *    sink detached first), the media session by the viewer.
 *  - **HLS** — a media3 [PlayerView] hosting [DeviceConsoleViewModel.hlsPlayer].
 *  - **MJPEG** — an [ImageView] repainted from the playback state's newest
 *    decoded frame.
 *
 * Phase overlays sit ON TOP as small, corner-anchored chips — connecting
 * spinner, the failed rung's reason with the ladder's state, and the manual
 * rung-cycle affordance ([DeviceConsoleViewModel.onCycleRung], the hook P3's
 * gestures can also reach). **Embedding contract: the stage fills its
 * constraints (callers pass `Modifier.fillMaxSize()`) and keeps every
 * overlay non-areas-blocking by design — chips are small Surfaces at the
 * top edge, never a scrim over the whole stage, so the dashboard's preview
 * gestures (tap-to-focus, pinch-zoom, the countdown tap) keep receiving
 * pointers on the surface underneath.** A failed rung degrades to the error
 * chip only; the surface stays pointer-transparent.
 *
 * Embedded hosts whose chrome already occupies the stage's corners (the
 * camera dashboard's top overlay row) pass `showOverlays = false` and render
 * the rung/phase affordance in their own aesthetics; the media surfaces and
 * the connecting spinner always stay. Default true keeps the stage
 * self-contained for full-screen use.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemotePreviewStage(
    viewModel: DeviceConsoleViewModel,
    modifier: Modifier = Modifier,
    showOverlays: Boolean = true,
) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val hlsPlayer by viewModel.hlsPlayer.collectAsStateWithLifecycle()

    Box(modifier = modifier) {
        when (playback.rung) {
            PlaybackRung.WHEP -> WhepSurface(viewModel, Modifier.fillMaxSize())
            PlaybackRung.HLS -> HlsSurface(hlsPlayer, Modifier.fillMaxSize())
            PlaybackRung.MJPEG -> MjpegSurface(playback.mjpegFrame, Modifier.fillMaxSize())
        }

        if (showOverlays) {
            // Overlays: corner-anchored, pointer-small, never a scrim (the
            // embedding contract above — the dashboard's gestures stay live).
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .testTag(StageTags.OVERLAY_ROW),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhaseChip(playback.phase, playback.failureReason)
            }
            IconButton(
                onClick = viewModel::onCycleRung,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .testTag(StageTags.CYCLE),
            ) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = stringResource(R.string.companion_cycle_rung_cd),
                )
            }
        }
        if (playback.phase == RungPhase.CONNECTING) {
            LoadingIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }
    }
}

@Composable
private fun WhepSurface(viewModel: DeviceConsoleViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewer by viewModel.whepViewer.collectAsStateWithLifecycle()
    // The surface's lifetime is scoped to ONE viewer: this flow re-emits on
    // every rung (re)start (reconnect edge, auto-retry, cycle back), and the
    // replacement's attach must never land on the still-mounted surface —
    // the peer's double-init guard is per-instance
    // (AndroidWhepPeer.initializedRenderer), so a new peer cannot tell the
    // surface was already init()ed by the outgoing peer, against the EglBase
    // that peer's close() releases. The key remounts the surface with the
    // viewer, and Compose disposes the old subtree (detach + release) before
    // composing the new one — release is ordered before re-init, and the new
    // peer always inits a fresh surface.
    key(viewer) {
        val renderer = remember(context) { SurfaceViewRenderer(context) }
        // The renderer's ownership lives entirely HERE: created for this
        // composition and released with it. The sink binds to the viewer
        // published for this subtree.
        DisposableEffect(renderer) {
            onDispose {
                // Detach from whatever viewer is active NOW (a composition-stale
                // capture would miss it), then release — the sink never outlives
                // the stage's ownership, without relying on ViewModel disposal
                // ordering.
                viewModel.detachWhepSurface()
                renderer.release()
            }
        }
        AndroidView(
            factory = { renderer },
            modifier = modifier,
            update = { viewer?.attachRenderer(it) },
        )
    }
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
private fun MjpegSurface(
    frame: com.raulshma.lenscast.companion.playback.DecodedFrame?,
    modifier: Modifier = Modifier,
) {
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

/** The active rung's live verdict — the live tab's phase chip, verbatim styling. */
@Composable
private fun PhaseChip(phase: RungPhase, failureReason: String?, modifier: Modifier = Modifier) {
    val (labelRes, colors) = when (phase) {
        RungPhase.CONNECTING ->
            R.string.companion_rung_connecting to (
                MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                )
        RungPhase.LIVE ->
            R.string.companion_connected to (
                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                )
        RungPhase.FAILED ->
            R.string.companion_rung_failed to (
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                )
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.first,
        contentColor = colors.second,
        modifier = modifier.testTag(StageTags.PHASE_CHIP),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLargeEmphasized,
                fontWeight = FontWeight.SemiBold,
            )
            if (phase == RungPhase.FAILED && !failureReason.isNullOrBlank()) {
                Text(
                    text = failureReason,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.width(180.dp),
                )
            }
        }
    }
}

/** The remote preview stage's compose test tags (unresourced — tag strings only). */
object StageTags {
    /** The overlay chip row (top-start). */
    const val OVERLAY_ROW = "companion_console_stage_overlays"

    /** The rung-phase status chip. */
    const val PHASE_CHIP = "companion_console_stage_phase_chip"

    /** The manual rung-cycle affordance. */
    const val CYCLE = "companion_console_stage_cycle"
}
