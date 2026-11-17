package com.raulshma.lenscast.companion.ui.tabs

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.session.DeviceSession
import com.raulshma.lenscast.companion.ui.ConnectionChip
import com.raulshma.lenscast.companion.ui.DeviceScreenTags
import com.raulshma.lenscast.companion.ui.tabs.live.LiveActions
import com.raulshma.lenscast.companion.ui.tabs.live.LiveContent
import com.raulshma.lenscast.companion.ui.tabs.live.LiveUiState
import com.raulshma.lenscast.companion.ui.tabs.live.LiveEffect
import com.raulshma.lenscast.companion.ui.tabs.live.LiveRuntime
import com.raulshma.lenscast.companion.ui.tabs.live.LiveViewModel

/**
 * The device screen's Live tab (wave 3): the ladder-driven video stage
 * (WHEP → HLS → MJPEG), the admin controls strip, the connected-clients
 * card, and the listen / push-to-talk row — web console parity.
 *
 * Wiring: the [DeviceSession] arrives already running (the DeviceScreen shell
 * owns the start/stop lifecycle); this composable resolves the companion
 * graph off the application, builds the [LiveViewModel] through its Factory
 * (which resolves the roster device — the write-gate's role source — and the
 * session's own API client), and keeps the two halves stateless: rendering
 * lives in [LiveContent], behavior in the ViewModel. Tab visibility gates
 * playback and polls ([LiveViewModel.setActive]) so a background tab holds no
 * viewer slot, no audio lane, and no pollers.
 *
 * Push-to-talk's runtime mic permission is a UI concern and lives here: the
 * first press requests `RECORD_AUDIO` (already declared by the app's
 * manifest — no new permission), and the grant lands the hold.
 *
 * The roster-missing edge (a bare session, or a device removed mid-visit)
 * renders the detached shell — title plus connection chip — instead of
 * crashing on `api()`.
 */
@Composable
fun LiveTab(session: DeviceSession, modifier: Modifier = Modifier) {
    // The same soft-cast shape MediaTab/SystemTab use: the graph is always
    // present in production (MainApplication implements AppGraph), but a
    // host without it (tests, a non-graph process) degrades to the detached
    // shell — the same rendering a missing roster device gets — instead of
    // a ClassCastException.
    val app = LocalContext.current.applicationContext as? AppGraph
    val device = remember(session.deviceId) { app?.companion?.roster?.find(session.deviceId) }
    if (app == null || device == null) {
        LiveTabDetached(session = session, modifier = modifier)
        return
    }
    val context = LocalContext.current
    val viewModel: LiveViewModel = viewModel(
        key = session.deviceId,
        factory = LiveViewModel.Factory(app.companion, session, context.applicationContext),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hlsPlayer by viewModel.hlsPlayer.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // One-shot effects → snackbar. Every Notice copy pre-resolves in
    // composition — stringResource is configuration-aware, while a
    // LocalContext getString captured in the collector would freeze the
    // pre-rotation locale into the snackbar. The Notice vocabulary is closed
    // (the LiveViewModel's verdict mapping), so the collector maps the resId
    // to its resolved copy; collect stays sequential, so the snackbar queue
    // semantics are unchanged.
    val captureOkText = stringResource(R.string.companion_live_capture_ok)
    val kickOkText = stringResource(R.string.companion_live_kick_ok)
    val kickFailedText = stringResource(R.string.companion_live_kick_failed)
    val audioFailedText = stringResource(R.string.companion_live_audio_failed)
    val talkbackFailedText = stringResource(R.string.companion_live_talkback_failed)
    val talkbackNeedsLoginText = stringResource(R.string.companion_live_talkback_needs_login)
    val forbiddenText = stringResource(R.string.companion_live_forbidden)
    val controlAuthText = stringResource(R.string.companion_live_control_auth)
    val controlFailedText = stringResource(R.string.companion_live_control_failed)
    LaunchedEffect(
        viewModel,
        captureOkText, kickOkText, kickFailedText, audioFailedText, talkbackFailedText,
        talkbackNeedsLoginText, forbiddenText, controlAuthText, controlFailedText,
    ) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is LiveEffect.Notice -> {
                    val message = when (effect.resId) {
                        R.string.companion_live_capture_ok -> captureOkText
                        R.string.companion_live_kick_ok -> kickOkText
                        R.string.companion_live_kick_failed -> kickFailedText
                        R.string.companion_live_audio_failed -> audioFailedText
                        R.string.companion_live_talkback_failed -> talkbackFailedText
                        R.string.companion_live_talkback_needs_login -> talkbackNeedsLoginText
                        R.string.companion_live_forbidden -> forbiddenText
                        R.string.companion_live_control_auth -> controlAuthText
                        else -> controlFailedText
                    }
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    // Tab visibility gates playback + polls: leaving the Live tab releases
    // the device's viewer slot and the audio lanes.
    DisposableEffect(session.deviceId) {
        viewModel.setActive(true)
        onDispose { viewModel.setActive(false) }
    }

    // The mic permission gate: the talk affordance lives at this surface.
    val micGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onTalkStart()
    }

    Box(modifier = modifier.fillMaxSize()) {
        LiveContent(
            state = state,
            runtime = LiveRuntime(
                attachWhepSurface = viewModel::attachWhepSurface,
                hlsPlayer = hlsPlayer,
            ),
            actions = LiveActions(
                onCycleRung = viewModel::onCycleRung,
                onEnterPip = { enterPip(context, state) },
                onTorchToggle = viewModel::onTorchToggle,
                onZoomChange = viewModel::onZoomPreview,
                onZoomCommit = viewModel::onZoomCommit,
                onSirenToggle = viewModel::onSirenToggle,
                onWebStreamToggle = viewModel::onWebStreamToggle,
                onCapturePhoto = viewModel::onCapturePhoto,
                onRecordingToggle = viewModel::onRecordingToggle,
                onRequestKick = viewModel::onRequestKick,
                onConfirmKick = viewModel::onConfirmKick,
                onListenToggle = viewModel::onListenToggle,
                onTalkStart = {
                    if (micGranted) {
                        viewModel.onTalkStart()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onTalkEnd = viewModel::onTalkEnd,
            ),
            modifier = Modifier.fillMaxSize(),
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The rung header's PiP affordance: enters picture-in-picture on the host
 * activity. Gated to API 26+ (the platform API's floor — the app's minSdk is
 * 23, so the gate is load-bearing); the affordance itself only renders in the
 * Ready state, which is the "a rung is showing" gate. Params: a 16:9 stage
 * (the device stream's shape) and no custom actions — the PiP window carries
 * no app chrome (the platform's own close/expand buttons are not
 * suppressible). No auto-PiP on user-leave, and no lifecycle changes — the
 * session's lanes keep running while piped (the process is still foreground
 * to the platform).
 */
private fun enterPip(context: Context, state: LiveUiState) {
    if (state !is LiveUiState.Ready) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val activity = context.findActivity() ?: return
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()
    activity.enterPictureInPictureMode(params)
}

/** Walks the context wrapper chain to the host [Activity], null when detached. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The roster-missing shell (the DeviceScreenTest path composes the shell
 * against a bare session): the tab title plus the session's live connection
 * chip and an explanation. Nothing dials — `api()` would throw here.
 */
@Composable
private fun LiveTabDetached(session: DeviceSession, modifier: Modifier = Modifier) {
    val connection by session.connectionState.collectAsStateWithLifecycle()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.companion_tab_live),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(DeviceScreenTags.TAB_TITLE),
        )
        ConnectionChip(connection)
        Text(
            text = stringResource(R.string.companion_device_missing_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
