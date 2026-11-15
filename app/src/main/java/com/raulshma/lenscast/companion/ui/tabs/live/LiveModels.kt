package com.raulshma.lenscast.companion.ui.tabs.live

import androidx.annotation.StringRes
import com.raulshma.lenscast.companion.client.dto.ControlRange
import com.raulshma.lenscast.companion.playback.DecodedFrame
import com.raulshma.lenscast.companion.playback.PlaybackRung
import com.raulshma.lenscast.companion.session.ConnectionState
import androidx.media3.common.Player
import org.webrtc.SurfaceViewRenderer

/**
 * The live tab's model vocabulary: the MVI [LiveUiState], the one-shot
 * [LiveEffect]s, the stateless content's action/runtime seams ([LiveActions] /
 * [LiveRuntime]), and the small data rows the cards render. Everything here
 * is plain data — the behavior lives in [LiveViewModel] and the composables
 * stay stateless (the DevicesScreen precedent: data-class-of-lambdas actions,
 * so the Robolectric render test composes the content without a ViewModel).
 */

/** The active playback rung's phase — the video stage's status chip. */
enum class RungPhase { CONNECTING, LIVE, FAILED }

/** One row of the connected-clients card. */
data class StreamClientRow(
    /** The id the kick DELETE targets (the address for web viewers, the server id for RTSP). */
    val id: String,
    /** The human label: remote `address:port` for both kinds. */
    val label: String,
    val kind: ClientKind,
    /** When the client connected (epoch ms); null when the device does not report it (web viewers). */
    val connectedAtMs: Long?,
) {
    enum class ClientKind { WEB, RTSP }
}

/** The manual recording view the controls strip renders (device-polled truth). */
data class RecordingView(
    val isRecording: Boolean,
    val elapsedSeconds: Int,
)

/**
 * The live tab's MVI state: [Unavailable] when the session's device has no
 * roster entry (nothing to dial), else [Ready] with the playback ladder's
 * current rung and every control mirror. One instance per state write — the
 * ViewModel only ever publishes atomic `copy` updates.
 */
sealed interface LiveUiState {

    /** The device is missing from the roster — a render-only shell. */
    data object Unavailable : LiveUiState

    data class Ready(
        /** The session's lifecycle state (drives the talkback enable gate). */
        val connection: ConnectionState = ConnectionState.IDLE,
        /** False for viewer-role pairings — the controls strip is hidden. */
        val canControl: Boolean = false,
        /** True once the first status snapshot landed (sliders come alive). */
        val statusReady: Boolean = false,
        /** The ladder rung playback should be on right now. */
        val rung: PlaybackRung = PlaybackRung.WHEP,
        /** The active rung's own verdict (the stage's status chip). */
        val rungPhase: RungPhase = RungPhase.CONNECTING,
        /** Torch/siren/web-stream mirrors, optimistic around their commands. */
        val torchOn: Boolean = false,
        val sirenActive: Boolean = false,
        val webStreamActive: Boolean = false,
        /** The zoom ratio shown on the slider (preview while dragging). */
        val zoomRatio: Double = 1.0,
        /** The device-reported zoom bounds; null → the composable's fallback. */
        val zoomRange: ControlRange? = null,
        /** Manual-recording state; null until the first status poll lands. */
        val recording: RecordingView? = null,
        val clients: List<StreamClientRow> = emptyList(),
        /** The client id the kick-confirm dialog is armed for (null = closed). */
        val pendingKickId: String? = null,
        /** The live-audio lane is on. */
        val listening: Boolean = false,
        /** A push-to-talk hold is open. */
        val talkbackActive: Boolean = false,
        /** The last talkback dial failed on the cookie-only WS gate. */
        val talkbackNeedsLogin: Boolean = false,
        /** The newest MJPEG decode (only when the MJPEG rung is active). */
        val mjpegFrame: DecodedFrame? = null,
    ) : LiveUiState
}

/** One-shot effects the tab executes outside the state tree: snackbar notices. */
sealed interface LiveEffect {

    /**
     * A transient message. [resId] names the string; [arg] carries the
     * optional format argument — resolved at the snackbar site so the state
     * tree never holds presentation strings.
     */
    data class Notice(
        @StringRes val resId: Int,
        val arg: String? = null,
    ) : LiveEffect
}

/**
 * The user-action vocabulary of the stateless live content — the content's
 * entire write surface, as lambdas, so the render test records taps instead
 * of hosting a ViewModel (the [com.raulshma.lenscast.companion.ui.DevicesActions]
 * precedent).
 */
data class LiveActions(
    val onCycleRung: () -> Unit,
    /** The rung header's PiP affordance — the host activity's enter-PiP call. */
    val onEnterPip: () -> Unit,
    val onTorchToggle: () -> Unit,
    /** Live slider drag: preview only — the device command rides [onZoomCommit]. */
    val onZoomChange: (Double) -> Unit,
    val onZoomCommit: () -> Unit,
    val onSirenToggle: () -> Unit,
    val onWebStreamToggle: () -> Unit,
    val onCapturePhoto: () -> Unit,
    val onRecordingToggle: () -> Unit,
    /** Arms the kick-confirm dialog for one client id (null = cancel). */
    val onRequestKick: (String?) -> Unit,
    val onConfirmKick: () -> Unit,
    val onListenToggle: () -> Unit,
    /** Push-to-talk press/release — the caller owns the mic permission gate. */
    val onTalkStart: () -> Unit,
    val onTalkEnd: () -> Unit,
)

/**
 * The runtime handles the video stage's AndroidViews need beyond the MVI
 * state: the WHEP surface attach seam (the viewer renders straight into a
 * [SurfaceViewRenderer]) and the active HLS [Player] (a PlayerView hosts
 * it directly). Defaults keep the render test's stub trivial.
 */
class LiveRuntime(
    val attachWhepSurface: (SurfaceViewRenderer?) -> Unit = {},
    val hlsPlayer: Player? = null,
)

/** The live tab's compose test tags (unresourced by design — tag strings only). */
object LiveTags {
    /** The torch/siren/stream/zoom/recording strip (admin-only render). */
    const val CONTROLS = "companion_live_controls"

    /** The connected-clients card. */
    const val CLIENTS_CARD = "companion_live_clients"

    /** The listen + push-to-talk row. */
    const val AUDIO_ROW = "companion_live_audio"

    /** The active-rung chip. */
    const val RUNG_CHIP = "companion_live_rung_chip"

    /** The rung-phase status chip. */
    const val PHASE_CHIP = "companion_live_phase_chip"

    /** The tab's scrolling list (the scroll-to seam for the below-fold rows). */
    const val LIST = "companion_live_list"
}

/** `m:ss` for the recording elapsed label (locale-stable digits). */
internal fun formatElapsed(totalSeconds: Int): String =
    String.format(java.util.Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
