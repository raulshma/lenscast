package com.raulshma.lenscast.camera.model

import com.raulshma.lenscast.camera.CameraSettingsEditor
import java.util.Locale

/**
 * The camera screen's quick-setting controls. The catalog is the single
 * description of every control — pill label, sheet title, editor shape,
 * write transform, optional explainer — so the bar, the sheet, and the
 * settings screens all render from (and write through) one table instead of
 * ten hand-written branches. Pure data: icons are named selectors (the UI
 * maps them to vectors) and every field is a function of [CameraSettings]
 * plus the device's live [QuickSettingRanges], so the label math and the
 * write transforms are JVM-testable.
 */

/**
 * The typed value a quick-setting editor produces — one variant per editor
 * shape. [QuickSettingDescriptor.write] consumes it; the raw callback value
 * from the UI is converted once in [QuickSettingCatalog.editorValueFor] and
 * never reaches a write untyped.
 */
sealed class QuickSettingEditorValue {
    data class Toggle(val checked: Boolean) : QuickSettingEditorValue()
    data class Chips(val option: String) : QuickSettingEditorValue()
    data class Slider(val value: Float) : QuickSettingEditorValue()
}
enum class QuickSettingType {
    EXPOSURE, ISO, WHITE_BALANCE, FOCUS, ZOOM, HDR, RESOLUTION, FRAME_RATE, STABILIZATION, NIGHT_VISION
}

/** Icon selector per control; mapped to material vectors at the UI seam. */
enum class QuickSettingIcon {
    EXPOSURE, ISO, WHITE_BALANCE, FOCUS, ZOOM, HDR, RESOLUTION, FRAME_RATE, STABILIZATION, NIGHT_VISION
}

/** The device's live ranges, as far as the quick-setting editors need them. */
data class QuickSettingRanges(
    val iso: ClosedRange<Int>,
    val zoom: ClosedFloatingPointRange<Float>,
    val exposure: ClosedRange<Int>,
)

/** How the sheet edits a control: a switch, a chip row, or a slider. */
sealed class QuickSettingEditor {
    data class Toggle(
        val title: String,
        val checked: (CameraSettings) -> Boolean,
    ) : QuickSettingEditor()

    data class Chips(
        val options: (QuickSettingRanges) -> List<String>,
        val selected: (CameraSettings) -> String,
        val optionLabel: (String) -> String = ::chipLabel,
    ) : QuickSettingEditor()

    data class Slider(
        val range: (QuickSettingRanges) -> ClosedFloatingPointRange<Float>,
        val value: (CameraSettings) -> Float,
        val label: (CameraSettings) -> String,
    ) : QuickSettingEditor()
}

data class QuickSettingDescriptor(
    val type: QuickSettingType,
    val icon: QuickSettingIcon,
    val title: String,
    /** The pill's compact label. */
    val label: (CameraSettings) -> String,
    val editor: QuickSettingEditor,
    /**
     * The pure settings transform for this control's editor value — the one
     * write table. A value whose shape doesn't match [editor] is a
     * programming error and fails fast, like the unchecked dispatch it
     * replaced. No clamping here: writes persist exactly what the caller
     * sent (the device's live ranges win at apply time).
     */
    val write: (CameraSettings, QuickSettingEditorValue) -> CameraSettings,
    /** Optional mode-dependent explainer rendered under the sheet editor. */
    val description: ((CameraSettings) -> String?)? = null,
)

/**
 * Shape-guarded write builders: the single place that unpacks a
 * [QuickSettingEditorValue] onto the typed transform each editor shape
 * needs. A mismatched shape fails fast — it can only be a programming error.
 */
private fun toggleWrite(
    transform: (CameraSettings, Boolean) -> CameraSettings,
): (CameraSettings, QuickSettingEditorValue) -> CameraSettings =
    { settings, value -> transform(settings, (value as QuickSettingEditorValue.Toggle).checked) }

private fun chipsWrite(
    transform: (CameraSettings, String) -> CameraSettings,
): (CameraSettings, QuickSettingEditorValue) -> CameraSettings =
    { settings, value -> transform(settings, (value as QuickSettingEditorValue.Chips).option) }

private fun sliderWrite(
    transform: (CameraSettings, Float) -> CameraSettings,
): (CameraSettings, QuickSettingEditorValue) -> CameraSettings =
    { settings, value -> transform(settings, (value as QuickSettingEditorValue.Slider).value) }

/**
 * The chip label for a raw option name — the one underscore→space rule,
 * the [QuickSettingEditor.Chips] default optionLabel and the settings
 * screen's chip renderer both label through.
 */
fun chipLabel(option: String): String = option.replace("_", " ")

object QuickSettingCatalog {

    /** Formatted "%.1f" is pinned to [Locale.US] so labels never shift with device locale. */
    fun zoomLabel(ratio: Float): String = "${String.format(Locale.US, "%.1f", ratio)}x"

    /** The manual white-balance fallback shown when no temperature was set yet. */
    fun colorTemperatureLabel(kelvin: Int?): String = "${kelvin ?: CameraSettings.DEFAULT_COLOR_TEMPERATURE_K}K"

    // ── Settings-screen editor ranges ──
    // Built FROM the CameraSettings companion bounds, which stay the one
    // home; the sliders can only follow, so the UI always offers the full
    // persisted range and can never drift from it.

    /** The focus-distance slider span: 0 up to the persistence ceiling. */
    fun focusDistanceRange(): ClosedFloatingPointRange<Float> = 0f..CameraSettings.FOCUS_DISTANCE_MAX

    /** The manual color-temperature slider span, exactly the persistence clamp. */
    fun colorTemperatureRange(): ClosedFloatingPointRange<Float> =
        CameraSettings.COLOR_TEMPERATURE_MIN.toFloat()..CameraSettings.COLOR_TEMPERATURE_MAX.toFloat()

    /** The frame-rate slider span shared by the quick-setting sheet and the settings screen. */
    fun frameRateRange(): ClosedFloatingPointRange<Float> =
        CameraSettings.FRAME_RATE_SLIDER_MIN.toFloat()..CameraSettings.FRAME_RATE_SLIDER_MAX.toFloat()

    /**
     * The scene-mode option names offered in the settings screen; "OFF"
     * clears the override (see [CameraSettingsEditor.parseSceneMode]).
     */
    val sceneModeOptions: List<String> =
        listOf("OFF", "FACE_DETECTION", "NIGHT", "HDR", "SUNSET", "FIREWORKS")

    /**
     * Converts the raw editor callback value (Boolean | String | Float)
     * onto the typed [QuickSettingEditorValue], per the descriptor's editor
     * shape. Null when the raw value doesn't match the shape — the UI
     * contract guarantees a match; null is the loud-enough no-op.
     */
    fun editorValueFor(type: QuickSettingType, raw: Any): QuickSettingEditorValue? =
        when (descriptorFor(type).editor) {
            is QuickSettingEditor.Toggle -> (raw as? Boolean)?.let(QuickSettingEditorValue::Toggle)
            is QuickSettingEditor.Chips -> (raw as? String)?.let(QuickSettingEditorValue::Chips)
            is QuickSettingEditor.Slider -> (raw as? Number)?.let { QuickSettingEditorValue.Slider(it.toFloat()) }
        }

    fun nightVisionOptionLabel(name: String): String = when (name) {
        NightVisionMode.ON.name -> "IR On"
        NightVisionMode.AUTO.name -> "Auto"
        else -> "Off"
    }

    fun nightVisionDescription(mode: NightVisionMode): String = when (mode) {
        NightVisionMode.ON -> "Forces night scene mode with maximum exposure and reduced frame rate for best low-light performance."
        NightVisionMode.AUTO -> "Automatically adapts to lighting conditions using night portrait mode with auto flash."
        NightVisionMode.OFF -> "Standard camera behavior without low-light enhancements."
    }

    val entries: List<QuickSettingDescriptor> = listOf(
        QuickSettingDescriptor(
            type = QuickSettingType.EXPOSURE,
            icon = QuickSettingIcon.EXPOSURE,
            title = "Exposure Compensation",
            label = { "${it.exposureCompensation}" },
            editor = QuickSettingEditor.Slider(
                range = { it.exposure.toClosedFloat() },
                value = { it.exposureCompensation.toFloat() },
                label = { "${it.exposureCompensation}" },
            ),
            write = sliderWrite { settings, value ->
                settings.copy(exposureCompensation = value.toInt())
            },
        ),
        QuickSettingDescriptor(
            type = QuickSettingType.ISO,
            icon = QuickSettingIcon.ISO,
            title = "ISO",
            label = { it.iso?.toString() ?: "A" },
            editor = QuickSettingEditor.Chips(
                options = { ranges -> isoStops(ranges.iso) },
                selected = { it.iso?.toString() ?: "Auto" },
            ),
            write = chipsWrite { settings, option ->
                settings.copy(iso = CameraSettingsEditor.parseIso(option))
            },
        ),
        QuickSettingDescriptor(
            type = QuickSettingType.WHITE_BALANCE,
            icon = QuickSettingIcon.WHITE_BALANCE,
            title = "White Balance",
            label = {
                when (it.whiteBalance) {
                    WhiteBalance.AUTO -> "AWB"
                    WhiteBalance.MANUAL -> colorTemperatureLabel(it.colorTemperature)
                    else -> it.whiteBalance.name.take(3)
                }
            },
            editor = QuickSettingEditor.Chips(
                options = { WhiteBalance.entries.map { mode -> mode.name } },
                selected = { it.whiteBalance.name },
            ),
            write = chipsWrite { settings, option ->
                settings.copy(whiteBalance = WhiteBalance.valueOf(option))
            },
        ),
        QuickSettingDescriptor(
            type = QuickSettingType.FOCUS,
            icon = QuickSettingIcon.FOCUS,
            title = "Focus Mode",
            label = { it.focusMode.name.take(3) },
            editor = QuickSettingEditor.Chips(
                options = { FocusMode.entries.map { mode -> mode.name } },
                selected = { it.focusMode.name },
            ),
            write = chipsWrite { settings, option ->
                settings.copy(focusMode = FocusMode.valueOf(option))
            },
        ),
        QuickSettingDescriptor(
            type = QuickSettingType.ZOOM,
            icon = QuickSettingIcon.ZOOM,
            title = "Zoom",
            label = { zoomLabel(it.zoomRatio) },
            editor = QuickSettingEditor.Slider(
                range = { it.zoom },
                value = { it.zoomRatio },
                label = { zoomLabel(it.zoomRatio) },
            ),
            write = sliderWrite { settings, value ->
                settings.copy(zoomRatio = value)
            },
        ),
        QuickSettingDescriptor(
            type = QuickSettingType.HDR,
            icon = QuickSettingIcon.HDR,
            title = "HDR",
            label = { it.hdrMode.name },
            editor = QuickSettingEditor.Chips(
                options = { HdrMode.entries.map { mode -> mode.name } },
                selected = { it.hdrMode.name },
            ),
            write = chipsWrite { settings, option ->
                settings.copy(hdrMode = HdrMode.valueOf(option))
            },
        ),
        QuickSettingDescriptor(
            type = QuickSettingType.RESOLUTION,
            icon = QuickSettingIcon.RESOLUTION,
            title = "Resolution",
            label = { it.resolution.name.replace("_", " ").take(5) },
            editor = QuickSettingEditor.Chips(
                options = { Resolution.entries.map { mode -> mode.name } },
                selected = { it.resolution.name },
            ),
            write = chipsWrite { settings, option ->
                settings.copy(resolution = Resolution.valueOf(option))
            },
        ),
        QuickSettingDescriptor(
            type = QuickSettingType.FRAME_RATE,
            icon = QuickSettingIcon.FRAME_RATE,
            title = "Frame Rate",
            label = { "${it.frameRate}" },
            editor = QuickSettingEditor.Slider(
                range = { _ -> frameRateRange() },
                value = { it.frameRate.toFloat() },
                label = { "${it.frameRate} fps" },
            ),
            write = sliderWrite { settings, value ->
                settings.copy(frameRate = value.toInt())
            },
        ),
        QuickSettingDescriptor(
            type = QuickSettingType.STABILIZATION,
            icon = QuickSettingIcon.STABILIZATION,
            title = "Stabilization",
            label = { if (it.stabilization) "OIS" else "OFF" },
            editor = QuickSettingEditor.Toggle(
                title = "Image Stabilization",
                checked = { it.stabilization },
            ),
            write = toggleWrite { settings, checked ->
                settings.copy(stabilization = checked)
            },
        ),
        QuickSettingDescriptor(
            type = QuickSettingType.NIGHT_VISION,
            icon = QuickSettingIcon.NIGHT_VISION,
            title = "Night Vision / IR",
            label = {
                when (it.nightVisionMode) {
                    NightVisionMode.ON -> "IR"
                    NightVisionMode.AUTO -> "AUTO"
                    NightVisionMode.OFF -> "OFF"
                }
            },
            editor = QuickSettingEditor.Chips(
                options = { NightVisionMode.entries.map { mode -> mode.name } },
                selected = { it.nightVisionMode.name },
                optionLabel = ::nightVisionOptionLabel,
            ),
            write = chipsWrite { settings, option ->
                settings.copy(nightVisionMode = NightVisionMode.valueOf(option))
            },
            description = { nightVisionDescription(it.nightVisionMode) },
        ),
    )

    private val byType: Map<QuickSettingType, QuickSettingDescriptor> = entries.associateBy { it.type }

    fun descriptorFor(type: QuickSettingType): QuickSettingDescriptor =
        requireNotNull(byType[type]) { "No quick-setting descriptor for $type" }
}

private fun ClosedRange<Int>.toClosedFloat(): ClosedFloatingPointRange<Float> =
    start.toFloat()..endInclusive.toFloat()
