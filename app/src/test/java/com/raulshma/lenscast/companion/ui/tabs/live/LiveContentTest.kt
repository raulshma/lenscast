package com.raulshma.lenscast.companion.ui.tabs.live

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.raulshma.lenscast.companion.session.ConnectionState
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The live tab's stateless render half, under Robolectric (the
 * [com.raulshma.lenscast.companion.ui.DevicesContentTest] hosting seam):
 * composes [LiveContent] directly against seeded [LiveUiState] values with a
 * stubbed video-surface slot — the real slot inflates native-backed views
 * (SurfaceViewRenderer, PlayerView) that cannot load on the JVM.
 *
 * Pinned: the viewer role hides the controls strip while keeping the rung
 * chips and audio row; the admin role shows the strip; the rung chip renders
 * per ladder rung and the phase chip per rung phase; the empty clients card
 * copy; the kick dialog's body; the talkback hold's CONNECTED gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveContentTest {

    private val composeRule: ComposeContentTestRule = createComposeRule()

    /**
     * The compose harness's [ComponentActivity] is declared in the test-scope
     * manifest, which never reaches the binary manifest Robolectric's package
     * manager is built from — add it explicitly before the compose rule
     * resolves and launches it (robolectric#4736; the same seam
     * DevicesContentTest and DeviceScreenTest use).
     */
    private val registerHarnessActivity =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    val app = RuntimeEnvironment.getApplication()
                    Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                        ComponentName(app, ComponentActivity::class.java),
                    )
                    base.evaluate()
                }
            }
        }

    // The registration must run before the compose rule's activity launch —
    // an explicit chain pins the order (a bare pair of @Rule fields does not).
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerHarnessActivity).around(composeRule)

    /** The stub video slot: no native views, one tagged box. */
    @Composable
    private fun stubSurface(state: LiveUiState.Ready, runtime: LiveRuntime, modifier: Modifier) {
        Box(modifier.testTag("stub_stage"))
    }

    private fun noActions() = LiveActions(
        onCycleRung = {},
        onEnterPip = {},
        onTorchToggle = {},
        onZoomChange = {},
        onZoomCommit = {},
        onSirenToggle = {},
        onWebStreamToggle = {},
        onCapturePhoto = {},
        onRecordingToggle = {},
        onRequestKick = {},
        onConfirmKick = {},
        onListenToggle = {},
        onTalkStart = {},
        onTalkEnd = {},
    )

    private fun setContent(state: LiveUiState) {
        composeRule.setContent {
            MaterialTheme {
                LiveContent(
                    state = state,
                    runtime = LiveRuntime(),
                    actions = noActions(),
                    videoSurface = { ready, runtime, modifier -> stubSurface(ready, runtime, modifier) },
                )
            }
        }
    }

    private fun ready(
        canControl: Boolean,
        rung: com.raulshma.lenscast.companion.playback.PlaybackRung =
            com.raulshma.lenscast.companion.playback.PlaybackRung.WHEP,
        phase: RungPhase = RungPhase.CONNECTING,
        connection: ConnectionState = ConnectionState.CONNECTED,
        clients: List<StreamClientRow> = emptyList(),
        pendingKickId: String? = null,
    ) = LiveUiState.Ready(
        connection = connection,
        canControl = canControl,
        rung = rung,
        rungPhase = phase,
        clients = clients,
        pendingKickId = pendingKickId,
    )

    @Test
    fun `the tab title carries the shell's TAB_TITLE tag`() {
        setContent(ready(canControl = false))

        composeRule.onNodeWithTag(com.raulshma.lenscast.companion.ui.DeviceScreenTags.TAB_TITLE)
            .assertTextEquals("Live")
    }

    @Test
    fun `viewer role hides the controls strip but keeps chips, audio row and clients card`() {
        setContent(ready(canControl = false))

        composeRule.onNodeWithTag(LiveTags.CONTROLS).assertDoesNotExist()
        // Read-only surfaces stay: rung + phase chips, cycle, audio row, clients.
        composeRule.onNodeWithTag(LiveTags.RUNG_CHIP).assertTextEquals("WebRTC")
        composeRule.onNodeWithTag(LiveTags.PHASE_CHIP).assertTextEquals("Connecting…")
        composeRule.onNodeWithTag(LiveTags.LIST).assertExists()
        composeRule.onAllNodesWithContentDescription("Switch stream transport").onFirst().assertExists()
        composeRule.onNodeWithTag(LiveTags.AUDIO_ROW).assertExists()
        composeRule.onNodeWithTag(LiveTags.CLIENTS_CARD).assertExists()
        composeRule.onNodeWithText("No clients connected").assertExists()
    }

    @Test
    fun `admin role renders the controls strip`() {
        setContent(ready(canControl = true))

        composeRule.onNodeWithTag(LiveTags.CONTROLS).assertExists()
        composeRule.onNodeWithText("Start stream").assertExists()
        composeRule.onNodeWithText("Record").assertExists()
    }

    @Test
    fun `the rung chip renders per ladder state`() {
        // One setContent, hoisted state: a second setContent would throw
        // (the harness activity settles content once per test).
        var rung by mutableStateOf(com.raulshma.lenscast.companion.playback.PlaybackRung.HLS)
        composeRule.setContent {
            MaterialTheme {
                LiveContent(
                    state = ready(canControl = false, rung = rung),
                    runtime = LiveRuntime(),
                    actions = noActions(),
                    videoSurface = { ready, runtime, modifier -> stubSurface(ready, runtime, modifier) },
                )
            }
        }
        composeRule.onNodeWithTag(LiveTags.RUNG_CHIP).assertTextEquals("HLS")

        rung = com.raulshma.lenscast.companion.playback.PlaybackRung.MJPEG
        composeRule.onNodeWithTag(LiveTags.RUNG_CHIP).assertTextEquals("MJPEG")
    }

    @Test
    fun `the phase chip names the failed rung`() {
        setContent(
            ready(
                canControl = false,
                phase = RungPhase.FAILED,
            ),
        )

        composeRule.onNodeWithTag(LiveTags.PHASE_CHIP).assertTextEquals("Stream unavailable")
    }

    @Test
    fun `client rows render label, kind and the kick affordance`() {
        setContent(
            ready(
                canControl = false,
                clients = listOf(
                    StreamClientRow(
                        id = "192.168.1.20:45110",
                        label = "192.168.1.20:45110",
                        kind = StreamClientRow.ClientKind.WEB,
                        connectedAtMs = null,
                    ),
                    StreamClientRow(
                        id = "rtsp-1",
                        label = "192.168.1.30:51000",
                        kind = StreamClientRow.ClientKind.RTSP,
                        connectedAtMs = 1_700_000_000_000L,
                    ),
                ),
            ),
        )

        // The rows sit below the video stage's fold — scroll the lazy list to
        // each before asserting on it.
        composeRule.onNodeWithTag(LiveTags.LIST)
            .performScrollToNode(hasText("192.168.1.20:45110"))
        composeRule.onAllNodesWithText("192.168.1.20:45110").onFirst().assertExists()
        composeRule.onNodeWithTag(LiveTags.LIST)
            .performScrollToNode(hasText("192.168.1.30:51000"))
        composeRule.onAllNodesWithText("192.168.1.30:51000").onFirst().assertExists()
        composeRule.onAllNodesWithText("Web viewer").onFirst().assertExists()
        // The kind line reads "RTSP · <time>" (the row reports a connect time),
        // so the verdict rides a substring match.
        composeRule.onAllNodesWithText("RTSP", substring = true).onFirst().assertExists()
        composeRule
            .onAllNodesWithContentDescription("Disconnect client 192.168.1.20:45110")
            .onFirst()
            .assertExists()
        composeRule.onNodeWithText("No clients connected").assertDoesNotExist()
    }

    @Test
    fun `the armed kick dialog names the client`() {
        setContent(
            ready(
                canControl = true,
                clients = listOf(
                    StreamClientRow(
                        id = "rtsp-1",
                        label = "192.168.1.30:51000",
                        kind = StreamClientRow.ClientKind.RTSP,
                        connectedAtMs = null,
                    ),
                ),
                pendingKickId = "rtsp-1",
            ),
        )

        composeRule
            .onAllNodesWithText("Disconnect 192.168.1.30:51000 from the camera's stream?")
            .onFirst()
            .assertExists()
    }

    @Test
    fun `the push-to-talk hold is gated on the CONNECTED chip`() {
        var connection by mutableStateOf(ConnectionState.RECONNECTING)
        composeRule.setContent {
            MaterialTheme {
                LiveContent(
                    state = ready(canControl = false, connection = connection),
                    runtime = LiveRuntime(),
                    actions = noActions(),
                    videoSurface = { ready, runtime, modifier -> stubSurface(ready, runtime, modifier) },
                )
            }
        }
        composeRule.onNodeWithText("Talk").assertIsNotEnabled()

        connection = ConnectionState.CONNECTED
        composeRule.onNodeWithText("Talk").assertIsEnabled()
    }

    @Test
    fun `the PiP affordance renders in Ready and fires its action`() {
        var pipRequested = false
        composeRule.setContent {
            MaterialTheme {
                LiveContent(
                    state = ready(canControl = false),
                    runtime = LiveRuntime(),
                    actions = noActions().copy(onEnterPip = { pipRequested = true }),
                    videoSurface = { ready, runtime, modifier -> stubSurface(ready, runtime, modifier) },
                )
            }
        }

        composeRule.onAllNodesWithContentDescription("Picture-in-picture").onFirst().assertExists()
        composeRule.onAllNodesWithContentDescription("Picture-in-picture").onFirst().performClick()
        composeRule.runOnIdle { org.junit.Assert.assertTrue(pipRequested) }
    }

    @Test
    fun `the PiP affordance does not render in the Unavailable state`() {
        setContent(LiveUiState.Unavailable)

        composeRule.onAllNodesWithContentDescription("Picture-in-picture").assertCountEquals(0)
    }
}
