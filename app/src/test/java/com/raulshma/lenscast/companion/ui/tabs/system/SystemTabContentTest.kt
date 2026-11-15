package com.raulshma.lenscast.companion.ui.tabs.system

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.raulshma.lenscast.companion.client.dto.AllSettings
import com.raulshma.lenscast.companion.client.dto.AuthSessionEntry
import com.raulshma.lenscast.companion.client.dto.BatteryDetail
import com.raulshma.lenscast.companion.client.dto.LensInfo
import com.raulshma.lenscast.companion.client.dto.LensesResponse
import com.raulshma.lenscast.companion.client.dto.RemoteAuditEntry
import com.raulshma.lenscast.companion.client.dto.RemoteCameraSettings
import com.raulshma.lenscast.companion.client.dto.StorageInfo
import com.raulshma.lenscast.companion.client.dto.StreamingSettings
import com.raulshma.lenscast.companion.client.dto.SystemInfo
import com.raulshma.lenscast.companion.ui.tabs.SystemTabContent
import com.raulshma.lenscast.ui.theme.LensCastTheme
import org.junit.Assert.assertTrue
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
 * The system tab's stateless render half, under Robolectric (the same
 * hosting seam [com.raulshma.lenscast.companion.ui.DevicesContentTest]
 * uses): the info card renders its identity/storage/lens facts, an admin
 * with unsaved edits sees the save/revert row and per-session revoke
 * buttons, a viewer sees neither (read-only banner instead), and the audit
 * list renders newest-first (the first entry's row sits above the second's).
 *
 * The content composable is deliberately ViewModel-free — state + action
 * lambdas in — so the test seeds [SystemUiState.Ready] directly and records
 * gestures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemTabContentTest {

    private val composeRule: ComposeContentTestRule = createComposeRule()

    /**
     * The compose harness's [ComponentActivity] is declared in the
     * test-scope manifest, which never reaches the binary manifest
     * Robolectric's package manager is built from — add it explicitly before
     * the compose rule resolves and launches it (robolectric#4736).
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

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerHarnessActivity).around(composeRule)

    // ── State builders ──

    private fun info() = SystemInfo(
        appVersion = "1.9.0",
        deviceModel = "Pixel 8",
        deviceManufacturer = "Google",
        androidVersion = "15",
        sdkInt = 35,
        osUptimeMs = 86_400_000L,
        processUptimeMs = 3_600_000L,
        battery = BatteryDetail(level = 76, isCharging = true),
        storage = StorageInfo(
            usedBytes = 53_687_091_200L,
            quotaBytes = 107_374_182_400L,
            freeBytes = 53_687_091_200L,
            totalBytes = 107_374_182_400L,
        ),
    )

    private fun lenses() = LensesResponse(
        lenses = listOf(
            LensInfo(
                index = 0,
                id = "0",
                label = "Wide",
                focalLength = 4.3,
                isFront = false,
                selected = true,
            ),
            LensInfo(
                index = 1,
                id = "2",
                label = "Telephoto",
                focalLength = 10.9,
                isFront = false,
                selected = false,
            ),
        ),
        selectedIndex = 0,
    )

    private fun settingsBlock(dirty: Boolean): SettingsBlock {
        val streaming = StreamingSettings(
            motionDetectionEnabled = true,
            motionSensitivityPercent = 60,
            mlDetectionEnabled = true,
            storageQuotaMb = 2_048,
        )
        val document = AllSettings(
            camera = RemoteCameraSettings(),
            streaming = streaming,
        )
        return SettingsBlock(
            document = document,
            draft = if (dirty) streaming.copy(motionSensitivityPercent = 80) else null,
        )
    }

    private fun identity(role: String, sessions: List<AuthSessionEntry>?) = IdentityBlock(
        authRequired = true,
        authenticated = true,
        role = role,
        sessions = sessions,
    )

    private val sessions = listOf(
        AuthSessionEntry(tokenPrefix = "abcd1234", expiresAtMs = 1_893_456_000_000L, role = "admin"),
        AuthSessionEntry(tokenPrefix = "efgh5678", expiresAtMs = 1_893_456_000_000L, role = "viewer"),
    )

    private fun audit() = AuditBlock(
        entries = listOf(
            RemoteAuditEntry(timestampMs = 1_788_825_600_000L, action = "PUT /api/settings"),
            RemoteAuditEntry(
                timestampMs = 1_788_825_540_000L,
                action = "login.failed",
                detail = "192.168.1.20",
                outcome = "error",
            ),
        ),
        total = 2,
        limit = 50,
        exhausted = true,
    )

    private fun ready(
        role: String,
        settingsDirty: Boolean,
        sessions: List<AuthSessionEntry>?,
    ) = SystemUiState.Ready(
        info = info(),
        lenses = lenses(),
        settings = settingsBlock(settingsDirty),
        identity = identity(role, sessions),
        audit = audit(),
    )

    private fun actions(save: () -> Unit = {}, revoke: (String) -> Unit = {}) = SystemTabActions(
        onRetry = {},
        onEditSettings = {},
        onSaveSettings = save,
        onRevertSettings = {},
        onRevokeSession = revoke,
        onRequestLogout = {},
        onConfirmLogout = {},
        onLoadMoreAudit = {},
    )

    private fun setContent(
        state: SystemUiState,
        role: String,
        actions: SystemTabActions = actions(),
    ) {
        composeRule.setContent {
            LensCastTheme {
                SystemTabContent(
                    state = state,
                    role = role,
                    snackbarHostState = SnackbarHostState(),
                    actions = actions,
                )
            }
        }
    }

    /**
     * The cards live in a LazyColumn, so only the viewport's items are
     * composed — the tests scroll the list to a card before asserting its
     * facts (scrolling past it un-composes earlier cards, so every assert
     * happens right after its own scroll).
     */
    private fun scrollTo(text: String) {
        composeRule
            .onNodeWithTag(SystemTabTags.CARDS_LIST)
            .performScrollToNode(hasText(text))
    }

    // ── Render facts ──

    @Test
    fun `info card renders identity, storage and the active lens`() {
        setContent(ready("admin", settingsDirty = false, sessions = sessions), role = "admin")

        composeRule.onNodeWithText("Device").assertExists()
        composeRule.onNodeWithText("1.9.0").assertExists()
        composeRule.onNodeWithText("Google Pixel 8").assertExists()
        composeRule.onNodeWithText("Wide").assertExists()
        composeRule.onNodeWithText("Active").assertExists()
        composeRule.onNodeWithText("Telephoto").assertExists()
    }

    @Test
    fun `an admin with unsaved edits sees the save row and per-session revoke`() {
        var saved = false
        setContent(
            ready("admin", settingsDirty = true, sessions = sessions),
            role = "admin",
            actions = actions(save = { saved = true }),
        )

        // The save row sits at the settings card's bottom — scroll it fully
        // into view first (a clipped node's center click is a no-op), then
        // exercise it.
        scrollTo("Revert")
        composeRule.onNodeWithText("Revert").assertExists()
        composeRule.onNodeWithText("Save").assertExists()
        composeRule.onNodeWithText("Save").performClick()
        assertTrue(saved)

        // The auth card sits below the fold — scroll to it, then assert.
        scrollTo("Sign out")
        composeRule.onNodeWithText("Sign out").assertExists()
        composeRule.onAllNodesWithText("Revoke").onFirst().assertExists()
    }

    @Test
    fun `a viewer sees the read-only banner and neither save nor revoke`() {
        setContent(
            ready("viewer", settingsDirty = false, sessions = null),
            role = "viewer",
        )

        composeRule
            .onNodeWithText("Viewer session — settings are read-only")
            .assertExists()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
        composeRule.onNodeWithText("Revert").assertDoesNotExist()
        composeRule.onNodeWithText("Revoke").assertDoesNotExist()
        // The audit card still renders for viewers (below the fold: scroll).
        scrollTo("PUT /api/settings")
        composeRule.onAllNodesWithText("PUT /api/settings").onFirst().assertExists()
    }

    @Test
    fun `the audit list renders newest-first labels`() {
        setContent(ready("admin", settingsDirty = false, sessions = sessions), role = "admin")

        scrollTo("Audit log")
        composeRule.onNodeWithText("Audit log").assertExists()
        composeRule.onAllNodesWithText("PUT /api/settings").onFirst().assertExists()
        composeRule.onAllNodesWithText("login.failed").onFirst().assertExists()

        // Ordering: the newest entry's node sits ABOVE the older one's
        // (positionInRoot is the node's top-left offset).
        val newY = composeRule
            .onAllNodesWithText("PUT /api/settings")
            .fetchSemanticsNodes()
            .first()
            .positionInRoot
            .y
        val oldY = composeRule
            .onAllNodesWithText("login.failed")
            .fetchSemanticsNodes()
            .first()
            .positionInRoot
            .y
        assertTrue(
            "expected the newest audit entry above the older one (y=$newY vs y=$oldY)",
            newY < oldY,
        )
    }

    @Test
    fun `the empty-trail audit card renders its empty note without load-more`() {
        composeRule.setContent {
            LensCastTheme {
                SystemTabContent(
                    state = SystemUiState.Ready(
                        info = info(),
                        lenses = lenses(),
                        settings = settingsBlock(false),
                        identity = identity("admin", sessions),
                        audit = AuditBlock(entries = emptyList(), total = 0, limit = 50, exhausted = true),
                    ),
                    role = "admin",
                    snackbarHostState = SnackbarHostState(),
                    actions = actions(),
                )
            }
        }

        scrollTo("No audited actions yet")
        composeRule.onNodeWithText("No audited actions yet").assertExists()
        composeRule.onNodeWithText("Load more").assertDoesNotExist()
    }

    @Test
    fun `the whole-load failure renders the retry affordance`() {
        setContent(SystemUiState.Ready(failed = true), role = "admin")

        composeRule.onNodeWithText("Could not reach the device").assertExists()
        composeRule.onNodeWithText("Retry").assertExists()
    }
}
