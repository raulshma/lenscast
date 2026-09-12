package com.raulshma.lenscast.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
import kotlinx.coroutines.launch

/**
 * The connection settings screen: host, port, auth mode (API token or Basic
 * credentials), and the live test. Everything typed here persists through
 * [WearSettingsStore] when Save is tapped — and the test saves first, so
 * the wire path it exercises is exactly the config the remote will use.
 *
 * The test ladder: GET /api/status is the verdict (reachable + authorized);
 * on success, GET /api/system contributes the identity line —
 * "Pixel 8 · LensCast 0.1.1" — degrading to a bare "Connected" summary when
 * the diagnostics route is unavailable.
 *
 * Wear text input is clumsy but one-time; the fields are deliberately few
 * and every one is optional except the host, matching the server's own
 * optional-auth posture. Field captions ride ABOVE each box (small text)
 * instead of in-box labels: the caption is always visible regardless of the
 * text field's internal layout.
 *
 * The detection-alerts opt-in lives here too (default ON). The API 33+
 * POST_NOTIFICATIONS runtime permission is requested lazily — only at the
 * moment the user flips alerts ON — mirroring the phone app's ask-when-used
 * posture; the grant result surfaces as an inline hint, and the alert post
 * itself is a silent no-op without the grant.
 */
class SettingsActivity : ComponentActivity() {

    /** The latest POST_NOTIFICATIONS grant, surfaced in the alerts section. */
    private val notificationGranted = mutableStateOf<Boolean?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationGranted.value = granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsStore = WearSettingsStore(applicationContext)
        val client = WearApiClient(settingsStore)

        setContent {
            MaterialTheme {
                SettingsScreen(
                    settingsStore = settingsStore,
                    client = client,
                    notificationGranted = notificationGranted,
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notificationGranted.value = true
                        }
                    },
                )
            }
        }
    }
}

/** The editable form + test verdict surface, seeded from the stored settings. */
@Composable
private fun SettingsScreen(
    settingsStore: WearSettingsStore,
    client: WearApiClient,
    notificationGranted: MutableState<Boolean?>,
    onRequestNotificationPermission: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // The draft settings: seeded once from DataStore, edited locally, saved
    // explicitly (Save, or the Test button which persists before reaching).
    var draft by remember { mutableStateOf<WearSettings?>(null) }
    var testState by remember { mutableStateOf<RequestState<String>>(RequestState.Idle) }

    LaunchedEffect(Unit) { draft = settingsStore.current() }

    val settings = draft // one frame while DataStore's first read is in flight

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberScalingLazyListState(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (settings != null) {
                item { FormHeader("Connection") }
                item {
                    FormField(
                        caption = "Host / IP",
                        value = settings.host,
                        onValueChange = { draft = settings.copy(host = it) },
                    )
                }
                item {
                    FormField(
                        caption = "Port",
                        value = settings.port.toString(),
                        onValueChange = { raw ->
                            val digits = raw.filter(Char::isDigit).take(5)
                            draft = settings.copy(port = digits.toIntOrNull() ?: WearSettings.DEFAULT_PORT)
                        },
                    )
                }
                item { FormHeader("Auth") }
                item {
                    // Mode cycling: two options, one button — the smallest
                    // reliable selector on a watch crown.
                    Button(
                        onClick = {
                            val next = if (settings.authMode == AuthMode.API_TOKEN) AuthMode.BASIC else AuthMode.API_TOKEN
                            draft = settings.copy(authMode = next)
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF333236)),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text(
                            text = when (settings.authMode) {
                                AuthMode.API_TOKEN -> "Mode: API Token"
                                AuthMode.BASIC -> "Mode: Basic"
                            },
                            fontSize = 12.sp,
                        )
                    }
                }
                if (settings.authMode == AuthMode.BASIC) {
                    item {
                        FormField(
                            caption = "Username",
                            value = settings.username,
                            onValueChange = { draft = settings.copy(username = it) },
                        )
                    }
                    item {
                        FormField(
                            caption = "Password",
                            value = settings.password,
                            onValueChange = { draft = settings.copy(password = it) },
                        )
                    }
                } else {
                    item {
                        FormField(
                            caption = "API Token",
                            value = settings.apiToken,
                            onValueChange = { draft = settings.copy(apiToken = it) },
                        )
                    }
                }
                item { FormHeader(stringResource(R.string.settings_section_alerts)) }
                item {
                    // The alerts opt-in: default ON, one tap to flip. A flip
                    // to ON lazily asks for the notification permission;
                    // both directions persist with Save/Test like the rest
                    // of the form, and the poll loop picks the change up on
                    // its next lap.
                    Button(
                        onClick = {
                            val enabling = !settings.alertsEnabled
                            draft = settings.copy(alertsEnabled = enabling)
                            if (enabling) onRequestNotificationPermission()
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (settings.alertsEnabled) Color(0xFF1E6B32) else Color(0xFF333236)
                        ),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (settings.alertsEnabled) R.string.settings_alerts_on
                                else R.string.settings_alerts_off
                            ),
                            fontSize = 12.sp,
                        )
                    }
                }
                item {
                    val granted = notificationGranted.value
                    Text(
                        text = stringResource(
                            when {
                                settings.alertsEnabled && granted == false -> R.string.settings_alert_permission_denied
                                else -> R.string.settings_alerts_hint
                            }
                        ),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        color = if (settings.alertsEnabled && granted == false) {
                            MaterialTheme.colors.error
                        } else {
                            MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    Button(
                        onClick = { scope.launch { settingsStore.save(settings) } },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0)),
                        modifier = Modifier.height(40.dp),
                    ) { Text("Save", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
                item {
                    val testing = testState is RequestState.Loading
                    Button(
                        onClick = {
                            scope.launch {
                                // Test the SAVED config: persist first, then reach.
                                settingsStore.save(settings)
                                testState = RequestState.Loading
                                testState = runTest(client)
                            }
                        },
                        enabled = !testing,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1E6B32)),
                        modifier = Modifier.height(40.dp),
                    ) {
                        if (testing) CircularProgressIndicator()
                        else Text("Test", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                when (val t = testState) {
                    is RequestState.Success -> item {
                        Text(
                            text = "OK: ${t.value}",
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = Color(0xFF81C784),
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    is RequestState.Error -> item {
                        Text(
                            text = "Failed: ${t.message}",
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colors.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

/** The test ladder, extracted from the button for readability: /api/status is the verdict, /api/system the identity bonus. */
private suspend fun runTest(client: WearApiClient): RequestState<String> = try {
    val (status, system) = client.testConnection()
    val identity = system?.let {
        listOfNotNull(
            it.deviceModel.takeIf(String::isNotBlank),
            "LensCast ${it.appVersion}".takeIf { _ -> it.appVersion.isNotBlank() },
        ).joinToString(" · ")
    }
    RequestState.Success(
        identity ?: "Connected · stream ${if (status.streamingActive) "active" else "idle"}"
    )
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    RequestState.Error(e.message ?: "Unreachable")
}

/**
 * One captioned text field: small caption above, box below, full-width.
 *
 * BasicTextField (compose foundation) rather than a Wear Material text
 * field — the stable Wear Compose 1.4.0 material artifact ships no
 * TextField, and the module deliberately avoids the Material 3 alpha line.
 * Styled to the watch theme by hand: themed cursor + text, inset box.
 */
@Composable
private fun FormField(
    caption: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth(0.92f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = caption,
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colors.onBackground,
                fontSize = 14.sp,
            ),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(Color(0xFF232227), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 12.dp),
        )
    }
}

/** Small caps section label between field groups. */
@Composable
private fun FormHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
    )
}
