package com.raulshma.lenscast.companion.ui.tabs.media

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertCountEquals
import com.raulshma.lenscast.ui.theme.LensCastTheme
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
 * The media tab's stateless render half under Robolectric (the
 * [com.raulshma.lenscast.companion.ui.DevicesContentTest] hosting seam:
 * registerHarnessActivity in an outer rule so the compose rule's activity
 * launch resolves, then compose [MediaTabContent] directly against seeded
 * [MediaUiState.Ready] values with no-op action lambdas). Assertions:
 * the empty-gallery copy, the type filter chips, the recordings card's idle
 * copy, and the viewer role's hidden delete controls. The ExoPlayer surface
 * is deliberately never composed here — the tests exercise state presence,
 * not player frames (thumbnail loads are no-ops: the media engine seam is
 * left null, so tiles render their placeholders).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaTabContentTest {

    private val composeRule: ComposeContentTestRule = createComposeRule()

    /**
     * The compose harness's [ComponentActivity] is declared in the test-scope
     * manifest, which never reaches the binary manifest Robolectric's package
     * manager is built from — add it explicitly before the compose rule
     * resolves and launches it (robolectric#4736).
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

    /** No-op action vocabulary — the render tests never drive behavior. */
    private fun actions() = MediaTabActions(
        onFilterSelect = {},
        onOpenItem = {},
        onToggleSelect = {},
        onClearSelection = {},
        onRequestDelete = {},
        onRequestBatchDelete = {},
        onConfirmDelete = {},
        onCancelDelete = {},
        onCloseViewer = {},
        onShare = {},
        onLoadMore = {},
        onRetry = {},
        onStartRecording = {},
        onStopRecording = {},
        onIntervalDraftChange = {},
        onStartInterval = {},
        onStopInterval = {},
    )

    /** Hosts [state] inside the theme under the compose test rule. */
    private fun setContent(state: MediaUiState) {
        composeRule.setContent {
            LensCastTheme {
                MediaTabContent(state = state, actions = actions())
            }
        }
    }

    @Test
    fun `empty gallery renders the empty copy, chips and idle cards`() {
        setContent(MediaUiState.Ready())

        composeRule.onNodeWithText("No captures on this device").assertExists()
        // The type filter chips…
        composeRule.onNodeWithText("All").assertExists()
        composeRule.onNodeWithText("Photos").assertExists()
        composeRule.onNodeWithText("Videos").assertExists()
        // …the recordings card's idle copy…
        composeRule.onNodeWithText("Recording sessions").assertExists()
        composeRule.onNodeWithText("Not recording").assertExists()
        composeRule.onNodeWithText("No recording sessions yet").assertExists()
        // …and the interval card's idle copy.
        composeRule.onNodeWithText("Interval capture").assertExists()
        composeRule.onNodeWithText("Interval capture is off").assertExists()
    }

    @Test
    fun `a filtered empty gallery names the filter`() {
        setContent(
            MediaUiState.Ready(
                items = emptyList(),
                filter = MediaGalleryFilter.VIDEOS,
            ),
        )

        composeRule.onNodeWithText("Nothing matches this filter").assertExists()
        composeRule.onNodeWithText("No captures on this device").assertDoesNotExist()
    }

    @Test
    fun `viewer role hides every delete control`() {
        setContent(
            MediaUiState.Ready(
                items = listOf(item("a1", "gate-1.jpg"), item("a2", "gate-2.jpg")),
                canManage = false,
            ),
        )

        composeRule.onAllNodesWithContentDescription("Delete gate-1.jpg").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Delete gate-2.jpg").assertCountEquals(0)
        // The read surface survives: tiles carry the share affordance.
        composeRule.onAllNodesWithContentDescription("Share").assertCountEquals(2)
    }

    @Test
    fun `admin tiles expose per-item delete named by file`() {
        setContent(
            MediaUiState.Ready(
                items = listOf(item("a1", "gate-1.jpg"), item("a2", "gate-2.jpg")),
                canManage = true,
            ),
        )

        composeRule.onAllNodesWithContentDescription("Delete gate-1.jpg").onFirst().assertExists()
        composeRule.onAllNodesWithContentDescription("Delete gate-2.jpg").onFirst().assertExists()
    }

    @Test
    fun `recording card shows the elapsed copy and stop control while running`() {
        setContent(
            MediaUiState.Ready(
                recording = MediaRecordingUi(isRecording = true, elapsedSeconds = 62),
                canManage = true,
            ),
        )

        composeRule.onNodeWithText("Recording").assertExists()
        composeRule.onNodeWithText("Elapsed 01:02").assertExists()
        composeRule.onNodeWithText("Stop recording").assertExists()
        composeRule.onNodeWithText("Not recording").assertDoesNotExist()
    }

    @Test
    fun `interval card shows the running count and stop control`() {
        setContent(
            MediaUiState.Ready(
                interval = MediaIntervalUi(isRunning = true, completedCaptures = 12),
                canManage = true,
            ),
        )

        composeRule.onNodeWithText("Running — 12 captures so far").assertExists()
        composeRule.onNodeWithText("Stop").assertExists()
        // The editor hides while the capture is running.
        composeRule.onNodeWithText("Seconds between captures").assertDoesNotExist()
    }

    @Test
    fun `idle interval card offers the seconds editor with the default draft`() {
        var drafted: String? = null
        composeRule.setContent {
            LensCastTheme {
                MediaTabContent(
                    state = MediaUiState.Ready(canManage = true),
                    actions = actions().copy(onIntervalDraftChange = { drafted = it }),
                )
            }
        }

        composeRule.onNodeWithText("Seconds between captures").assertExists()
        composeRule.onNodeWithText("60").assertExists()
        composeRule.onNodeWithText("Enter a number between 1 and 3600").assertDoesNotExist()
        // Editing routes through the action seam (the VM owns the draft).
        composeRule.onNodeWithText("60").performTextReplacement("90")
        composeRule.runOnIdle { org.junit.Assert.assertEquals("90", drafted) }
    }

    @Test
    fun `an invalid draft marks the field and disarms the start button`() {
        setContent(
            MediaUiState.Ready(
                canManage = true,
                intervalDraft = "9999",
                intervalDraftValid = false,
            ),
        )

        composeRule.onNodeWithText("Enter a number between 1 and 3600").assertExists()
        composeRule.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test
    fun `viewer role sees no interval editor`() {
        setContent(MediaUiState.Ready(canManage = false))

        composeRule.onNodeWithText("Seconds between captures").assertDoesNotExist()
        composeRule.onNodeWithText("Interval capture is off").assertExists()
    }

    @Test
    fun `selection bar offers clear and batch delete`() {
        var cleared = false
        var batchRequested = false
        composeRule.setContent {
            LensCastTheme {
                MediaTabContent(
                    state = MediaUiState.Ready(
                        items = listOf(item("a1", "gate-1.jpg"), item("a2", "gate-2.jpg")),
                        selection = setOf("a1"),
                        canManage = true,
                    ),
                    actions = MediaTabActions(
                        onFilterSelect = {},
                        onOpenItem = {},
                        onToggleSelect = {},
                        onClearSelection = { cleared = true },
                        onRequestDelete = {},
                        onRequestBatchDelete = { batchRequested = true },
                        onConfirmDelete = {},
                        onCancelDelete = {},
                        onCloseViewer = {},
                        onShare = {},
                        onLoadMore = {},
                        onRetry = {},
                        onStartRecording = {},
                        onStopRecording = {},
                        onIntervalDraftChange = {},
                        onStartInterval = {},
                        onStopInterval = {},
                    ),
                )
            }
        }

        composeRule.onNodeWithText("1 selected").assertExists()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { org.junit.Assert.assertTrue(batchRequested) }
        composeRule.onNodeWithContentDescription("Clear selection").performClick()
        composeRule.runOnIdle { org.junit.Assert.assertTrue(cleared) }
    }

    @Test
    fun `armed single delete dialog names the file`() {
        setContent(
            MediaUiState.Ready(
                items = listOf(item("a1", "gate-1.jpg")),
                pendingDelete = MediaPendingDelete.Single("a1", "gate-1.jpg"),
            ),
        )
        composeRule.onAllNodesWithText("Delete gate-1.jpg from the device? This cannot be undone.").onFirst().assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `armed batch delete dialog names the count`() {
        setContent(
            MediaUiState.Ready(
                pendingDelete = MediaPendingDelete.Batch(listOf("a1", "a2", "a3")),
            ),
        )
        composeRule
            .onAllNodesWithText("Delete 3 captures from the device? This cannot be undone.")
            .onFirst()
            .assertExists()
    }
}

private fun item(id: String, name: String, video: Boolean = false): MediaUiItem {
    // Placeholders resolve through the null media-engine seam in tests — the
    // URLs are unreachable dummies that never fetch.
    return MediaUiItem(
        id = id,
        type = if (video) "VIDEO" else "PHOTO",
        fileName = name,
        timestampMs = 1_700_000_000_000L,
        fileSizeBytes = 1024L,
        durationMs = if (video) 61_000L else 0L,
        favorite = false,
        mediaUrl = "http://127.0.0.1:1/api/media/$id",
        downloadUrl = "http://127.0.0.1:1/api/media/$id?download=1",
    )
}
