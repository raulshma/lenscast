package com.raulshma.lenscast.automation.schedule

/**
 * The closed vocabulary of automation icon kinds GET /api/automation publishes
 * on every catalog entry (`icon` on the action/trigger/condition catalogs).
 * It is the single seam between the catalogs and the two renderers: the app's
 * `AutomationVisuals` maps kind → Material drawable, the web dashboard's
 * `automationIcons.tsx` maps kind → inline SVG glyph — so a catalog entry's
 * visual identity is published device truth, not a hand-mirror on either
 * client. Adding a catalog entry means picking (or, only with real
 * justification, adding) a kind from this set; both renderers fall back to a
 * neutral glyph/drawable for an unknown kind, so an older client never blanks
 * a badge on a newer catalog.
 *
 * One kind always renders the same visual on both sides (they are
 * translations of one semantic idea, not pixel-identical art). Kinds are
 * shared only where the two entries genuinely share a visual concept:
 * `power` (the bolt: the charging trigger and the charging condition) and
 * `radar` (the sweeping rings: the detection action and the
 * motion-detection condition). Every other visual is its own kind — 29 kinds
 * covering the 31 catalog entries today. The set is closed and pinned by a
 * JVM test (AutomationVisualsTest) plus the web contract test; never rename
 * a kind without moving both renderers in the same commit.
 */
object AutomationIconVocabulary {

    // ── time / schedule ──
    /** A clock face: the time-of-day trigger. */
    const val SCHEDULE = "schedule"

    /** A clock with a day-span bar: the time-window condition. */
    const val TIME_WINDOW = "time_window"

    // ── detection ──
    /** Radiating sensor waves: the motion-detected trigger. */
    const val SENSOR = "sensor"

    /** Equalizer bars: the sound-detected trigger. */
    const val SOUND = "sound"

    /** Sweeping radar rings: the detection action and the motion-detection condition. */
    const val RADAR = "radar"

    // ── power ──
    /** A lightning bolt: the charging-changed trigger and the charging condition. */
    const val POWER = "power"

    /** A battery carrying a warning mark: the battery-level trigger. */
    const val BATTERY_ALERT = "battery_alert"

    /** A plain battery: the battery-level condition. */
    const val BATTERY = "battery"

    /** A battery in saver mode: the power-save condition. */
    const val BATTERY_SAVER = "battery_saver"

    /** A security shield: the tamper-detected trigger. */
    const val SHIELD = "shield"

    // ── stream & app ──
    /** A cast screen: the streaming-changed trigger. */
    const val CAST = "cast"

    /** A watching eye: the viewers-changed trigger. */
    const val EYE = "eye"

    /** A launching rocket: the app-started trigger. */
    const val ROCKET = "rocket"

    /** Radiating broadcast waves over a dot: the stream action. */
    const val BROADCAST = "broadcast"

    /** A screen with a play wedge: the streaming state condition. */
    const val STREAM = "stream"

    /** A person silhouette: the viewers condition. */
    const val PEOPLE = "people"

    /** Wifi arcs: the wifi-connected condition. */
    const val WIFI = "wifi"

    // ── camera ──
    /** Sun-dial exposure rings: the camera-exposure action. */
    const val EXPOSURE = "exposure"

    /** A half-toned circle (the EV +/- idea): the camera-EV action. */
    const val TONALITY = "tonality"

    /** A magnifier with a plus: the camera-zoom action. */
    const val ZOOM = "zoom"

    /** Fast-forward chevrons: the camera-fps action. */
    const val SPEED = "speed"

    /** Concentric lens rings: the camera-lens action. */
    const val LENS = "lens"

    /** A photo camera: the capture-photo action. */
    const val CAMERA = "camera"

    // ── light & sound ──
    /** A flashlight: the torch action. */
    const val TORCH = "torch"

    /** A light bulb: the torch-state condition. */
    const val LIGHTBULB = "lightbulb"

    /** An alarm bell: the siren action. */
    const val SIREN = "siren"

    // ── recording ──
    /** A video camera: the recording action. */
    const val VIDEOCAM = "videocam"

    /** A filled record dot: the recording-state condition. */
    const val RECORD = "record"

    /** A thermometer: the thermal condition. */
    const val THERMOSTAT = "thermostat"

    /** Every kind, as a closed set — pinned by test so the renderers and the catalogs cannot drift apart. */
    val ALL: Set<String> = setOf(
        SCHEDULE, TIME_WINDOW,
        SENSOR, SOUND, RADAR,
        POWER, BATTERY_ALERT, BATTERY, BATTERY_SAVER,
        SHIELD,
        CAST, EYE, ROCKET, BROADCAST, STREAM, PEOPLE, WIFI,
        EXPOSURE, TONALITY, ZOOM, SPEED, LENS, CAMERA,
        TORCH, LIGHTBULB, SIREN,
        VIDEOCAM, RECORD, THERMOSTAT,
    )
}
