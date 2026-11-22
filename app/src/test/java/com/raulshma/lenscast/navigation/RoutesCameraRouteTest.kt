package com.raulshma.lenscast.navigation

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * The camera route's optional-`device` argument, at two depths:
 *
 * - the builder's output shape and its round-trip through the same query-arg
 *   extraction the NavHost performs on `camera?device=<id>` (split the query,
 *   read `device`; absent → null = the local dashboard). The no-arg local
 *   form is pinned because it is the start destination every existing
 *   navigation rides;
 * - the registered destination itself: a NavHost mirrors [NavigationGraph]'s
 *   registrations verbatim ([Routes]' patterns + [deviceNavArgument]) and
 *   navigates BOTH forms of every shared device-parameterized route, so the
 *   binding Navigation actually performs — the query value lands under the
 *   pattern placeholder's name, which must be the declared `device` argument
 *   the screens read — is exercised, not assumed. Null (the argument
 *   omitted) is what selects the local backend; an id the remote console.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutesCameraRouteTest {

    private val composeRule: ComposeContentTestRule = createComposeRule()

    /**
     * The compose harness's [ComponentActivity] is declared in the test-scope
     * manifest, which never reaches the binary manifest Robolectric's package
     * manager is built from — add it explicitly before the compose rule
     * launches it (the [DevicesContentTest] precedent).
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

    // The registration must run before the compose rule's activity launch.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerHarnessActivity).around(composeRule)

    // ── the local form never moved ──

    @Test
    fun `null device builds the unchanged no-arg local route`() {
        assertEquals("camera", Routes.camera())
        assertEquals("camera", Routes.camera(null))
        assertEquals("camera", Routes.CAMERA_LOCAL)
    }

    @Test
    fun `the registered pattern carries the optional query arg`() {
        assertEquals("camera?device={device}", Routes.CAMERA)
        // The arg name the NavigationGraph composable reads must match the
        // pattern's query name.
        assertTrue(Routes.CAMERA.substringAfter('?').startsWith("device="))
        assertTrue(Routes.CAMERA.contains("{device}"))
    }

    // ── builder ⇄ parse round-trip (the NavHost's query extraction) ──

    /** Mirrors Navigation-Compose's query-arg read of `camera?device=<id>`. */
    private fun parseDevice(route: String): String? =
        route.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("device=") }
            ?.substringAfter('=')

    @Test
    fun `a device id round-trips through the query arg`() {
        val id = "a1b2c3d4-e5f6"
        val route = Routes.camera(id)
        assertEquals("camera?device=$id", route)
        assertEquals(id, parseDevice(route))
    }

    @Test
    fun `the local route parses to no device`() {
        // Navigation treats a missing optional query arg as null — the
        // screen's remoteDeviceId, i.e. the local backend.
        assertEquals(null, parseDevice(Routes.CAMERA_LOCAL))
    }

    @Test
    fun `the camera pattern stays the query-arg form, not a path-parameterized device route`() {
        val route = Routes.camera("dev1")
        // The device id rides the query string — the route never grew a
        // path-parameterized per-device form.
        assertFalse(route.substringBefore('?').contains("dev1"))
        assertTrue(route.startsWith(Routes.CAMERA_LOCAL))
    }

    // ── integration: the registered destination binds `device` ──
    //
    // A NavHost registering the shared routes exactly as NavigationGraph
    // does — the Routes patterns + the one shared deviceNavArgument — and
    // navigating the builders' output through Navigation's real pattern
    // parser. The `device` value read off the back stack entry is the
    // backend selection input: null → the local backend, an id → the paired
    // device's remote console.

    private lateinit var navController: NavHostController

    /** Mirrors NavigationGraph's registrations for the shared routes. */
    private fun setUpNavHost() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = Routes.CAMERA_LOCAL,
            ) {
                composable(Routes.CAMERA, arguments = listOf(deviceNavArgument)) { }
                composable(Routes.SETTINGS, arguments = listOf(deviceNavArgument)) { }
                composable(Routes.APP_SETTINGS, arguments = listOf(deviceNavArgument)) { }
                composable(Routes.AUTOMATION, arguments = listOf(deviceNavArgument)) { }
                composable(Routes.CAPTURE, arguments = listOf(deviceNavArgument)) { }
                composable(Routes.DETECTION_EVENTS, arguments = listOf(deviceNavArgument)) { }
                composable(Routes.GALLERY, arguments = listOf(deviceNavArgument)) { }
                composable(Routes.VIEWER, arguments = listOf(deviceNavArgument)) { }
            }
        }
        composeRule.waitForIdle()
    }

    /** Navigates on the main thread; returns the destination's `device` argument. */
    private fun deviceArgAfterNavigate(route: String): String? {
        composeRule.runOnUiThread { navController.navigate(route) }
        composeRule.waitForIdle()
        return navController.currentBackStackEntry?.arguments?.getString("device")
    }

    @Test
    fun `the start destination boots with the device argument null — the local dashboard`() {
        setUpNavHost()
        assertNull(navController.currentBackStackEntry?.arguments?.getString("device"))
    }

    @Test
    fun `the camera destination binds the device query arg on the remote form and null on the local form`() {
        setUpNavHost()
        assertEquals("a1b2c3d4-e5f6", deviceArgAfterNavigate(Routes.camera("a1b2c3d4-e5f6")))
        assertNull(deviceArgAfterNavigate(Routes.camera()))
    }

    @Test
    fun `the settings destination binds the device query arg on the remote form and null on the local form`() {
        setUpNavHost()
        assertEquals("dev-6", deviceArgAfterNavigate(Routes.settings("dev-6")))
        assertNull(deviceArgAfterNavigate(Routes.settings()))
    }

    @Test
    fun `the app-settings destination binds the device query arg on the remote form and null on the local form`() {
        setUpNavHost()
        assertEquals("dev-7", deviceArgAfterNavigate(Routes.appSettings("dev-7")))
        assertNull(deviceArgAfterNavigate(Routes.appSettings()))
    }

    @Test
    fun `the automation destination binds the device query arg on the remote form and null on the local form`() {
        setUpNavHost()
        assertEquals("dev-5", deviceArgAfterNavigate(Routes.automation("dev-5")))
        assertNull(deviceArgAfterNavigate(Routes.automation()))
    }

    @Test
    fun `the capture destination binds the device query arg on the remote form and null on the local form`() {
        setUpNavHost()
        assertEquals("dev-4", deviceArgAfterNavigate(Routes.capture("dev-4")))
        assertNull(deviceArgAfterNavigate(Routes.capture()))
    }

    @Test
    fun `the detection-events destination binds the device query arg on the remote form and null on the local form`() {
        setUpNavHost()
        assertEquals("dev-2", deviceArgAfterNavigate(Routes.detectionEvents("dev-2")))
        // The plain form is the value the update deep-link extra carries —
        // it must resolve against the pattern with the query arg null.
        assertNull(deviceArgAfterNavigate(Routes.detectionEvents()))
    }

    @Test
    fun `the gallery destination binds the device query arg on the remote form and null on the local form`() {
        setUpNavHost()
        assertEquals("dev-1", deviceArgAfterNavigate(Routes.gallery("dev-1")))
        assertNull(deviceArgAfterNavigate(Routes.gallery()))
    }

    @Test
    fun `the viewer destination binds the device query arg alongside the media id on both forms`() {
        setUpNavHost()
        val remote = deviceArgAfterNavigate(Routes.viewer("m-42", "dev-3"))
        assertEquals("dev-3", remote)
        assertEquals(
            "m-42",
            navController.currentBackStackEntry?.arguments?.getString("mediaId"),
        )
        assertNull(deviceArgAfterNavigate(Routes.viewer("m-42")))
    }
}
