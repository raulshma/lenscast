package com.raulshma.lenscast.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The P4 routes' optional-`device` argument, mirroring
 * [RoutesCameraRouteTest]'s shape: the builders' output and their round trip
 * through the same query-arg extraction the NavHost performs on each
 * `…?device=<id>` route (split the query, read `device`; absent → null = the
 * local backend). The plain forms are pinned because the local navigation
 * (app settings' event-log row, a local gallery's tap-through, the update
 * deep link's `detection-events` extra) rides them verbatim.
 */
class RoutesDeviceArgTest {

    /** Mirrors Navigation-Compose's query-arg read of `<route>?device=<id>`. */
    private fun parseDevice(route: String): String? =
        route.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("device=") }
            ?.substringAfter('=')

    // ── gallery ──

    @Test
    fun `the gallery pattern carries the optional query arg and the builder has a plain local form`() {
        assertEquals("gallery?device={device}", Routes.GALLERY)
        assertEquals("gallery", Routes.gallery())
        assertEquals("gallery", Routes.gallery(null))
    }

    @Test
    fun `a gallery device id round-trips through the query arg`() {
        val route = Routes.gallery("dev-1")
        assertEquals("gallery?device=dev-1", route)
        assertEquals("dev-1", parseDevice(route))
    }

    // ── detection events ──

    @Test
    fun `the events pattern wraps the capture const and the builder preserves the deep-link form`() {
        assertEquals("detection-events?device={device}", Routes.DETECTION_EVENTS)
        // The plain form is the value the detection notifier's deep-link
        // extra carries — it must stay exactly the capture package's const.
        assertEquals("detection-events", Routes.detectionEvents())
        assertEquals("detection-events", Routes.detectionEvents(null))
    }

    @Test
    fun `an events device id round-trips through the query arg`() {
        val route = Routes.detectionEvents("dev-2")
        assertEquals("detection-events?device=dev-2", route)
        assertEquals("dev-2", parseDevice(route))
    }

    // ── viewer ──

    @Test
    fun `the viewer pattern keeps its mediaId arg and gains the optional device arg`() {
        assertEquals("viewer/{mediaId}?device={device}", Routes.VIEWER)
        assertEquals("viewer/m-42", Routes.viewer("m-42"))
        assertEquals("viewer/m-42", Routes.viewer("m-42", null))
    }

    @Test
    fun `a viewer device id round-trips alongside the media id`() {
        val route = Routes.viewer("m-42", "dev-3")
        assertEquals("viewer/m-42?device=dev-3", route)
        assertEquals("dev-3", parseDevice(route))
        // The positional media id is untouched by the query arg.
        assertTrue(route.startsWith("viewer/m-42"))
        assertFalse(route.substringBefore('?').contains("dev-3"))
    }

    // ── capture (P5) ──

    @Test
    fun `the capture pattern carries the optional query arg and the builder has a plain local form`() {
        assertEquals("capture?device={device}", Routes.CAPTURE)
        assertEquals("capture", Routes.capture())
        assertEquals("capture", Routes.capture(null))
    }

    @Test
    fun `a capture device id round-trips through the query arg`() {
        val route = Routes.capture("dev-4")
        assertEquals("capture?device=dev-4", route)
        assertEquals("dev-4", parseDevice(route))
    }

    // ── automation (P5) ──

    @Test
    fun `the automation pattern carries the optional query arg and the builder has a plain local form`() {
        assertEquals("automation?device={device}", Routes.AUTOMATION)
        assertEquals("automation", Routes.automation())
        assertEquals("automation", Routes.automation(null))
    }

    @Test
    fun `an automation device id round-trips through the query arg`() {
        val route = Routes.automation("dev-5")
        assertEquals("automation?device=dev-5", route)
        assertEquals("dev-5", parseDevice(route))
    }

    @Test
    fun `the P5 builders keep the plain forms the deep links and settings rows navigate`() {
        // The app-settings screen's automation row and the dashboard's menu
        // navigate the plain forms for the local backends — the pattern must
        // never be navigated raw.
        assertFalse(Routes.capture().contains('{'))
        assertFalse(Routes.automation().contains('{'))
        assertFalse(Routes.capture().contains('?'))
        assertFalse(Routes.automation().contains('?'))
    }

    // ── settings + app-settings (P6) ──

    @Test
    fun `the settings pattern carries the optional query arg and the builder has a plain local form`() {
        assertEquals("settings?device={device}", Routes.SETTINGS)
        assertEquals("settings", Routes.settings())
        assertEquals("settings", Routes.settings(null))
    }

    @Test
    fun `a settings device id round-trips through the query arg`() {
        val route = Routes.settings("dev-6")
        assertEquals("settings?device=dev-6", route)
        assertEquals("dev-6", parseDevice(route))
    }

    @Test
    fun `the app-settings pattern carries the optional query arg and keeps the deep-link form`() {
        assertEquals("app-settings?device={device}", Routes.APP_SETTINGS)
        // The update deep-link extra carries the PLAIN form — Navigation-
        // Compose must keep matching it against the pattern (the
        // detection-events rule).
        assertEquals("app-settings", Routes.appSettings())
        assertEquals("app-settings", Routes.appSettings(null))
    }

    @Test
    fun `an app-settings device id round-trips through the query arg`() {
        val route = Routes.appSettings("dev-7")
        assertEquals("app-settings?device=dev-7", route)
        assertEquals("dev-7", parseDevice(route))
    }

    @Test
    fun `the P6 builders keep the plain forms the dashboard menu and deep links navigate`() {
        assertFalse(Routes.settings().contains('{'))
        assertFalse(Routes.appSettings().contains('{'))
        assertFalse(Routes.settings().contains('?'))
        assertFalse(Routes.appSettings().contains('?'))
    }
}
