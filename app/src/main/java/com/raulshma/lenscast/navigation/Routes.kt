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
 *
 * The companion mode adds the paired-device surfaces: [DEVICES] (the
 * roster) and [CAMERA] — the roster's row tap lands the device in the
 * camera dashboard over its remote console. [CAMERA]'s optional
 * `device` argument (built with [camera]) opens it against a paired device,
 * and the same optional argument (built with [gallery]/[detectionEvents]/[viewer]/[settings]/[appSettings])
 * parameterizes the gallery, the event log, the media viewer, and the two
 * settings screens onto that device's remote backends.
 */
object Routes {
    /**
     * The camera dashboard — the NavHost's registration pattern. Its single
     * optional query argument `device` selects the backend: null (the
     * argument omitted) is this phone's local camera; a paired device id
     * opens the dashboard against that device's remote console. Build
     * concrete routes with [camera] — never navigate the raw pattern (its
     * `{device}` placeholder is not a value). The placeholder is named for
     * the argument [NavigationGraph] declares and reads (`device`), so the
     * query value Navigation binds lands under the key the screen looks up.
     */
    const val CAMERA = "camera?device={device}"

    /**
     * The no-argument local form of [CAMERA] — the NavHost's start
     * destination (the destination pattern matches it, with `device` null).
     */
    const val CAMERA_LOCAL = "camera"

    /**
     * Builds [CAMERA]: null (or omitted) is the local dashboard's
     * [CAMERA_LOCAL] form; a device id opens the dashboard against that
     * device's remote console.
     */
    fun camera(deviceId: String? = null): String =
        if (deviceId == null) CAMERA_LOCAL else "$CAMERA_LOCAL?device=$deviceId"

    /**
     * The camera-settings screen — the NavHost's registration pattern. Its
     * optional `device` query argument (built with [settings]) selects the
     * backend: the no-arg form edits this phone's camera settings, a paired
     * device id opens the same screen against the device's settings document
     * (the camera section of `GET/PUT /api/settings`).
     */
    const val SETTINGS = "settings?device={device}"

    /** Builds [SETTINGS]: null (or omitted) is the local screen's plain form. */
    fun settings(deviceId: String? = null): String =
        if (deviceId == null) "settings" else "settings?device=$deviceId"

    /**
     * The app-settings screen — the NavHost's registration pattern, and the
     * plain form stays the value the update deep-link extra carries (the
     * [DETECTION_EVENTS] rule: Navigation-Compose matches the plain value
     * against the pattern with the query arg null). Its optional `device`
     * query argument (built with [appSettings]) selects the backend; the
     * remote form also carries the folded diagnostics section.
     */
    const val APP_SETTINGS = "app-settings?device={device}"

    /** Builds [APP_SETTINGS]: null (or omitted) is the local screen's plain form. */
    fun appSettings(deviceId: String? = null): String =
        if (deviceId == null) "app-settings" else "app-settings?device=$deviceId"

    /**
     * The deterrence automation screen — the NavHost's registration pattern.
     * Its optional `device` query argument (built with [automation]) selects
     * the backend: the no-arg form edits this phone's rules, a paired device
     * id opens the same editor against that device's `GET/PUT
     * /api/automation` document.
     */
    const val AUTOMATION = "automation?device={device}"

    /** Builds [AUTOMATION]: null (or omitted) is the local editor's plain form. */
    fun automation(deviceId: String? = null): String =
        if (deviceId == null) "automation" else "automation?device=$deviceId"

    /**
     * The capture screen (interval + scheduled capture) — the NavHost's
     * registration pattern. Its optional `device` query argument (built with
     * [capture]) selects the backend: the no-arg form drives this phone's
     * pipeline, a paired device id the same screen against that device's
     * capture routes.
     */
    const val CAPTURE = "capture?device={device}"

    /** Builds [CAPTURE]: null (or omitted) is the local pipeline's plain form. */
    fun capture(deviceId: String? = null): String =
        if (deviceId == null) "capture" else "capture?device=$deviceId"

    /**
     * The gallery grid — the NavHost's registration pattern. Its optional
     * `device` query argument (built with [gallery]) selects the backend:
     * the no-arg form is this phone's local library, a paired device id
     * opens the same grid against that device's `/api/gallery`.
     */
    const val GALLERY = "gallery?device={device}"

    /** Builds [GALLERY]: null (or omitted) is the local library's plain form. */
    fun gallery(deviceId: String? = null): String =
        if (deviceId == null) "gallery" else "gallery?device=$deviceId"

    /**
     * The detection event log — the capture package's route const wrapped
     * with the optional `device` argument (built with [detectionEvents]):
     * no-arg is this phone's [DETECTION_EVENTS_ROUTE] log verbatim (the
     * deep-link extra's value still resolves — Navigation-Compose matches
     * the plain form against the pattern with the query arg null), an id
     * opens the log against the paired device's remote feed.
     */
    const val DETECTION_EVENTS = "$DETECTION_EVENTS_ROUTE?device={device}"

    /** Builds [DETECTION_EVENTS]: null (or omitted) is the local log's plain form. */
    fun detectionEvents(deviceId: String? = null): String =
        if (deviceId == null) DETECTION_EVENTS_ROUTE else "$DETECTION_EVENTS_ROUTE?device=$deviceId"

    /**
     * The media viewer route pattern; the `mediaId` argument is [viewer]'s
     * input and the optional `device` query argument re-opens the same pager
     * over a paired device's library (a remote gallery's tap-through must
     * page the same items its grid showed, so the arg rides along).
     */
    const val VIEWER = "viewer/{mediaId}?device={device}"

    /** Builds [VIEWER] for one media id (and, remotely, its device backend). */
    fun viewer(mediaId: String, deviceId: String? = null): String =
        if (deviceId == null) "viewer/$mediaId" else "viewer/$mediaId?device=$deviceId"

    /** The companion device roster. */
    const val DEVICES = "devices"
}
