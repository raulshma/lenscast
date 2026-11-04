package com.raulshma.lenscast.automation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector
import com.raulshma.lenscast.R
import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationConditionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog

/**
 * The display-only visual identity of the three automation catalogs: a badge
 * icon, a one-line picker description, and the picker grouping per catalog
 * id. The engine catalogs stay untouched (they are device truth on the wire);
 * this object only decorates them, and every accessor falls back to a plain
 * entry for an id it does not know, so a future catalog id renders in the
 * pickers (generic look) instead of crashing or hiding.
 */
internal object AutomationVisuals {

    /** One catalog entry's display extras: a badge icon and a one-line description. */
    data class EntryVisual(val icon: ImageVector, @StringRes val descriptionRes: Int)

    /** One picker group: a title plus the catalog ids it lists, in display order. */
    data class PickerGroup(@StringRes val titleRes: Int, val ids: List<String>)

    private val FALLBACK = EntryVisual(Icons.Filled.Adjust, R.string.automation_action_unknown)

    // ── triggers ──

    private val TRIGGER_VISUALS: Map<String, EntryVisual> = mapOf(
        AutomationTriggerCatalog.TIME_OF_DAY to EntryVisual(
            Icons.Filled.Schedule, R.string.automation_trigger_desc_time_of_day,
        ),
        AutomationTriggerCatalog.MOTION_DETECTED to EntryVisual(
            Icons.Filled.Sensors, R.string.automation_trigger_desc_motion,
        ),
        AutomationTriggerCatalog.SOUND_DETECTED to EntryVisual(
            Icons.Filled.GraphicEq, R.string.automation_trigger_desc_sound,
        ),
        AutomationTriggerCatalog.TAMPER_DETECTED to EntryVisual(
            Icons.Filled.Security, R.string.automation_trigger_desc_tamper,
        ),
        AutomationTriggerCatalog.CHARGING_CHANGED to EntryVisual(
            Icons.Filled.Power, R.string.automation_trigger_desc_charging,
        ),
        AutomationTriggerCatalog.BATTERY_LEVEL to EntryVisual(
            Icons.Filled.BatteryAlert, R.string.automation_trigger_desc_battery,
        ),
        AutomationTriggerCatalog.STREAMING_CHANGED to EntryVisual(
            Icons.Filled.Cast, R.string.automation_trigger_desc_streaming,
        ),
        AutomationTriggerCatalog.VIEWERS_CHANGED to EntryVisual(
            Icons.Filled.Visibility, R.string.automation_trigger_desc_viewers,
        ),
        AutomationTriggerCatalog.APP_STARTED to EntryVisual(
            Icons.Filled.RocketLaunch, R.string.automation_trigger_desc_app_started,
        ),
    )

    fun triggerVisual(typeId: String): EntryVisual =
        TRIGGER_VISUALS[typeId] ?: FALLBACK

    val TRIGGER_GROUPS: List<PickerGroup> = listOf(
        PickerGroup(R.string.automation_group_schedule, listOf(AutomationTriggerCatalog.TIME_OF_DAY)),
        PickerGroup(
            R.string.automation_group_device_events,
            listOf(
                AutomationTriggerCatalog.MOTION_DETECTED,
                AutomationTriggerCatalog.SOUND_DETECTED,
                AutomationTriggerCatalog.TAMPER_DETECTED,
            ),
        ),
        PickerGroup(
            R.string.automation_group_power,
            listOf(AutomationTriggerCatalog.CHARGING_CHANGED, AutomationTriggerCatalog.BATTERY_LEVEL),
        ),
        PickerGroup(
            R.string.automation_group_stream_app,
            listOf(
                AutomationTriggerCatalog.STREAMING_CHANGED,
                AutomationTriggerCatalog.VIEWERS_CHANGED,
                AutomationTriggerCatalog.APP_STARTED,
            ),
        ),
    )

    // ── actions ──

    private val ACTION_VISUALS: Map<String, EntryVisual> = mapOf(
        "camera_exposure" to EntryVisual(Icons.Filled.Exposure, R.string.automation_action_desc_camera_exposure),
        "camera_ev" to EntryVisual(Icons.Filled.Tonality, R.string.automation_action_desc_camera_ev),
        "camera_zoom" to EntryVisual(Icons.Filled.ZoomIn, R.string.automation_action_desc_camera_zoom),
        "camera_fps" to EntryVisual(Icons.Filled.Speed, R.string.automation_action_desc_camera_fps),
        "camera_lens" to EntryVisual(Icons.Filled.Lens, R.string.automation_action_desc_camera_lens),
        "torch" to EntryVisual(Icons.Filled.FlashlightOn, R.string.automation_action_desc_torch),
        "stream" to EntryVisual(Icons.Filled.WifiTethering, R.string.automation_action_desc_stream),
        "capture_photo" to EntryVisual(Icons.Filled.PhotoCamera, R.string.automation_action_desc_capture_photo),
        "recording" to EntryVisual(Icons.Filled.Videocam, R.string.automation_action_desc_recording),
        "siren" to EntryVisual(Icons.Filled.NotificationsActive, R.string.automation_action_desc_siren),
        "detection" to EntryVisual(Icons.Filled.Radar, R.string.automation_action_desc_detection),
    )

    fun actionVisual(typeId: String): EntryVisual =
        ACTION_VISUALS[typeId] ?: FALLBACK

    val ACTION_GROUPS: List<PickerGroup> = listOf(
        PickerGroup(
            R.string.automation_group_camera,
            listOf("camera_exposure", "camera_ev", "camera_zoom", "camera_fps", "camera_lens"),
        ),
        PickerGroup(R.string.automation_group_light_sound, listOf("torch", "siren")),
        PickerGroup(R.string.automation_group_stream_capture, listOf("stream", "capture_photo", "recording")),
        PickerGroup(R.string.automation_group_detection, listOf("detection")),
    )

    // ── conditions ──

    private val CONDITION_VISUALS: Map<String, EntryVisual> = mapOf(
        AutomationConditionCatalog.TIME_WINDOW to EntryVisual(
            Icons.Filled.AccessTime, R.string.automation_condition_desc_time_window,
        ),
        AutomationConditionCatalog.CHARGING to EntryVisual(
            Icons.Filled.Power, R.string.automation_condition_desc_charging,
        ),
        AutomationConditionCatalog.BATTERY_LEVEL to EntryVisual(
            Icons.Filled.BatteryStd, R.string.automation_condition_desc_battery,
        ),
        AutomationConditionCatalog.POWER_SAVE to EntryVisual(
            Icons.Filled.BatterySaver, R.string.automation_condition_desc_power_save,
        ),
        AutomationConditionCatalog.WIFI_CONNECTED to EntryVisual(
            Icons.Filled.Wifi, R.string.automation_condition_desc_wifi,
        ),
        AutomationConditionCatalog.STREAMING to EntryVisual(
            Icons.Filled.Stream, R.string.automation_condition_desc_streaming,
        ),
        AutomationConditionCatalog.VIEWERS to EntryVisual(
            Icons.Filled.Groups, R.string.automation_condition_desc_viewers,
        ),
        AutomationConditionCatalog.TORCH to EntryVisual(
            Icons.Filled.Lightbulb, R.string.automation_condition_desc_torch,
        ),
        AutomationConditionCatalog.RECORDING to EntryVisual(
            Icons.Filled.FiberManualRecord, R.string.automation_condition_desc_recording,
        ),
        AutomationConditionCatalog.THERMAL to EntryVisual(
            Icons.Filled.DeviceThermostat, R.string.automation_condition_desc_thermal,
        ),
        AutomationConditionCatalog.MOTION_DETECTION to EntryVisual(
            Icons.Filled.Radar, R.string.automation_condition_desc_motion_detection,
        ),
    )

    fun conditionVisual(typeId: String): EntryVisual =
        CONDITION_VISUALS[typeId] ?: FALLBACK

    val CONDITION_GROUPS: List<PickerGroup> = listOf(
        PickerGroup(R.string.automation_group_time, listOf(AutomationConditionCatalog.TIME_WINDOW)),
        PickerGroup(
            R.string.automation_group_power,
            listOf(AutomationConditionCatalog.CHARGING, AutomationConditionCatalog.BATTERY_LEVEL, AutomationConditionCatalog.POWER_SAVE),
        ),
        PickerGroup(
            R.string.automation_group_connectivity,
            listOf(AutomationConditionCatalog.WIFI_CONNECTED, AutomationConditionCatalog.STREAMING, AutomationConditionCatalog.VIEWERS),
        ),
        PickerGroup(
            R.string.automation_group_device_state,
            listOf(
                AutomationConditionCatalog.TORCH,
                AutomationConditionCatalog.RECORDING,
                AutomationConditionCatalog.THERMAL,
                AutomationConditionCatalog.MOTION_DETECTION,
            ),
        ),
    )
}
