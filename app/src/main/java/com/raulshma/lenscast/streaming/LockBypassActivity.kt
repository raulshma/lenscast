package com.raulshma.lenscast.streaming

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.raulshma.lenscast.AppGraph
import com.raulshma.lenscast.R
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.ui.theme.LensCastTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The locked-mount camera shim: a translucent, screen-turning-on activity
 * that shows over the keyguard just long enough for the CAMERA-typed
 * foreground promotion and the camera bind to land — both of which Android's
 * while-in-use policy only grants a *visible* app, which a locked device is
 * not. Launched by the full-screen-intent notification
 * ([LockBypassController] posts when [StreamingService]'s streaming
 * promotion or the session's camera bind is denied under lock); the launch
 * itself makes this the visible
 * activity, the resume seam runs, the camera comes up, and the activity
 * dismisses itself — leaving the stream running under the CAMERA-typed
 * foreground service exactly like a stream started before locking, keyguard
 * back on top, no user interaction and no unlock.
 *
 * Nothing here unlocks the device or reads user data: the UI is a static
 * "starting the camera" notice, and the whole episode is bounded by the
 * readiness poll's hard timeout.
 */
class LockBypassActivity : ComponentActivity() {

    private var watcher: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverKeyguard()
        setContent {
            LensCastTheme {
                LockBypassScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as AppGraph
        // The MainActivity resume seam, minus preview: re-assert the streaming
        // foreground promotion while this activity is visible (the CAMERA type
        // is what the while-in-use policy was refusing). The streaming
        // re-assert must go first — ensureServerForeground's server-only mode
        // no-ops while a session is active, and with the web server down
        // (an RTSP/push-only stream) its sync() schedules a stop instead of
        // ever sending the START this activity's visibility exists to legitimize.
        app.streamingSession.reassertStreamingForegroundIfActive()
        app.ensureServerForeground()
        // Retry the bind through the session's own recovery path — the heal
        // rebinding under the keep-alive demand the live session already
        // holds; the camera-state watch corrects its blocked-by-lock verdict
        // from the observed open, not the bind's claimed result.
        lifecycleScope.launch { app.streamingSession.rebindOnUnlock() }
        watchForReadiness()
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        // The wake notification's dismissal epilogue — every exit path
        // (readiness, timeout, system teardown) lands here.
        runCatching {
            (application as? AppGraph)?.lockBypassController?.cancel()
        }
        super.onDestroy()
    }

    /**
     * Show over the keyguard and wake the screen: the API 27+ activity
     * contracts where available, the pre-27 window flags otherwise (minSdk
     * 23), and a keep-screen-on hold so the shim cannot be put to sleep
     * mid-bind on a dozing panel.
     */
    private fun showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Finish as soon as the camera actually came up (the promotion and bind
     * landed AND the camera-open observer confirmed OPEN — this activity's
     * visibility is what makes the open legal, so the bind's claimed Ready
     * alone is not camera access and dismissing on it could pull the rug
     * out from the pending open), when nothing is left to heal (the
     * start rolled back while we were waking — no point showing a spinner
     * over the keyguard), or at the hard timeout (a ROM that denies the
     * camera even here; the blocked-by-lock state is the honest surface).
     */
    private fun watchForReadiness() {
        watcher?.cancel()
        watcher = lifecycleScope.launch {
            val app = application as AppGraph
            withTimeoutOrNull(READY_TIMEOUT_MS) {
                while (true) {
                    val ready = app.cameraService.cameraState.value is CameraState.Ready
                    val open = app.cameraService.cameraOpenObserved.value
                    if (ready && open) break
                    if (!app.streamingManager.isLiveStreaming()) break
                    delay(READY_POLL_MS)
                }
            }
            delay(READY_GRACE_MS)
            finish()
        }
    }

    companion object {
        private const val READY_TIMEOUT_MS = 8_000L
        private const val READY_POLL_MS = 250L
        private const val READY_GRACE_MS = 700L
    }
}

@Composable
private fun LockBypassScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xE6101010),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.lock_bypass_title),
                color = Color.White,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.lock_bypass_message),
                color = Color(0xB3FFFFFF),
                fontSize = 14.sp,
            )
        }
    }
}
