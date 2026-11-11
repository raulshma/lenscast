package com.raulshma.lenscast.navigation

import com.raulshma.lenscast.capture.DETECTION_EVENTS_ROUTE

/**
 * The one nav-route vocabulary: every route string the in-app NavHost
 * registers and navigates with, and the values deep-link extras carry —
 * the update notification's `navigate_to` extra holds [APP_SETTINGS] and
 * MainActivity re-emits the extra's value verbatim as a navigation event,
 * which [NavigationGraph] feeds to `navigate`. Route literals belong here
 * and nowhere else: a screen registers with the same constant its
 * navigators call, so a rename is one edit instead of a string hunt.
 *
 * The detection event log stays single-sourced in the capture package:
 * [DETECTION_EVENTS] aliases [DETECTION_EVENTS_ROUTE] rather than
 * duplicating the literal.
 */
object Routes {
    /** The camera dashboard — the NavHost's start destination. */
    const val CAMERA = "camera"

    /** The camera-settings screen (per-camera controls). */
    const val SETTINGS = "settings"

    /** The app-settings screen — the value the deep-link extra carries. */
    const val APP_SETTINGS = "app-settings"

    /** The deterrence automation screen. */
    const val AUTOMATION = "automation"

    /** The capture screen (interval + scheduled capture). */
    const val CAPTURE = "capture"

    /** The gallery grid. */
    const val GALLERY = "gallery"

    /** The detection event log — the capture package's route const, aliased. */
    const val DETECTION_EVENTS = DETECTION_EVENTS_ROUTE

    /** The media viewer route pattern; the `mediaId` argument is [viewer]'s input. */
    const val VIEWER = "viewer/{mediaId}"

    /** Builds [VIEWER] for one media id. */
    fun viewer(mediaId: String): String = "viewer/$mediaId"
}
