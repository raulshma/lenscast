package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.NightVisionMode

/**
 * The value type of one automation parameter: its wire name, how to parse
 * it, its documented bounds, and the default a required-but-absent (or
 * invalid) value folds to. Pure data — the parsing/clamping lives in
 * [AutomationActionCatalog.sanitizeAction].
 *
 * @param name The exact wire key. Other agents (CameraService,
 *   AutomationReceiver) depend on these strings — never rename one without
 *   checking the catalog test that pins them.
 * @param kind Interpretation of the string value.
 * @param min Lower bound, or null for unbounded. Shared with FLOAT params —
 *   bounds are doubles so INT and FLOAT need one mechanism.
 * @param max Upper bound, or null for unbounded.
 * @param optional When true, an absent parameter means "leave the current
 *   setting unchanged" (semantics the executor honors); when false, an
 *   absent parameter folds to [defaultValue].
 * @param defaultValue The canonical value an absent required parameter or an
 *   invalid value folds to. Empty string for optional params that have no
 *   persisted default ("device default"/"run until stopped") — such a param
 *   folds back to absent, the only honest encoding of "no opinion".
 * @param autoAtZero '0' is the auto/unlimited sentinel — clients may render
 *   an Auto toggle for it; false means 0 is an ordinary value.
 * @param values The closed value set of a STRING param (canonical lowercase
 *   ids, in display order); empty for BOOL/INT/FLOAT params.
 */
data class AutomationParamSpec(
    val name: String,
    val kind: ParamKind,
    val min: Double?,
    val max: Double?,
    val optional: Boolean,
    val defaultValue: String,
    val autoAtZero: Boolean = false,
    val values: List<String> = emptyList(),
)

/**
 * How a parameter's string value is interpreted. STRING params carry their
 * closed value set on [AutomationParamSpec.values] — canonical lowercase ids,
 * parsed leniently (case/whitespace) and folded to the default when invalid.
 */
enum class ParamKind { BOOL, INT, FLOAT, STRING }

/**
 * What happens to an action when its rule fires late (device asleep, process
 * dead at the scheduled minute). STATEFUL actions converge on a setting, so
 * firing them late is always safe and they always ride the catch-up;
 * ONESHOT actions have a moment effect, so replaying them hours later would
 * surprise the user and they only fire inside the grace window
 * ([com.raulshma.lenscast.core.StreamDefaults.AUTOMATION_ONESHOT_GRACE_MS]).
 */
enum class CatchUpClass { STATEFUL, ONESHOT }

/**
 * One executable capability step an automation action dispatches — the
 * closed vocabulary the executor interprets against its collaborators.
 * Effects are deliberately param-free declarations (the WHAT, not the
 * verdict): the executor derives each step's verdict from the action's
 * params at dispatch, through its own seam contracts. A new action built
 * from existing capability vocabulary declares existing effects and touches
 * none of this machinery.
 */
sealed interface AutomationEffect {
    /**
     * Merge the action's params into the persisted
     * [CameraSettings] document (the executor's applyCameraSetting path —
     * the Settings Applier applies them in place with no stream
     * interruption). The merge semantics are the entry's [AutomationActionType.merge].
     */
    data object MergeCameraSettings : AutomationEffect

    /** Switch the live lens to the `index` param, clamped into the live inventory. */
    data object SwitchLens : AutomationEffect

    /** Start or stop the stream server per the `enabled` verdict. */
    data object ToggleStream : AutomationEffect

    /** One gallery photo capture; failures log, never throw into the rule loop. */
    data object CapturePhoto : AutomationEffect

    /** Start (with the duration verdict) or stop the Recording Controller. */
    data object ToggleRecording : AutomationEffect

    /** Arm (with the optional auto-stop delay) or cancel-and-stop the shared siren. */
    data object ToggleSiren : AutomationEffect

    /** Persist the motion/sound detection toggles, one save per param present. */
    data object SaveDetectionToggles : AutomationEffect
}

/**
 * The pure reading of the live world a snapshot's axes capture from: the
 * persisted camera document (disk truth) plus the live facts no store
 * answers for. [com.raulshma.lenscast.automation.AutomationTestController]
 * assembles it once per arm from the app's seams; the axes' capture lambdas
 * ([RestoreAxis.capture]) stay pure over it.
 */
data class AutomationLiveState(
    /** The persisted camera document (`settingsFromDisk` — read-your-write). */
    val camera: CameraSettings,
    /** The live selected lens' position in the camera's own enumeration. */
    val lensIndex: Int,
    /** The LIVE LED fact — live-only writers can desync it from the stored intent. */
    val torchOn: Boolean,
    val motionEnabled: Boolean,
    val soundEnabled: Boolean,
    /** Whether the whole server (web + RTSP) is running. */
    val streamRunning: Boolean,
    /** Whether the Recording Controller is Idle — a live recording is NOT automation's doing. */
    val recordingIdle: Boolean,
)

/**
 * One restorable snapshot axis — a named slot of the pre-test world the
 * Automation Test can wind back. The enum is the axis vocabulary the catalog
 * entries declare; the snapshot itself is the rendered value per axis.
 *
 * @param param The wire param this axis restores through; null for a gate
 *   axis no action param reads (the recording stop's idle gate).
 * @param capture Renders the axis's live value from one
 *   [AutomationLiveState] reading — the snapshot's capture loop. Returns
 *   null when the axis has no value (the device default), which the restore
 *   skips; renders that are never null fold their absent reading to the
 *   documented sentinel inline (the exposure "0" auto spelling).
 */
enum class RestoreAxis(
    val param: String?,
    val capture: (AutomationLiveState) -> String?,
) {
    /** null ISO is driver auto — restored through the catalog's "0" auto sentinel. */
    ISO("iso", { it.camera.iso?.toString() ?: "0" }),

    /** Nanoseconds on the document, milliseconds on the wire; auto folds to "0". */
    EXPOSURE_TIME_MS(
        "exposure_time_ms",
        { it.camera.exposureTime?.let { ns -> ns / 1_000_000.0 }?.toString() ?: "0" },
    ),

    EV_INDEX("index", { it.camera.exposureCompensation.toString() }),
    ZOOM_RATIO("ratio", { it.camera.zoomRatio.toString() }),
    FRAME_RATE("fps", { it.camera.frameRate.toString() }),
    LENS_INDEX("index", { it.lensIndex.toString() }),

    /** The enum's catalog-canonical lowercase id (the merge's own vocabulary). */
    NIGHT_VISION("mode", { it.camera.nightVisionMode.name.lowercase() }),

    TORCH_ENABLED("enabled", { it.torchOn.toString() }),

    /** null = device default strength — the strength param's absence. */
    TORCH_STRENGTH("strength", { it.camera.torchStrength?.toString() }),

    STREAM_RUNNING("enabled", { it.streamRunning.toString() }),
    MOTION_ENABLED("motion_enabled", { it.motionEnabled.toString() }),
    SOUND_ENABLED("sound_enabled", { it.soundEnabled.toString() }),

    /** The recording stop's gate, not a param: a live recording is the user's. */
    RECORDING_IDLE(null, { it.recordingIdle.toString() }),
}

/**
 * One axis an entry restores, with its gate: optional params restore ONLY
 * when the rule's action actually carried them (a rule that set only ISO
 * must not also force exposure back to auto), required params restore always
 * (the action always converged a definite verdict for them).
 */
data class RestorableAxis(
    val axis: RestoreAxis,
    /** true = restore only when the action's params carry the axis's param. */
    val onlyIfTouched: Boolean = false,
)

/**
 * How the Automation Test winds one action back (issue #18 item 3 — "press
 * again to restore the previous state"). The per-entry declaration here is
 * the one home of the restore semantics; the restore policy just interprets
 * them generically over the snapshot.
 */
sealed interface RestoreSpec {

    /** The snapshot axes this spec reads — the capture loop's union input. */
    val declaredAxes: List<RestoreAxis>

    /**
     * Restore each declared axis the rule touched from the snapshot's values.
     * Axes the snapshot left valueless (e.g. a device-default torch strength)
     * restore nothing.
     */
    data class Axes(val axes: List<RestorableAxis>) : RestoreSpec {
        override val declaredAxes: List<RestoreAxis> get() = axes.map { it.axis }
    }

    /**
     * Restore as an explicit stop — a test that started the effect stops it
     * on the way back and never re-arms it; a rule whose action asked for the
     * stopped state (`enabled = false`) has nothing to restore. Siren.
     */
    object StopWhenArmed : RestoreSpec {
        override val declaredAxes: List<RestoreAxis> get() = emptyList()
    }

    /**
     * Stop, but only when the Recording Controller was Idle at arm time — the
     * test started the only recording that can be the test's doing, and the
     * user's own recording survives the restore.
     */
    object StopWhenArmedAndIdle : RestoreSpec {
        override val declaredAxes: List<RestoreAxis> get() = listOf(RestoreAxis.RECORDING_IDLE)
    }

    /** Not restorable — the action's product is a capture, not a state. */
    object NotRestorable : RestoreSpec {
        override val declaredAxes: List<RestoreAxis> get() = emptyList()
    }
}

/**
 * One automation action the engine can execute: its id, parameter schema,
 * catch-up class, and the BEHAVIOR ANATOMY — the dispatch effects, the pure
 * [CameraSettings] merge, and the restore spec — plus the published picker
 * decorations ([group] and [icon]; see [AutomationIconVocabulary] for the
 * icon kind's closed meaning).
 *
 * The anatomy fields are the one registration point for an action's
 * semantics: the executor iterates [effects] against its collaborators, the
 * schedule policy delegates [merge], and the restore policy interprets
 * [restore] over the snapshot — no per-action ladder lives outside this
 * entry. Adding an action with existing capability vocabulary is an entry
 * (plus locale strings); the interpreters are stable.
 *
 * [interruptsStream] is a UI warning flag only — the executor does NOT
 * force a teardown/rebind around the actions it marks. The one flagged type
 * (`camera_lens`) switches through `CameraService.selectLens`, whose
 * `CameraSessionArbiter` rebinds immediately when the camera is free (a
 * brief preview/stream interruption) and defers to the next natural rebind
 * while an exclusive session (a recording) is active.
 *
 * @param merge Folds one action's params into the current persisted
 *   [CameraSettings]. Identity for actions the camera pipeline does not
 *   consume (the executor routes them to their own seams); the camera-valued
 *   merges follow the "absent/malformed = leave unchanged" contract — a
 *   malformed value must never collapse a manual setting to a sentinel.
 * @param effects The ordered dispatch steps the executor interprets. Every
 *   action declares at least one.
 * @param restore How the Automation Test winds the action back; see
 *   [RestoreSpec].
 */
data class AutomationActionType(
    val id: String,
    val params: List<AutomationParamSpec>,
    val catchUp: CatchUpClass,
    val merge: (current: CameraSettings, params: Map<String, String>) -> CameraSettings,
    val effects: List<AutomationEffect>,
    val restore: RestoreSpec,
    val interruptsStream: Boolean = false,
    /** The picker group this entry lists under — a semantic id, never renamed. */
    val group: String,
    /** The published icon kind (a closed vocabulary; see [AutomationIconVocabulary]). */
    val icon: String,
)

/**
 * The catalog of what an automation rule may do: ids, param schemas (names /
 * kinds / bounds / defaults / autoAtZero), catch-up classes and each entry's
 * behavior anatomy (merge / dispatch effects / restore spec) are
 * single-sourced here — an action's semantics are known in ONE place, this
 * entry. Pretty labels stay at the UI seams by design (exhaustive label
 * `when`s over a closed set; the web summary switch is the same seam). The
 * Settings Store's codec sanitizes through it, the schedule policy merges
 * camera actions through it, the receiver/executor resolve ids and dispatch
 * through it, and the restore policy/capture loop read its declared restore
 * axes. The catalog test pins the exact id/param-name strings and the
 * per-entry anatomy, so a typo or a drifted declaration fails a JVM test
 * instead of silently disabling a feature.
 *
 * Sentinels documented per entry: `iso`/`exposure_time_ms` "0" mean auto;
 * `recording.duration_seconds` "0" means unbounded; `siren.duration_seconds`
 * absent means run-until-stopped while "0" means an explicit no-limit.
 * GET /api/automation also publishes each entry's picker `group` and `icon`
 * kind, so both editors (app screen and web dashboard) group and badge the
 * entries from this one source.
 */
object AutomationActionCatalog {

    // Picker group ids — semantic, wire-published (the catalog DTO's
    // `group`), never renamed; the labels live on the clients (string
    // resources / i18n keys keyed off the id).
    const val GROUP_CAMERA = "camera"
    const val GROUP_LIGHT_SOUND = "light_sound"
    const val GROUP_STREAM_CAPTURE = "stream_capture"
    const val GROUP_DETECTION = "detection"

    /** The merge that leaves the camera document untouched (non-camera actions). */
    private val MERGE_IDENTITY: (CameraSettings, Map<String, String>) -> CameraSettings =
        { current, _ -> current }

    /**
     * The camera_exposure merge: ONLY the exact "0" sentinel is the auto
     * sentinel → null (driver auto); any other parseable value is the manual
     * ISO / the exposure milliseconds (→ nanoseconds on the document), and an
     * unparseable one leaves its field unchanged — a malformed value must
     * never fold a manual setting back to auto. Several axes merge in one
     * pass, and one malformed field never blocks the parseable one.
     */
    private val MERGE_CAMERA_EXPOSURE: (CameraSettings, Map<String, String>) -> CameraSettings =
        { current, params ->
            var merged = current
            params["iso"]?.let { raw ->
                if (raw == "0") {
                    merged = merged.copy(iso = null)
                } else {
                    raw.toIntOrNull()?.let { iso -> merged = merged.copy(iso = iso) }
                }
            }
            params["exposure_time_ms"]?.let { raw ->
                if (raw == "0") {
                    merged = merged.copy(exposureTime = null)
                } else {
                    raw.toDoubleOrNull()?.let { ms ->
                        merged = merged.copy(exposureTime = (ms * 1_000_000).toLong())
                    }
                }
            }
            merged
        }

    /** The camera_ev merge: the compensation index, unchanged on an unparseable value. */
    private val MERGE_CAMERA_EV: (CameraSettings, Map<String, String>) -> CameraSettings =
        { current, params ->
            params["index"]?.toIntOrNull()?.let { index ->
                current.copy(exposureCompensation = index)
            } ?: current
        }

    /** The camera_zoom merge: the magnification ratio, unchanged on an unparseable value. */
    private val MERGE_CAMERA_ZOOM: (CameraSettings, Map<String, String>) -> CameraSettings =
        { current, params ->
            params["ratio"]?.toFloatOrNull()?.let { ratio ->
                current.copy(zoomRatio = ratio)
            } ?: current
        }

    /** The camera_fps merge: the frame rate, unchanged on an unparseable value. */
    private val MERGE_CAMERA_FPS: (CameraSettings, Map<String, String>) -> CameraSettings =
        { current, params ->
            params["fps"]?.toIntOrNull()?.let { fps ->
                current.copy(frameRate = fps)
            } ?: current
        }

    /**
     * The torch merge: the toggle flips on the canonical "true"/"false"
     * (absent/unknown leaves the current torch state alone), and strength
     * applies only when the optional param is present — absent means "device
     * default", and clobbering a strength the user set elsewhere is not this
     * action's business.
     */
    private val MERGE_TORCH: (CameraSettings, Map<String, String>) -> CameraSettings =
        { current, params ->
            var merged = current
            when (params["enabled"]) {
                "true" -> merged = merged.copy(torchEnabled = true)
                "false" -> merged = merged.copy(torchEnabled = false)
                // absent/unknown: leave the current torch state alone
            }
            params["strength"]?.toIntOrNull()?.let { strength ->
                merged = merged.copy(torchStrength = strength)
            }
            merged
        }

    /**
     * The night_vision merge: the canonical catalog id maps onto
     * [NightVisionMode]; the else-arm is the hand-built-spec guard (an
     * unknown mode degrades to "no change", like every unparseable param).
     */
    private val MERGE_NIGHT_VISION: (CameraSettings, Map<String, String>) -> CameraSettings =
        { current, params ->
            params["mode"]?.let { mode ->
                when (mode) {
                    "off" -> current.copy(nightVisionMode = NightVisionMode.OFF)
                    "auto" -> current.copy(nightVisionMode = NightVisionMode.AUTO)
                    "on" -> current.copy(nightVisionMode = NightVisionMode.ON)
                    else -> current
                }
            } ?: current
        }

    /**
     * Every executable action, in display/contract order. Additive only:
     * entries are persisted by id and decoded forever, so existing entries
     * must keep their ids and param names.
     */
    val ACTION_TYPES: List<AutomationActionType> = listOf(
        AutomationActionType(
            id = "camera_exposure",
            params = listOf(
                AutomationParamSpec(
                    name = "iso", kind = ParamKind.INT,
                    // The span covers modern manual-night sensors (issue #16
                    // asked for ISO 4000; 10_000 covers today's sensor
                    // ceilings without inviting garbage). NOT an apply-time
                    // ceiling — the device's sensor range always wins:
                    // CameraControlPlan clamps to the live device range, and
                    // the app's own web settings path already accepts higher
                    // ISOs. This is the UI/persistence bound only.
                    min = 0.0, max = 10_000.0,
                    optional = true, defaultValue = "0", // 0 = auto; > 0 = manual
                    autoAtZero = true,
                ),
                AutomationParamSpec(
                    name = "exposure_time_ms", kind = ParamKind.FLOAT,
                    min = 0.0, max = 1000.0,
                    optional = true, defaultValue = "0", // 0 = auto
                    autoAtZero = true,
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
            merge = MERGE_CAMERA_EXPOSURE,
            effects = listOf(AutomationEffect.MergeCameraSettings),
            // Optional params: restore only the axes the rule actually touched.
            restore = RestoreSpec.Axes(
                listOf(
                    RestorableAxis(RestoreAxis.ISO, onlyIfTouched = true),
                    RestorableAxis(RestoreAxis.EXPOSURE_TIME_MS, onlyIfTouched = true),
                ),
            ),
            group = GROUP_CAMERA,
            icon = AutomationIconVocabulary.EXPOSURE,
        ),
        AutomationActionType(
            id = "camera_ev",
            params = listOf(
                AutomationParamSpec(
                    name = "index", kind = ParamKind.INT,
                    min = -12.0, max = 12.0,
                    optional = false, defaultValue = "0",
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
            merge = MERGE_CAMERA_EV,
            effects = listOf(AutomationEffect.MergeCameraSettings),
            restore = RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.EV_INDEX))),
            group = GROUP_CAMERA,
            icon = AutomationIconVocabulary.TONALITY,
        ),
        AutomationActionType(
            id = "camera_zoom",
            params = listOf(
                AutomationParamSpec(
                    name = "ratio", kind = ParamKind.FLOAT,
                    min = 1.0, max = 10.0,
                    optional = false, defaultValue = "1",
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
            merge = MERGE_CAMERA_ZOOM,
            effects = listOf(AutomationEffect.MergeCameraSettings),
            restore = RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.ZOOM_RATIO))),
            group = GROUP_CAMERA,
            icon = AutomationIconVocabulary.ZOOM,
        ),
        AutomationActionType(
            id = "camera_fps",
            params = listOf(
                AutomationParamSpec(
                    name = "fps", kind = ParamKind.INT,
                    min = 1.0, max = 120.0,
                    optional = false, defaultValue = "24",
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
            merge = MERGE_CAMERA_FPS,
            effects = listOf(AutomationEffect.MergeCameraSettings),
            restore = RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.FRAME_RATE))),
            group = GROUP_CAMERA,
            icon = AutomationIconVocabulary.SPEED,
        ),
        AutomationActionType(
            id = "camera_lens",
            // The lens switch goes through CameraService.selectLens, whose
            // CameraSessionArbiter rebinds when the camera is free and
            // defers while an exclusive session holds it — the flag above is
            // the UI's "this may briefly interrupt the stream" warning, not
            // a teardown directive the executor acts on.
            params = listOf(
                AutomationParamSpec(
                    name = "index", kind = ParamKind.INT,
                    min = 0.0, max = 8.0,
                    optional = false, defaultValue = "0",
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
            // The lens is a LIVE switch, not a persisted camera value: the
            // merge stays identity (the snapshot's lens axis rides its own
            // live reading, and the restore drives the live switch).
            merge = MERGE_IDENTITY,
            effects = listOf(AutomationEffect.SwitchLens),
            restore = RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.LENS_INDEX))),
            interruptsStream = true,
            group = GROUP_CAMERA,
            icon = AutomationIconVocabulary.LENS,
        ),
        AutomationActionType(
            id = "night_vision",
            // The same camera setting the dashboard's camera section writes
            // (NightVisionMode, applied in CameraControlPlan's scene-mode
            // rungs) — the classic day/night rule pairs it with the exposure
            // action in one fire. Required param at the documented default:
            // a night_vision action always converges a definite mode, "off"
            // included — unlike the exposure params there is no "leave
            // unchanged" reading of an enum action.
            params = listOf(
                AutomationParamSpec(
                    name = "mode", kind = ParamKind.STRING,
                    min = null, max = null,
                    optional = false, defaultValue = "off",
                    values = listOf("off", "auto", "on"),
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
            merge = MERGE_NIGHT_VISION,
            effects = listOf(AutomationEffect.MergeCameraSettings),
            restore = RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.NIGHT_VISION))),
            group = GROUP_CAMERA,
            icon = AutomationIconVocabulary.NIGHTLIGHT,
        ),
        AutomationActionType(
            id = "torch",
            params = listOf(
                AutomationParamSpec(
                    name = "enabled", kind = ParamKind.BOOL,
                    min = null, max = null,
                    optional = false, defaultValue = "true",
                ),
                // API 33+ devices with a multi-level flash; absent = device default strength.
                AutomationParamSpec(
                    name = "strength", kind = ParamKind.INT,
                    min = 1.0, max = 8.0,
                    optional = true, defaultValue = "",
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
            merge = MERGE_TORCH,
            effects = listOf(AutomationEffect.MergeCameraSettings),
            // The toggle always restores (required param); only a rule that
            // drove a level restores one — and only to the snapshot's own
            // level (null = device default, the strength param's absence).
            restore = RestoreSpec.Axes(
                listOf(
                    RestorableAxis(RestoreAxis.TORCH_ENABLED),
                    RestorableAxis(RestoreAxis.TORCH_STRENGTH, onlyIfTouched = true),
                ),
            ),
            group = GROUP_LIGHT_SOUND,
            icon = AutomationIconVocabulary.TORCH,
        ),
        AutomationActionType(
            id = "stream",
            params = listOf(
                AutomationParamSpec(
                    name = "enabled", kind = ParamKind.BOOL,
                    min = null, max = null,
                    optional = false, defaultValue = "true",
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
            merge = MERGE_IDENTITY,
            effects = listOf(AutomationEffect.ToggleStream),
            restore = RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.STREAM_RUNNING))),
            group = GROUP_STREAM_CAPTURE,
            icon = AutomationIconVocabulary.BROADCAST,
        ),
        AutomationActionType(
            id = "capture_photo",
            params = emptyList(),
            catchUp = CatchUpClass.ONESHOT,
            merge = MERGE_IDENTITY,
            effects = listOf(AutomationEffect.CapturePhoto),
            restore = RestoreSpec.NotRestorable,
            group = GROUP_STREAM_CAPTURE,
            icon = AutomationIconVocabulary.CAMERA,
        ),
        AutomationActionType(
            id = "recording",
            params = listOf(
                AutomationParamSpec(
                    name = "enabled", kind = ParamKind.BOOL,
                    min = null, max = null,
                    optional = false, defaultValue = "true",
                ),
                // 0 = unbounded (stop manually or by rule) — a "no limit"
                // sentinel, NOT the auto sentinel (autoAtZero stays false).
                AutomationParamSpec(
                    name = "duration_seconds", kind = ParamKind.INT,
                    min = 0.0, max = 86_400.0,
                    optional = true, defaultValue = "0",
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
            merge = MERGE_IDENTITY,
            effects = listOf(AutomationEffect.ToggleRecording),
            restore = RestoreSpec.StopWhenArmedAndIdle,
            group = GROUP_STREAM_CAPTURE,
            icon = AutomationIconVocabulary.VIDEOCAM,
        ),
        AutomationActionType(
            id = "siren",
            params = listOf(
                AutomationParamSpec(
                    name = "enabled", kind = ParamKind.BOOL,
                    min = null, max = null,
                    optional = false, defaultValue = "true",
                ),
                // Absent = run until stopped; "0" = explicit no-limit (not
                // an auto sentinel).
                AutomationParamSpec(
                    name = "duration_seconds", kind = ParamKind.INT,
                    min = 0.0, max = 3600.0,
                    optional = true, defaultValue = "",
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
            merge = MERGE_IDENTITY,
            effects = listOf(AutomationEffect.ToggleSiren),
            restore = RestoreSpec.StopWhenArmed,
            group = GROUP_LIGHT_SOUND,
            icon = AutomationIconVocabulary.SIREN,
        ),
        AutomationActionType(
            id = "detection",
            params = listOf(
                AutomationParamSpec(
                    name = "motion_enabled", kind = ParamKind.BOOL,
                    min = null, max = null,
                    optional = true, defaultValue = "",
                ),
                AutomationParamSpec(
                    name = "sound_enabled", kind = ParamKind.BOOL,
                    min = null, max = null,
                    optional = true, defaultValue = "",
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
            merge = MERGE_IDENTITY,
            effects = listOf(AutomationEffect.SaveDetectionToggles),
            // Optional params: restore only the toggles the rule touched.
            restore = RestoreSpec.Axes(
                listOf(
                    RestorableAxis(RestoreAxis.MOTION_ENABLED, onlyIfTouched = true),
                    RestorableAxis(RestoreAxis.SOUND_ENABLED, onlyIfTouched = true),
                ),
            ),
            group = GROUP_DETECTION,
            icon = AutomationIconVocabulary.RADAR,
        ),
    )

    /**
     * Every snapshot axis any entry's restore reads — the declared axes in
     * catalog order plus the stop gates. The snapshot's capture loop is this
     * list: one render per axis from a single [AutomationLiveState] reading,
     * in place of the old hand-wired per-axis fields.
     */
    val SNAPSHOT_AXES: List<RestoreAxis> =
        ACTION_TYPES.flatMap { it.restore.declaredAxes }.distinct()

    private val byId: Map<String, AutomationActionType> = ACTION_TYPES.associateBy { it.id }

    /** The catalog entry for [id], or null for anything unlisted. */
    fun typeFor(id: String): AutomationActionType? = byId[id]

    /**
     * Validate and canonicalize one action spec. Returns null when the type
     * id is not in the catalog or an unknown param name is present — both
     * mean "not an action this build understands", and dropping the action
     * is safer than guessing. Otherwise every present value is parsed,
     * clamped to its bounds, and re-rendered canonically ("true"/"false"
     * for BOOL, plain decimal for INT/FLOAT); absent optional params stay
     * absent (the executor reads absent as "leave unchanged"), absent
     * required params fold to their documented default, and invalid values
     * fold to the default (for a defaultless optional param that means the
     * param folds away entirely).
     */
    fun sanitizeAction(spec: AutomationActionSpec): AutomationActionSpec? {
        val type = typeFor(spec.type) ?: return null
        val known = type.params.associateBy { it.name }
        // An unknown key is a schema violation, not a value problem: the
        // writer believed in a parameter this build has never heard of, so
        // its other values cannot be trusted to mean what they say either.
        if (spec.params.keys.any { it !in known }) return null
        val sanitized = LinkedHashMap<String, String>()
        for (param in type.params) {
            val canonical = AutomationParamValues.canonicalValue(param, spec.params[param.name]) ?: continue
            sanitized[param.name] = canonical
        }
        return AutomationActionSpec(type = spec.type, params = sanitized)
    }

    /**
     * Canonicalize one raw INT value into a whole number (parse leniently,
     * coerce into Int range, round `roundToInt`-style) or null when
     * unparseable/non-finite. Bound-free on purpose — bounds are the
     * sanitize ladder's business ([sanitizeAction]); the rendering
     * convention lives in [AutomationParamValues], shared with the trigger
     * and condition catalogs, and the editor's
     * [com.raulshma.lenscast.automation.AutomationViewModel] companion
     * delegates through here.
     */
    fun canonicalIntValue(raw: String): Int? = AutomationParamValues.canonicalIntValue(raw)

    /**
     * Canonicalize one raw FLOAT value into the canonical wire string —
     * parse, round to one decimal, drop a trailing ".0" — or null when
     * unparseable/non-finite. See [AutomationParamValues.canonicalFloatValue].
     */
    fun canonicalFloatValue(raw: String): String? = AutomationParamValues.canonicalFloatValue(raw)
}
