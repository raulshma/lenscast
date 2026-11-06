package com.raulshma.lenscast.automation.schedule

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
 */
data class AutomationParamSpec(
    val name: String,
    val kind: ParamKind,
    val min: Double?,
    val max: Double?,
    val optional: Boolean,
    val defaultValue: String,
    val autoAtZero: Boolean = false,
)

/** How a parameter's string value is interpreted. */
enum class ParamKind { BOOL, INT, FLOAT }

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
 * One automation action the engine can execute: its id, parameter schema,
 * catch-up class, whether applying it interrupts the live stream, and the
 * published picker decorations ([group] and [icon] — see
 * [AutomationIconVocabulary] for the icon kind's closed meaning).
 *
 * [interruptsStream] is a UI warning flag only — the executor does NOT
 * force a teardown/rebind around the actions it marks. The one flagged type
 * (`camera_lens`) switches through `CameraService.selectLens`, whose
 * `CameraSessionArbiter` rebinds immediately when the camera is free (a
 * brief preview/stream interruption) and defers to the next natural rebind
 * while an exclusive session (a recording) is active.
 */
data class AutomationActionType(
    val id: String,
    val params: List<AutomationParamSpec>,
    val catchUp: CatchUpClass,
    val interruptsStream: Boolean = false,
    /** The picker group this entry lists under — a semantic id, never renamed. */
    val group: String,
    /** The published icon kind (a closed vocabulary; see [AutomationIconVocabulary]). */
    val icon: String,
)

/**
 * The catalog of what an automation rule may do: ids, param schemas (names /
 * kinds / bounds / defaults / autoAtZero) and catch-up classes are
 * single-sourced here. Per-type EXECUTION and pretty labels dispatch at the
 * executor/UI seams by design (exhaustive `when`s over a closed set), so
 * adding an action touches the catalog plus those seams. The Settings
 * Store's codec sanitizes through it, the schedule policy merges camera
 * actions through it, and the receiver/executor resolve ids through it. The
 * catalog test pins the exact id/param-name strings, so a typo here fails a
 * JVM test instead of silently disabling a feature.
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
            interruptsStream = true,
            group = GROUP_CAMERA,
            icon = AutomationIconVocabulary.LENS,
        ),
        AutomationActionType(
            id = "torch",
            params = listOf(
                AutomationParamSpec(
                    name = "enabled", kind = ParamKind.BOOL,
                    min = null, max = null,
                    optional = false, defaultValue = "true",
                ),
                // API 35+ devices only; absent = device default strength.
                AutomationParamSpec(
                    name = "strength", kind = ParamKind.INT,
                    min = 1.0, max = 8.0,
                    optional = true, defaultValue = "",
                ),
            ),
            catchUp = CatchUpClass.STATEFUL,
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
            group = GROUP_STREAM_CAPTURE,
            icon = AutomationIconVocabulary.BROADCAST,
        ),
        AutomationActionType(
            id = "capture_photo",
            params = emptyList(),
            catchUp = CatchUpClass.ONESHOT,
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
            group = GROUP_DETECTION,
            icon = AutomationIconVocabulary.RADAR,
        ),
    )

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
