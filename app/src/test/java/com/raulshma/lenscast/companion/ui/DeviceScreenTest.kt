package com.raulshma.lenscast.companion.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.session.ConnectionState
import com.raulshma.lenscast.companion.session.DeviceSession
import com.raulshma.lenscast.navigation.Routes
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
 * The per-device shell's compose pass, on Robolectric's native graphics:
 * composes the stateless [DeviceScreenContent] directly against a bare
 * [DeviceSession] — the testability seam [DeviceScreen] leaves (the shell's
 * AppGraph resolution and the session start/stop lifecycle stay out of the
 * content composable, so tests need neither the graph nor a live link; the
 * stub session's flows start IDLE and empty, which is exactly the state the
 * chip and placeholder tabs must render).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceScreenTest {

    private val composeRule: ComposeContentTestRule = createComposeRule()

    /**
     * The compose harness's [ComponentActivity] is declared in the test-scope
     * manifest, which never reaches the binary manifest Robolectric's package
     * manager is built from — add it explicitly before the compose rule
     * resolves and launches it (robolectric#4736; the same seam
     * [DevicesContentTest] uses).
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

    private fun setContent(initialTab: String = Routes.TAB_LIVE) {
        composeRule.setContent {
            MaterialTheme {
                DeviceScreenContent(
                    deviceLabel = "Backyard Cam",
                    session = DeviceSession("test-device"),
                    onBack = {},
                    initialTab = initialTab,
                )
            }
        }
    }

    @Test
    fun `shell renders device name, connection chip and the four tab labels`() {
        setContent()

        // Top bar carries the device's display name…
        composeRule.onNodeWithText("Backyard Cam").assertExists()
        // …and the connection chip reflects the injected session's state — a
        // bare stub session starts IDLE, so the chip reads "Idle". The
        // detached live tab renders its own chip too (a bare session has no
        // roster entry), hence the collection form.
        composeRule.onAllNodesWithText("Idle").onFirst().assertExists()

        // All four bottom-nav labels render (each also appears as the active
        // tab's placeholder title, hence the collection form).
        listOf("Live", "Events", "Media", "System").forEach { label ->
            composeRule.onAllNodesWithText(label).onFirst().assertExists()
        }
    }

    @Test
    fun `tapping each bottom tab swaps the placeholder title`() {
        setContent()

        composeRule.onNodeWithTag(DeviceScreenTags.TAB_TITLE).assertTextEquals("Live")

        composeRule.onNodeWithTag(DeviceScreenTags.NAV_EVENTS).performClick()
        composeRule.onNodeWithTag(DeviceScreenTags.TAB_TITLE).assertTextEquals("Events")

        composeRule.onNodeWithTag(DeviceScreenTags.NAV_MEDIA).performClick()
        composeRule.onNodeWithTag(DeviceScreenTags.TAB_TITLE).assertTextEquals("Media")

        composeRule.onNodeWithTag(DeviceScreenTags.NAV_SYSTEM).performClick()
        composeRule.onNodeWithTag(DeviceScreenTags.TAB_TITLE).assertTextEquals("System")

        composeRule.onNodeWithTag(DeviceScreenTags.NAV_LIVE).performClick()
        composeRule.onNodeWithTag(DeviceScreenTags.TAB_TITLE).assertTextEquals("Live")
    }

    @Test
    fun `deep-link initialTab selects the entry tab without navigating`() {
        setContent(initialTab = Routes.TAB_MEDIA)

        composeRule.onNodeWithTag(DeviceScreenTags.TAB_TITLE).assertTextEquals("Media")
    }

    @Test
    fun `unknown device renders the graceful missing state with a back affordance`() {
        composeRule.setContent {
            MaterialTheme {
                DeviceMissingContent(onBack = {})
            }
        }

        composeRule.onNodeWithText("Device not found").assertExists()
        // LensCastTopBar's back icon carries the shared nav_back description.
        composeRule.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun `deep-link tab argument outside the vocabulary sanitizes to live`() {
        assertEquals(Routes.TAB_LIVE, sanitizedTab(Routes.TAB_LIVE))
        assertEquals(Routes.TAB_EVENTS, sanitizedTab(Routes.TAB_EVENTS))
        assertEquals(Routes.TAB_MEDIA, sanitizedTab(Routes.TAB_MEDIA))
        assertEquals(Routes.TAB_SYSTEM, sanitizedTab(Routes.TAB_SYSTEM))
        assertEquals(Routes.TAB_LIVE, sanitizedTab("bogus"))
        assertEquals(Routes.TAB_LIVE, sanitizedTab(""))
    }

    @Test
    fun `connection states map to distinct label resources`() {
        val labels = ConnectionState.entries.map { it.labelRes() }
        assertEquals(ConnectionState.entries.size, labels.distinct().size)
        assertEquals(R.string.companion_conn_idle, ConnectionState.IDLE.labelRes())
        assertEquals(R.string.companion_auth_failed, ConnectionState.AUTH_FAILED.labelRes())
    }
}
