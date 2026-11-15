package com.raulshma.lenscast.companion.ui.tabs.events

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raulshma.lenscast.companion.client.dto.DailyCount
import com.raulshma.lenscast.companion.client.dto.DetectionStats
import com.raulshma.lenscast.companion.client.dto.LabeledCount
import com.raulshma.lenscast.companion.client.dto.RemoteDetectionEvent
import com.raulshma.lenscast.companion.session.ConnectionState
import com.raulshma.lenscast.companion.ui.DeviceScreenTags
import com.raulshma.lenscast.ui.theme.LensCastTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The events tab's stateless render half, under Robolectric: the empty feed's
 * copy, a seeded feed's identity (type badge, source, relative age) with the
 * NEWEST-FIRST order visible in the semantics tree, the stats strip's counts,
 * the viewer role hiding both destructive actions, and the armed clear
 * dialog. The content composable is deliberately ViewModel-free — the test
 * renders [EventsContent] directly against a seeded [EventsUiState.Ready]
 * and records the taps.
 *
 * Hosting seam copied from [com.raulshma.lenscast.companion.ui.DevicesContentTest]:
 * `createComposeRule()` launches `androidx.activity.ComponentActivity`, which
 * only the test-scope manifest declares — register it at runtime, in an outer
 * rule so it lands before the compose rule's launch (robolectric#4736).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventsContentTest {

    private val composeRule: ComposeContentTestRule = createComposeRule()

    /** Registers the harness activity before the compose rule launches it. */
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
    // an explicit chain pins the order.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerHarnessActivity).around(composeRule)

    private val noActions = EventsActions(
        onRefresh = {},
        onRequestClear = {},
        onDismissClear = {},
        onConfirmClear = {},
        onTestFire = {},
    )

    private fun ready(
        events: List<RemoteDetectionEvent> = emptyList(),
        stats: DetectionStats? = null,
        connection: ConnectionState = ConnectionState.CONNECTED,
        isAdmin: Boolean = true,
        pendingClearConfirm: Boolean = false,
    ) = EventsUiState.Ready(
        events = events,
        stats = stats,
        connection = connection,
        isAdmin = isAdmin,
        busy = false,
        pendingClearConfirm = pendingClearConfirm,
    )

    private fun event(id: String, type: String, timestampMs: Long) = RemoteDetectionEvent(
        id = id,
        type = type,
        source = "Backyard",
        timestampMs = timestampMs,
    )

    /** Hosts [state] inside the theme under the compose test rule. */
    private fun setContent(state: EventsUiState, actions: EventsActions = noActions) {
        composeRule.setContent {
            LensCastTheme {
                EventsContent(state = state, actions = actions, modifier = Modifier)
            }
        }
    }

    @Test
    fun `empty feed renders the title, the live chip and the empty copy`() {
        setContent(ready())

        composeRule.onNodeWithTag(DeviceScreenTags.TAB_TITLE).assertTextEquals("Events")
        composeRule.onNodeWithText("Live").assertExists()
        composeRule.onNodeWithText("No detection events yet").assertExists()
        // Stats hidden until a fetch lands.
        composeRule.onNodeWithText("47").assertDoesNotExist()
    }

    @Test
    fun `seeded feed renders type, source and age newest-first`() {
        val now = System.currentTimeMillis()
        val newest = event("e3", type = "tamper", timestampMs = now - 5_000)
        val middle = event("e2", type = "sound", timestampMs = now - 2 * 60_000)
        val oldest = event("e1", type = "motion", timestampMs = now - 3 * 86_400_000)
        // The buffer's snapshot arrives NEWEST-FIRST; the feed renders as-is.
        setContent(ready(events = listOf(newest, middle, oldest)))

        composeRule.onNodeWithText("TAMPER").assertExists()
        composeRule.onNodeWithText("SOUND").assertExists()
        composeRule.onNodeWithText("MOTION").assertExists()
        composeRule.onNodeWithText("Just now").assertExists()
        composeRule.onNodeWithText("2 min ago").assertExists()
        composeRule.onNodeWithText("3 d ago").assertExists()

        // Order: the three source nodes sit top-down in list order — the
        // newest's y strictly above the middle's, the middle's above the
        // oldest's.
        val sourceNodes = composeRule.onAllNodesWithText("Backyard").fetchSemanticsNodes()
        assertEquals(3, sourceNodes.size)
        val ys = sourceNodes.map { it.positionInRoot.y }
        assertTrue(
            "expected newest-first vertical order, got $ys",
            ys[0] < ys[1] && ys[1] < ys[2],
        )
    }

    @Test
    fun `stats strip renders the summed counts`() {
        val now = System.currentTimeMillis()
        setContent(
            ready(
                events = listOf(event("e1", type = "motion", timestampMs = now)),
                stats = DetectionStats(
                    last24h = mapOf("motion" to 4),
                    last7d = mapOf("motion" to 9, "sound" to 2),
                    allTime = mapOf("motion" to 47),
                    perDay = listOf(DailyCount("2026-11-14", count = 4)),
                    totalEvents = 47,
                    topZones = listOf(LabeledCount("door", 5)),
                    topLabels = listOf(LabeledCount("person", 12)),
                ),
            ),
        )

        composeRule.onNodeWithText("4").assertExists()
        composeRule.onNodeWithText("11").assertExists()
        composeRule.onNodeWithText("47").assertExists()
    }

    @Test
    fun `admin sees the destructive actions and the refresh affordance`() {
        val now = System.currentTimeMillis()
        var cleared = false
        setContent(
            ready(events = listOf(event("e1", type = "motion", timestampMs = now))),
            actions = EventsActions(
                onRefresh = {},
                onRequestClear = { cleared = true },
                onDismissClear = {},
                onConfirmClear = {},
                onTestFire = {},
            ),
        )

        composeRule.onNodeWithText("Test alert").assertExists()
        composeRule.onNodeWithText("Clear log").assertExists()
        composeRule.onNodeWithContentDescription("Refresh events").assertExists()

        composeRule.onAllNodesWithText("Clear log").onFirst().performClick()
        assertTrue(cleared)
    }

    @Test
    fun `viewer role hides both destructive actions`() {
        val now = System.currentTimeMillis()
        setContent(
            ready(events = listOf(event("e1", type = "motion", timestampMs = now)), isAdmin = false),
        )

        composeRule.onNodeWithText("Clear log").assertDoesNotExist()
        composeRule.onNodeWithText("Test alert").assertDoesNotExist()
        // The feed itself stays readable.
        composeRule.onNodeWithText("MOTION").assertExists()
    }

    @Test
    fun `the armed clear dialog renders its copy`() {
        setContent(ready(pendingClearConfirm = true))

        composeRule.onNodeWithText("Clear the event log?").assertExists()
        composeRule
            .onAllNodesWithText("Every detection event stored on the device is deleted permanently.")
            .onFirst()
            .assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `a non-connected link renders the state chip instead of live`() {
        setContent(ready(connection = ConnectionState.RECONNECTING))

        composeRule.onNodeWithText("Live").assertDoesNotExist()
        composeRule.onNodeWithText("Reconnecting…").assertExists()
    }
}
