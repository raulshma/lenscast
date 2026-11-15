package com.raulshma.lenscast.companion.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.ui.theme.LensCastTheme
import org.junit.Assert.assertEquals
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
 * The devices screen's stateless render half, under Robolectric: the empty
 * roster shows the empty state's copy (and no cards), and a seeded Ready
 * state renders the card's identity (name, host:port, badges, probe chip)
 * with the tap wiring intact. The content composable is deliberately
 * ViewModel-free — the actions are lambdas, so the test renders
 * [DevicesContent] directly and records the taps.
 *
 * Hosting note: `createComposeRule()` launches
 * `androidx.activity.ComponentActivity`, which the test-scope manifest
 * (`app/src/test/AndroidManifest.xml`) declares — but AGP packages the
 * *app* manifest (not the test-scope merge) into the
 * `apk-for-local-test` Robolectric boots from, so the harness activity is
 * not in the package manager the launch resolves against (robolectric#4736's
 * exact symptom). [registerHarnessActivity] therefore registers it at
 * runtime, in an outer rule so it lands before the compose rule's launch.
 * Assertions go through the semantics tree; duplicates are plausible (the
 * FAB renders on the Scaffold *and* inside the empty state, dialog copy can
 * land on more than one node), so `onAllNodesWithText(...).onFirst()` is the
 * default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DevicesContentTest {

    private val composeRule: ComposeContentTestRule = createComposeRule()

    /**
     * The compose harness's [ComponentActivity] is declared in the test-scope
     * manifest, which never reaches the binary manifest Robolectric's package
     * manager is built from — add it explicitly before the compose rule
     * resolves and launches it. Direct instantiation is not an option here:
     * `ActivityScenario` (inside `createComposeRule`) always goes through
     * `resolveActivityInfo`.
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

    private fun testDevice(
        id: String = "dev-1",
        name: String = "Garage Cam",
        host: String = "192.168.1.5",
        port: Int = 8443,
    ) = CompanionDevice(
        id = id,
        name = name,
        host = host,
        port = port,
        useTls = true,
        tlsFingerprint = "a".repeat(64),
        auth = CompanionAuthKind.TOKEN,
        role = "viewer",
        addedAtMs = 0L,
    )

    /** No-op action lambdas, with a tap recorder on [onOpenDevice]. */
    private fun actions(
        onOpenDevice: (deviceId: String) -> Unit = {},
    ) = DevicesActions(
        onBack = {},
        onOpenDevice = onOpenDevice,
        onRefresh = {},
        onCaptureAll = {},
        onRequestRemove = {},
        onConfirmRemove = {},
        onAddDevice = {},
    )

    /** Hosts [state] inside the theme under the compose test rule. */
    private fun setContent(
        state: DevicesUiState,
        actions: DevicesActions = actions(),
    ) {
        composeRule.setContent {
            LensCastTheme {
                DevicesContent(
                    state = state,
                    snackbarHostState = SnackbarHostState(),
                    actions = actions,
                )
            }
        }
    }

    @Test
    fun `empty roster renders the empty state and the add affordance`() {
        setContent(DevicesUiState.Empty)

        composeRule.onNodeWithText("Devices").assertExists()
        // The empty-state copy is one resource with an embedded newline;
        // match on its first line (the original test's startsWith intent).
        composeRule
            .onAllNodesWithText("No paired devices yet.", substring = true)
            .onFirst()
            .assertExists()
        // The add affordance renders twice here — the Scaffold FAB plus the
        // empty state's own CTA — hence the collection form. Material3's
        // ExtendedFloatingActionButton hides its label text from the
        // semantics tree (clearAndSetSemantics), so the affordance surfaces
        // through the icon's content description (companion_add_cd — the
        // same "Add device" literal).
        composeRule
            .onAllNodesWithContentDescription("Add device")
            .onFirst()
            .assertExists()
        // …and no roster cards.
        composeRule.onNodeWithText("Garage Cam").assertDoesNotExist()
        composeRule.onNodeWithText("192.168.1.5:8443").assertDoesNotExist()
    }

    @Test
    fun `a seeded roster row renders identity, badges and probe chip`() {
        setContent(
            DevicesUiState.Ready(
                rows = listOf(DeviceRow(testDevice(), DeviceProbeStatus.ONLINE)),
            ),
        )

        composeRule.onAllNodesWithText("Garage Cam").onFirst().assertExists()
        composeRule.onAllNodesWithText("192.168.1.5:8443").onFirst().assertExists()
        composeRule.onAllNodesWithText("Certificate pinned").onFirst().assertExists()
        composeRule.onAllNodesWithText("Viewer").onFirst().assertExists()
        composeRule.onAllNodesWithText("Connected").onFirst().assertExists()
        // The capture-all affordance rides the roster chrome in the Ready state.
        composeRule
            .onAllNodesWithContentDescription("Capture a photo on every online device")
            .onFirst()
            .assertExists()
    }

    @Test
    fun `card tap opens the device`() {
        var opened: String? = null
        setContent(
            DevicesUiState.Ready(
                rows = listOf(DeviceRow(testDevice(), DeviceProbeStatus.ONLINE)),
            ),
            actions = actions(onOpenDevice = { opened = it }),
        )

        // The card's clickable merges its descendants' semantics, so the
        // name's node carries the card's OnClick action.
        composeRule.onAllNodesWithText("Garage Cam").onFirst().performClick()
        assertEquals("dev-1", opened)
    }

    @Test
    fun `offline verdict renders the offline chip and not the online one`() {
        setContent(
            DevicesUiState.Ready(
                rows = listOf(DeviceRow(testDevice(), DeviceProbeStatus.OFFLINE)),
            ),
        )

        composeRule.onAllNodesWithText("Offline").onFirst().assertExists()
        composeRule.onNodeWithText("Connected").assertDoesNotExist()
    }

    @Test
    fun `loading state renders the progress placeholder without cards`() {
        setContent(DevicesUiState.Loading)

        composeRule.onNodeWithText("Devices").assertExists()
        composeRule.onNodeWithText("Garage Cam").assertDoesNotExist()
    }

    @Test
    fun `the armed remove dialog names the device`() {
        setContent(
            DevicesUiState.Ready(
                rows = listOf(DeviceRow(testDevice(), DeviceProbeStatus.UNAUTHORIZED)),
                pendingRemovalId = "dev-1",
            ),
        )

        // The confirm dialog is armed from state; its copy names the device
        // (companion_remove_confirm's %1$s slot). Dialog text can surface on
        // more than one node, hence the collection form.
        composeRule
            .onAllNodesWithText(
                "Remove Garage Cam from your devices? " +
                    "Stored credentials for it are deleted too.",
            )
            .onFirst()
            .assertExists()
    }
}
