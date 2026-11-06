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
import com.raulshma.lenscast.automation.schedule.AutomationIconVocabulary
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog

/**
 * The display-only visual identity of the three automation catalogs: a badge
 * icon, a one-line picker description, and the picker grouping per catalog
 * id. The GROUPING and the ICON KINDS are not hand lists here anymore — both
 * are device truth published on the wire (each catalog entry carries its
 * `group` id and its `icon` kind, see AutomationIconVocabulary); this object
 * only translates: group ids → localized titles, icon kinds → drawables,
 * catalog ids → one-line descriptions. A picker group's member list is the
 * catalog's own display order filtered by the group id, so adding a catalog
 * entry with a known group/icon kind renders everywhere with zero edits
 * here. Every accessor falls back to a plain entry for an id (or a kind) it
 * does not know, so a future catalog entry still renders in the pickers
 * (generic look) instead of crashing or hiding.
 */
internal object AutomationVisuals {

    /** One catalog entry's display extras: a badge icon and a one-line description. */
    data class EntryVisual(val icon: ImageVector, @StringRes val descriptionRes: Int)

    /** One picker group: a title plus the catalog ids it lists, in display order. */
    data class PickerGroup(@StringRes val titleRes: Int, val ids: List<String>)

    private val FALLBACK = EntryVisual(Icons.Filled.Adjust, R.string.automation_action_unknown)

    /** The group id → localized title mapping, in display order (the ids and member lists come from the catalogs). */
    private val TRIGGER_GROUP_TITLES: List<Pair<String, Int>> = listOf(
        AutomationTriggerCatalog.GROUP_SCHEDULE to R.string.automation_group_schedule,
        AutomationTriggerCatalog.GROUP_DEVICE_EVENTS to R.string.automation_group_device_events,
        AutomationTriggerCatalog.GROUP_POWER to R.string.automation_group_power,
        AutomationTriggerCatalog.GROUP_STREAM_APP to R.string.automation_group_stream_app,
    )

    private val ACTION_GROUP_TITLES: List<Pair<String, Int>> = listOf(
        AutomationActionCatalog.GROUP_CAMERA to R.string.automation_group_camera,
        AutomationActionCatalog.GROUP_LIGHT_SOUND to R.string.automation_group_light_sound,
        AutomationActionCatalog.GROUP_STREAM_CAPTURE to R.string.automation_group_stream_capture,
        AutomationActionCatalog.GROUP_DETECTION to R.string.automation_group_detection,
    )

    private val CONDITION_GROUP_TITLES: List<Pair<String, Int>> = listOf(
        AutomationConditionCatalog.GROUP_TIME to R.string.automation_group_time,
        AutomationConditionCatalog.GROUP_POWER to R.string.automation_group_power,
        AutomationConditionCatalog.GROUP_CONNECTIVITY to R.string.automation_group_connectivity,
        AutomationConditionCatalog.GROUP_DEVICE_STATE to R.string.automation_group_device_state,
    )

    /**
     * Derive one catalog's picker groups: the titles above, in order, each
     * listing the catalog's own entries in catalog order filtered by the
     * group id. A group with no entries disappears (the catalogs are the
     * single source — an empty group would be a hand-list ghost).
     */
    private fun groups(
        entries: List<Pair<String, String>>, // (id, group) in catalog display order
        titles: List<Pair<String, Int>>,
    ): List<PickerGroup> =
        titles.mapNotNull { (groupId, titleRes) ->
            val ids = entries.filter { it.second == groupId }.map { it.first }
            if (ids.isEmpty()) null else PickerGroup(titleRes, ids)
        }

    // ── triggers ──

    /** Icon kind → drawable/description (AutomationIconVocabulary is the closed kind set). */
    private val TRIGGER_VISUALS: Map<String, EntryVisual> = mapOf(
        AutomationIconVocabulary.SCHEDULE to EntryVisual(
            Icons.Filled.Schedule, R.string.automation_trigger_desc_time_of_day,
        ),
        AutomationIconVocabulary.SENSOR to EntryVisual(
            Icons.Filled.Sensors, R.string.automation_trigger_desc_motion,
        ),
        AutomationIconVocabulary.SOUND to EntryVisual(
            Icons.Filled.GraphicEq, R.string.automation_trigger_desc_sound,
        ),
        AutomationIconVocabulary.SHIELD to EntryVisual(
            Icons.Filled.Security, R.string.automation_trigger_desc_tamper,
        ),
        AutomationIconVocabulary.POWER to EntryVisual(
            Icons.Filled.Power, R.string.automation_trigger_desc_charging,
        ),
        AutomationIconVocabulary.BATTERY_ALERT to EntryVisual(
            Icons.Filled.BatteryAlert, R.string.automation_trigger_desc_battery,
        ),
        AutomationIconVocabulary.CAST to EntryVisual(
            Icons.Filled.Cast, R.string.automation_trigger_desc_streaming,
        ),
        AutomationIconVocabulary.EYE to EntryVisual(
            Icons.Filled.Visibility, R.string.automation_trigger_desc_viewers,
        ),
        AutomationIconVocabulary.ROCKET to EntryVisual(
            Icons.Filled.RocketLaunch, R.string.automation_trigger_desc_app_started,
        ),
    )

    fun triggerVisual(typeId: String): EntryVisual =
        AutomationTriggerCatalog.typeFor(typeId)?.icon?.let { TRIGGER_VISUALS[it] } ?: FALLBACK

    val TRIGGER_GROUPS: List<PickerGroup> = groups(
        AutomationTriggerCatalog.ACTION_TYPES.map { it.id to it.group },
        TRIGGER_GROUP_TITLES,
    )

    // ── actions ──

    private val ACTION_VISUALS: Map<String, EntryVisual> = mapOf(
        AutomationIconVocabulary.EXPOSURE to EntryVisual(Icons.Filled.Exposure, R.string.automation_action_desc_camera_exposure),
        AutomationIconVocabulary.TONALITY to EntryVisual(Icons.Filled.Tonality, R.string.automation_action_desc_camera_ev),
        AutomationIconVocabulary.ZOOM to EntryVisual(Icons.Filled.ZoomIn, R.string.automation_action_desc_camera_zoom),
        AutomationIconVocabulary.SPEED to EntryVisual(Icons.Filled.Speed, R.string.automation_action_desc_camera_fps),
        AutomationIconVocabulary.LENS to EntryVisual(Icons.Filled.Lens, R.string.automation_action_desc_camera_lens),
        AutomationIconVocabulary.TORCH to EntryVisual(Icons.Filled.FlashlightOn, R.string.automation_action_desc_torch),
        AutomationIconVocabulary.BROADCAST to EntryVisual(Icons.Filled.WifiTethering, R.string.automation_action_desc_stream),
        AutomationIconVocabulary.CAMERA to EntryVisual(Icons.Filled.PhotoCamera, R.string.automation_action_desc_capture_photo),
        AutomationIconVocabulary.VIDEOCAM to EntryVisual(Icons.Filled.Videocam, R.string.automation_action_desc_recording),
        AutomationIconVocabulary.SIREN to EntryVisual(Icons.Filled.NotificationsActive, R.string.automation_action_desc_siren),
        AutomationIconVocabulary.RADAR to EntryVisual(Icons.Filled.Radar, R.string.automation_action_desc_detection),
    )

    fun actionVisual(typeId: String): EntryVisual =
        AutomationActionCatalog.typeFor(typeId)?.icon?.let { ACTION_VISUALS[it] } ?: FALLBACK

    val ACTION_GROUPS: List<PickerGroup> = groups(
        AutomationActionCatalog.ACTION_TYPES.map { it.id to it.group },
        ACTION_GROUP_TITLES,
    )

    // ── conditions ──

    private val CONDITION_VISUALS: Map<String, EntryVisual> = mapOf(
        AutomationIconVocabulary.TIME_WINDOW to EntryVisual(
            Icons.Filled.AccessTime, R.string.automation_condition_desc_time_window,
        ),
        AutomationIconVocabulary.POWER to EntryVisual(
            Icons.Filled.Power, R.string.automation_condition_desc_charging,
        ),
        AutomationIconVocabulary.BATTERY to EntryVisual(
            Icons.Filled.BatteryStd, R.string.automation_condition_desc_battery,
        ),
        AutomationIconVocabulary.BATTERY_SAVER to EntryVisual(
            Icons.Filled.BatterySaver, R.string.automation_condition_desc_power_save,
        ),
        AutomationIconVocabulary.WIFI to EntryVisual(
            Icons.Filled.Wifi, R.string.automation_condition_desc_wifi,
        ),
        AutomationIconVocabulary.STREAM to EntryVisual(
            Icons.Filled.Stream, R.string.automation_condition_desc_streaming,
        ),
        AutomationIconVocabulary.PEOPLE to EntryVisual(
            Icons.Filled.Groups, R.string.automation_condition_desc_viewers,
        ),
        AutomationIconVocabulary.LIGHTBULB to EntryVisual(
            Icons.Filled.Lightbulb, R.string.automation_condition_desc_torch,
        ),
        AutomationIconVocabulary.RECORD to EntryVisual(
            Icons.Filled.FiberManualRecord, R.string.automation_condition_desc_recording,
        ),
        AutomationIconVocabulary.THERMOSTAT to EntryVisual(
            Icons.Filled.DeviceThermostat, R.string.automation_condition_desc_thermal,
        ),
        AutomationIconVocabulary.RADAR to EntryVisual(
            Icons.Filled.Radar, R.string.automation_condition_desc_motion_detection,
        ),
    )

    fun conditionVisual(typeId: String): EntryVisual =
        AutomationConditionCatalog.typeFor(typeId)?.icon?.let { CONDITION_VISUALS[it] } ?: FALLBACK

    val CONDITION_GROUPS: List<PickerGroup> = groups(
        AutomationConditionCatalog.CONDITIONS.map { it.id to it.group },
        CONDITION_GROUP_TITLES,
    )
}
