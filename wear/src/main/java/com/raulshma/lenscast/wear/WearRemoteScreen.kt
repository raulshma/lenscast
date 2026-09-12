package com.raulshma.lenscast.wear

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The remote screen: status header, stream toggle, photo button, snapshot
 * pane, settings door — one ScalingLazyColumn that works on round and
 * rectangular watches alike.
 *
 * Every control renders straight from the controller's [UiState]: the
 * stream button mirrors the last status poll (never an optimistic guess),
 * busy states disable their buttons, and errors render as short text next
 * to the control that failed. The snapshot pane is the only periodically
 * refreshed surface; its manual refresh doubles as the retry path.
 */
@Composable
fun RemoteScreen(
    controller: WearRemoteController,
    onOpenSettings: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(timeText = { TimeText() }) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = rememberScalingLazyListState(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.alert?.let { alert ->
                    item {
                        AlertBannerCard(
                            alert = alert,
                            snapshot = state.snapshot,
                            onDismiss = { controller.dismissAlert() },
                        )
                    }
                }
                item { StatusHeader(state.status) }
            item {
                StreamToggleButton(
                    state = state,
                    onToggle = { scope.launch { controller.toggleStream(state.activeNow()) } },
                )
            }
            item {
                PhotoButton(
                    capture = state.capture,
                    onCapture = { scope.launch { controller.capturePhoto() } },
                )
            }
            item {
                SnapshotPane(
                    snapshot = state.snapshot,
                    onRefresh = { scope.launch { controller.refreshSnapshot(manual = true) } },
                )
            }
            item {
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF333236)),
                    modifier = Modifier.height(32.dp),
                ) { Text("Settings", fontSize = 12.sp) }
            }
        }
    }
}

/**
 * The status header: one compact truth line about the phone — stream state,
 * client count while streaming, battery, camera state. The poll's Error
 * state reads "Unreachable" so a dead WiFi hop is unmistakable.
 */
@Composable
private fun StatusHeader(status: RequestState<WearStatus>) {
    val text = when (status) {
        is RequestState.Success -> {
            val s = status.value
            val stream = when {
                s.streamingActive && s.clientCount > 0 -> "Streaming (${s.clientCount})"
                s.streamingActive -> "Streaming"
                else -> "Idle"
            }
            val charge = if (s.batteryCharging) " charging" else ""
            "$stream · Batt ${s.batteryLevel}%$charge · ${s.cameraState}"
        }
        is RequestState.Loading -> "Checking phone…"
        is RequestState.Error -> "Unreachable: ${status.message}"
        RequestState.Idle -> "LensCast"
    }
    Text(
        text = text,
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.8f),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/**
 * The big stream button: label and color are the phone's mirrored state —
 * green "Start Stream" while idle, red "Stop Stream" while active. The
 * toggle's own busy state disables it for the round trip so a double tap
 * cannot fire two commands; while the poll is unreachable the button rests
 * disabled (the 5s poll repairs the mirror on its own).
 */
@Composable
private fun StreamToggleButton(
    state: WearRemoteController.UiState,
    onToggle: () -> Unit,
) {
    val status = state.status
    val toggleBusy = state.streamToggle is RequestState.Loading
    val (label, bg) = when (status) {
        is RequestState.Success -> if (status.value.streamingActive) {
            "Stop Stream" to Color(0xFFB3261E)
        } else {
            "Start Stream" to Color(0xFF1E6B32)
        }
        is RequestState.Loading -> "Checking…" to Color(0xFF44464F)
        is RequestState.Error -> "Unreachable" to Color(0xFF44464F)
        RequestState.Idle -> "Waiting…" to Color(0xFF44464F)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onToggle,
            enabled = !toggleBusy && status is RequestState.Success,
            colors = ButtonDefaults.buttonColors(backgroundColor = bg),
            modifier = Modifier.width(140.dp).height(56.dp),
        ) {
            if (toggleBusy) {
                CircularProgressIndicator()
            } else {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        val toggleError = (state.streamToggle as? RequestState.Error)?.message
        if (toggleError != null) {
            Text(
                text = toggleError,
                fontSize = 11.sp,
                color = MaterialTheme.colors.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The photo button with its confirmation moment: a spring scale-in plus a
 * short green "Captured" phase that the LaunchedEffect timer retires, so
 * the resting look always comes back without user input.
 */
@Composable
private fun PhotoButton(
    capture: RequestState<Unit>,
    onCapture: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (confirming) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.4f),
        label = "captureConfirm",
    )
    // Confirmation lifetime: enter on Success, retire after the on-screen moment.
    LaunchedEffect(capture) {
        if (capture is RequestState.Success) {
            confirming = true
            delay(CAPTURE_CONFIRM_MS)
            confirming = false
        }
    }
    val busy = capture is RequestState.Loading
    val error = (capture as? RequestState.Error)?.message
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onCapture,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = if (confirming) Color(0xFF1E6B32) else Color(0xFF1565C0)
            ),
            modifier = Modifier.width(140.dp).height(44.dp).scale(scale),
        ) {
            if (busy) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = if (confirming) "Captured" else "Photo",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (error != null) {
            Text(
                text = error,
                fontSize = 11.sp,
                color = MaterialTheme.colors.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The snapshot pane: the live-feel view of the phone's last preview frame.
 * Success shows the JPEG with its frame timestamp and a one-second age
 * ticker; the manual refresh button under the error card is the retry
 * path — a failed background refresh never blanks a still-good frame.
 */
@Composable
private fun SnapshotPane(
    snapshot: RequestState<SnapshotFrame>,
    onRefresh: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SNAPSHOT_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1C1B1F)),
            contentAlignment = Alignment.Center,
        ) {
            when (snapshot) {
                is RequestState.Success -> {
                    Image(
                        bitmap = snapshot.value.bitmap.asImageBitmap(),
                        contentDescription = "Phone preview frame",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                is RequestState.Loading -> CircularProgressIndicator()
                is RequestState.Error -> Text(
                    text = "No frame: ${snapshot.message}",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.padding(8.dp),
                )
                RequestState.Idle -> Text(
                    text = "No frame yet",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                )
            }
        }
        when (snapshot) {
            is RequestState.Success -> {
                val nowMs = rememberNowMs()
                val frame = snapshot.value
                Text(
                    text = "Frame ${formatTime(frame.fetchedAtMs)} · ${ageLabel(frame.fetchedAtMs, nowMs)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.75f),
                )
            }
            is RequestState.Error -> {
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.height(34.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF44464F)),
                ) { Text("Refresh", fontSize = 12.sp) }
            }
            else -> {}
        }
    }
}

// ── Small pure helpers ──

/**
 * The detection-alert banner: the newest event from the phone, rendered as
 * a dismissible card at the top of the remote — event title (motion /
 * sound / tamper), the label/zone detail line, the live snapshot pane's
 * last frame as the thumbnail, a one-second age ticker, and a Dismiss
 * button. Dismissing only clears the card; the notification (if posted)
 * stays until its own dismissal.
 */
@Composable
private fun AlertBannerCard(
    alert: WearDetectionEvent,
    snapshot: RequestState<SnapshotFrame>,
    onDismiss: () -> Unit,
) {
    val nowMs = rememberNowMs()
    val detail = WearAlertPolicy.detailLine(alert).ifBlank { stringResource(R.string.banner_no_detail) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF4A2B26))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(eventTitleRes(alert.type)),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFB4A9),
        )
        val frame = (snapshot as? RequestState.Success)?.value
        if (frame != null) {
            Image(
                bitmap = frame.bitmap.asImageBitmap(),
                contentDescription = "Event snapshot",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .width(72.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        Text(
            text = detail,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.85f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = ageText(WearAlertPolicy.relativeAge(alert.timestampMs, nowMs)),
            fontSize = 10.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        )
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF44464F)),
            modifier = Modifier
                .padding(top = 4.dp)
                .height(28.dp),
        ) { Text(stringResource(R.string.banner_dismiss), fontSize = 11.sp) }
    }
}

/** The age bucket → its string template (the banner's one-second ticker). */
@Composable
private fun ageText(bucket: WearAlertPolicy.AgeBucket): String = when (bucket) {
    WearAlertPolicy.AgeBucket.Now -> stringResource(R.string.age_now)
    is WearAlertPolicy.AgeBucket.Seconds -> stringResource(R.string.age_seconds, bucket.value)
    is WearAlertPolicy.AgeBucket.Minutes -> stringResource(R.string.age_minutes, bucket.value)
    is WearAlertPolicy.AgeBucket.Hours -> stringResource(R.string.age_hours, bucket.value)
}

/** The last known stream state, or false before the first poll lands. */
private fun WearRemoteController.UiState.activeNow(): Boolean =
    (status as? RequestState.Success)?.value?.streamingActive ?: false

/** A wall-clock value that re-reads every second, for the frame-age label. */
@Composable
private fun rememberNowMs(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** The fetched-at instant as a wall-clock HH:mm:ss. */
private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

/** Frame age as a short human label ("now", "12s", "3m+"). */
private fun ageLabel(fetchedAtMs: Long, nowMs: Long): String {
    val ageS = ((nowMs - fetchedAtMs) / 1_000).coerceAtLeast(0)
    return when {
        ageS < 3 -> "now"
        ageS < 90 -> "${ageS}s"
        else -> "${ageS / 60}m+"
    }
}

/** Auto-clear delay for the photo confirmation phase. */
private const val CAPTURE_CONFIRM_MS = 1_400L

/** The pane's fixed height — portrait frames letterbox inside it. */
private const val SNAPSHOT_HEIGHT_DP = 110
