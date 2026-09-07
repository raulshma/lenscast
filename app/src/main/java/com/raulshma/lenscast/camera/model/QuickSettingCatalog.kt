package com.raulshma.lenscast.camera.model

import java.util.Locale

/**
 * The camera screen's quick-setting controls. The catalog is the single
 * description of every control — pill label, sheet title, editor shape,
 * optional explainer — so the bar, the sheet, and the settings screens all
 * render from one table instead of ten hand-written branches. Pure data:
 * icons are named selectors (the UI maps them to vectors) and every field
 * is a function of [CameraSettings] plus the device's live
 * [QuickSettingRanges], so the label math is JVM-testable.
 */
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
        val optionLabel: (String) -> String = { it.replace("_", " ") },
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
    /** Optional mode-dependent explainer rendered under the sheet editor. */
    val description: ((CameraSettings) -> String?)? = null,
)

object QuickSettingCatalog {

    /** Formatted "%.1f" is pinned to [Locale.US] so labels never shift with device locale. */
    fun zoomLabel(ratio: Float): String = "${String.format(Locale.US, "%.1f", ratio)}x"

    /** The manual white-balance fallback shown when no temperature was set yet. */
    fun colorTemperatureLabel(kelvin: Int?): String = "${kelvin ?: CameraSettings.DEFAULT_COLOR_TEMPERATURE_K}K"

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
        ),
        QuickSettingDescriptor(
            type = QuickSettingType.FRAME_RATE,
            icon = QuickSettingIcon.FRAME_RATE,
            title = "Frame Rate",
            label = { "${it.frameRate}" },
            editor = QuickSettingEditor.Slider(
                range = {
                    CameraSettings.FRAME_RATE_SLIDER_MIN.toFloat()..CameraSettings.FRAME_RATE_SLIDER_MAX.toFloat()
                },
                value = { it.frameRate.toFloat() },
                label = { "${it.frameRate} fps" },
            ),
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
            description = { nightVisionDescription(it.nightVisionMode) },
        ),
    )

    private val byType: Map<QuickSettingType, QuickSettingDescriptor> = entries.associateBy { it.type }

    fun descriptorFor(type: QuickSettingType): QuickSettingDescriptor =
        requireNotNull(byType[type]) { "No quick-setting descriptor for $type" }
}

private fun ClosedRange<Int>.toClosedFloat(): ClosedFloatingPointRange<Float> =
    start.toFloat()..endInclusive.toFloat()
