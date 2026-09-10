package com.raulshma.lenscast.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import com.raulshma.lenscast.capture.MotionArmingPolicy
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.core.StreamDefaults

/**
 * The detection (motion + sound), watchdog, and backup settings sections.
 * Like every settings surface: the screen only writes the store — the
 * Settings Applier applies values to the runtime detectors.
 */
@Composable
fun DetectionSettingsSection(viewModel: SettingsViewModel, onOpenEventLog: (() -> Unit)? = null) {
    val motionDetectionEnabled by viewModel.motionDetectionEnabled.collectAsState()
    val motionSensitivity by viewModel.motionSensitivityPercent.collectAsState()
    val motionRecordingEnabled by viewModel.motionRecordingEnabled.collectAsState()
    val motionPostRoll by viewModel.motionPostRollSeconds.collectAsState()
    val armScheduleEnabled by viewModel.motionArmScheduleEnabled.collectAsState()
    val armStartMinute by viewModel.motionArmStartMinute.collectAsState()
    val armEndMinute by viewModel.motionArmEndMinute.collectAsState()
    val armDaysMask by viewModel.motionArmDaysMask.collectAsState()
    val soundEnabled by viewModel.soundDetectionEnabled.collectAsState()
    val soundThreshold by viewModel.soundThresholdPercent.collectAsState()
    val soundAdaptiveFloor by viewModel.soundAdaptiveNoiseFloor.collectAsState()
    val soundRecordingEnabled by viewModel.soundRecordingEnabled.collectAsState()
    val motionCooldown by viewModel.motionCooldownSeconds.collectAsState()
    val soundCooldown by viewModel.soundCooldownSeconds.collectAsState()
    val mlEnabled by viewModel.mlDetectionEnabled.collectAsState()
    val mlMinScore by viewModel.mlMinScorePercent.collectAsState()
    val mlPerson by viewModel.mlIncludePerson.collectAsState()
    val mlPets by viewModel.mlIncludePets.collectAsState()
    val mlVehicles by viewModel.mlIncludeVehicles.collectAsState()
    val modelState by viewModel.detectionModelState.collectAsState()
    val notificationEnabled by viewModel.detectionNotificationsEnabled.collectAsState()
    val quietHoursEnabled by viewModel.alertQuietHoursEnabled.collectAsState()
    val quietHoursStart by viewModel.alertQuietHoursStartMinute.collectAsState()
    val quietHoursEnd by viewModel.alertQuietHoursEndMinute.collectAsState()
    val tamperEnabled by viewModel.tamperDetectionEnabled.collectAsState()

    SettingsSection(title = "Detection & Alerts") {
        // Persisted toggles: the screen writes the store, the Settings
        // Applier applies them to the runtime detectors.
        SwitchSetting(
            title = "Motion Detection",
            checked = motionDetectionEnabled,
            onCheckedChange = { viewModel.updateMotionDetectionEnabled(it) }
        )
        if (onOpenEventLog != null) {
            TextButton(onClick = onOpenEventLog) {
                Text("Open Event Log")
            }
        }
        if (motionDetectionEnabled) {
            SliderSetting(
                title = "Motion Sensitivity (%)",
                value = motionSensitivity.toFloat(),
                range = StreamDefaultsRange.MOTION_SENSITIVITY,
                onValueChange = { viewModel.updateMotionSensitivity(it.toInt()) }
            )
            SliderSetting(
                title = "Event Cooldown (seconds)",
                value = motionCooldown.toFloat(),
                range = StreamDefaultsRange.MOTION_COOLDOWN,
                onValueChange = { viewModel.updateMotionCooldownSeconds(it.toInt()) }
            )
            SwitchSetting(
                title = "Record on Motion",
                checked = motionRecordingEnabled,
                onCheckedChange = { viewModel.updateMotionRecordingEnabled(it) }
            )
            if (motionRecordingEnabled) {
                SliderSetting(
                    title = "Post-roll (seconds)",
                    value = motionPostRoll.toFloat(),
                    range = StreamDefaultsRange.MOTION_POST_ROLL,
                    onValueChange = { viewModel.updateMotionPostRollSeconds(it.toInt()) }
                )
            }
            SwitchSetting(
                title = "Arm on Schedule",
                checked = armScheduleEnabled,
                onCheckedChange = { viewModel.updateMotionArmScheduleEnabled(it) }
            )
            if (armScheduleEnabled) {
                SliderSetting(
                    title = "Arm From (minute of day)",
                    value = armStartMinute.toFloat(),
                    range = StreamDefaultsRange.MINUTE_OF_DAY,
                    steps = 95,
                    onValueChange = { viewModel.updateMotionArmStartMinute(it.toInt()) }
                )
                SliderSetting(
                    title = "Arm Until (minute of day)",
                    value = armEndMinute.toFloat(),
                    range = StreamDefaultsRange.MINUTE_OF_DAY,
                    steps = 95,
                    onValueChange = { viewModel.updateMotionArmEndMinute(it.toInt()) }
                )
                ArmDayChips(
                    daysMask = armDaysMask,
                    onToggleDay = { isoDay ->
                        viewModel.updateMotionArmDaysMask(
                            MotionArmingPolicy.toggleDay(armDaysMask, isoDay),
                        )
                    }
                )
            }
        }
        SwitchSetting(
            title = "Sound Detection",
            checked = soundEnabled,
            onCheckedChange = { viewModel.updateSoundDetectionEnabled(it) }
        )
        if (soundEnabled) {
            SliderSetting(
                title = "Sound Threshold (%)",
                value = soundThreshold.toFloat(),
                range = StreamDefaultsRange.SOUND_THRESHOLD,
                onValueChange = { viewModel.updateSoundThresholdPercent(it.toInt()) }
            )
            SwitchSetting(
                title = "Adaptive Noise Floor",
                checked = soundAdaptiveFloor,
                onCheckedChange = { viewModel.updateSoundAdaptiveNoiseFloor(it) }
            )
            if (soundAdaptiveFloor) {
                Text(
                    text = "The trigger rides above a tracked ambient level, so a constant " +
                        "background (HVAC, traffic) neither masks real events nor trips alone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SliderSetting(
                title = "Event Cooldown (seconds)",
                value = soundCooldown.toFloat(),
                range = StreamDefaultsRange.SOUND_COOLDOWN,
                onValueChange = { viewModel.updateSoundCooldownSeconds(it.toInt()) }
            )
            SwitchSetting(
                title = "Record on Sound",
                checked = soundRecordingEnabled,
                onCheckedChange = { viewModel.updateSoundRecordingEnabled(it) }
            )
            if (soundRecordingEnabled) {
                Text(
                    text = "Sound events start a bounded clip using the motion post-roll duration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SwitchSetting(
            title = "Local Alerts on Detection",
            checked = notificationEnabled,
            onCheckedChange = { viewModel.updateDetectionNotificationsEnabled(it) }
        )
        if (notificationEnabled) {
            SwitchSetting(
                title = "Quiet Hours",
                checked = quietHoursEnabled,
                onCheckedChange = { viewModel.updateAlertQuietHoursEnabled(it) }
            )
            if (quietHoursEnabled) {
                Text(
                    text = "Local notifications are held inside the window — webhooks, MQTT, " +
                        "recordings, and the event log keep firing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SliderSetting(
                    title = "Quiet From (minute of day)",
                    value = quietHoursStart.toFloat(),
                    range = StreamDefaultsRange.MINUTE_OF_DAY,
                    steps = 95,
                    onValueChange = { viewModel.updateAlertQuietHoursStartMinute(it.toInt()) }
                )
                SliderSetting(
                    title = "Quiet Until (minute of day)",
                    value = quietHoursEnd.toFloat(),
                    range = StreamDefaultsRange.MINUTE_OF_DAY,
                    steps = 95,
                    onValueChange = { viewModel.updateAlertQuietHoursEndMinute(it.toInt()) }
                )
            }
        }
        SwitchSetting(
            title = "Tamper Detection (power cut)",
            checked = tamperEnabled,
            onCheckedChange = { viewModel.updateTamperDetectionEnabled(it) }
        )
        SwitchSetting(
            title = "Object Detection (ML)",
            checked = mlEnabled,
            onCheckedChange = { viewModel.updateMlDetectionEnabled(it) }
        )
        if (mlEnabled) {
            // Applies on top of motion detection: motion still arms the event;
            // the on-device model decides whether it carries an allowed class.
            Text(
                text = "Applies on top of motion detection — alerts fire only when " +
                    "a person, pet, or vehicle is detected in the frame",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SliderSetting(
                title = "Minimum Confidence (%)",
                value = mlMinScore.toFloat(),
                range = StreamDefaultsRange.ML_SCORE_PERCENT,
                steps = StreamDefaultsRange.ML_SCORE_STEPS,
                onValueChange = { viewModel.updateMlMinScorePercent(it.toInt()) }
            )
            SwitchSetting(
                title = "Alert on People",
                checked = mlPerson,
                onCheckedChange = { viewModel.updateMlIncludePerson(it) }
            )
            SwitchSetting(
                title = "Alert on Animals",
                checked = mlPets,
                onCheckedChange = { viewModel.updateMlIncludePets(it) }
            )
            SwitchSetting(
                title = "Alert on Vehicles",
                checked = mlVehicles,
                onCheckedChange = { viewModel.updateMlIncludeVehicles(it) }
            )
            // The model ships outside the APK — this row is its only
            // user-facing fetch control (the detection gate also auto-requests
            // the download on the first gated motion event).
            DetectionModelRow(
                state = modelState,
                onDownload = { viewModel.downloadDetectionModel() },
            )
        }
    }
}

/**
 * The arm schedule's day-of-week chips: one chip per ISO day (Monday first),
 * selected when its bit is set in the persisted mask. Toggling reports the
 * ISO day index; the caller folds it into the mask.
 */
@Composable
private fun ArmDayChips(daysMask: Int, onToggleDay: (isoDayIndex: Int) -> Unit) {
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Arm on Days",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            labels.forEachIndexed { index, label ->
                FilterChip(
                    label = label,
                    selected = (daysMask and MotionArmingPolicy.dayBit(index)) != 0,
                    onClick = { onToggleDay(index) }
                )
            }
        }
    }
}

/**
 * The on-demand detection model's status row: download when missing, progress
 * while fetching, the reason + retry after a failure. Pure echo of
 * [DetectionModelStore.State] — the store stays the single owner of the file.
 */
@Composable
private fun DetectionModelRow(state: DetectionModelStore.State, onDownload: () -> Unit) {
    Spacer(modifier = Modifier.height(4.dp))
    when (state) {
        is DetectionModelStore.State.NotDownloaded -> {
            Text(
                text = "Detection model not downloaded (${DetectionModelStore.DISPLAY_SIZE_MB}, " +
                    "fetched once and kept in app storage)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onDownload) {
                Text("Download model")
            }
        }
        is DetectionModelStore.State.Downloading -> {
            Text(
                text = "Downloading detection model…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.progress >= 0f) {
                LinearProgressIndicator(progress = { state.progress })
            } else {
                LinearProgressIndicator()
            }
        }
        is DetectionModelStore.State.Ready -> Text(
            text = "Detection model ready (${DetectionModelStore.DISPLAY_SIZE_MB})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is DetectionModelStore.State.Failed -> {
            Text(
                text = "Detection model download failed: ${state.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onDownload) {
                Text("Retry download")
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
fun WatchdogSettingsSection(viewModel: SettingsViewModel) {
    val watchdogEnabled by viewModel.watchdogEnabled.collectAsState()
    val maxRetries by viewModel.watchdogMaxRetries.collectAsState()
    val checkInterval by viewModel.watchdogCheckIntervalSeconds.collectAsState()

    SettingsSection(title = "Stream Watchdog") {
        SwitchSetting(
            title = "Enable Watchdog",
            checked = watchdogEnabled,
            onCheckedChange = { viewModel.updateWatchdogEnabled(it) }
        )
        if (watchdogEnabled) {
            SliderSetting(
                title = "Max Retries",
                value = maxRetries.toFloat(),
                range = StreamDefaultsRange.WATCHDOG_RETRIES,
                onValueChange = { viewModel.updateWatchdogMaxRetries(it.toInt()) }
            )
            SliderSetting(
                title = "Check Interval (seconds)",
                value = checkInterval.toFloat(),
                range = StreamDefaultsRange.WATCHDOG_INTERVAL,
                onValueChange = { viewModel.updateWatchdogCheckIntervalSeconds(it.toInt()) }
            )
        }
    }
}

@Composable
fun BackupSettingsSection(viewModel: SettingsViewModel) {
    val backupEnabled by viewModel.backupEnabled.collectAsState()
    val wifiOnly by viewModel.backupWifiOnly.collectAsState()
    val url by viewModel.backupWebdavUrl.collectAsState()
    val username by viewModel.backupWebdavUsername.collectAsState()

    SettingsSection(title = "Backup (WebDAV)") {
        SwitchSetting(
            title = "Auto-upload New Captures",
            checked = backupEnabled,
            onCheckedChange = { viewModel.updateBackupEnabled(it) }
        )
        if (backupEnabled) {
            // Committed-on-done text fields, mirroring the Security section's
            // password pattern; the password stays write-only.
            OutlinedTextField(
                value = url,
                onValueChange = { viewModel.updateBackupWebdavUrl(it) },
                label = { Text("WebDAV Collection URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { viewModel.updateBackupWebdavUsername(it) },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(4.dp))
            var passwordText by remember { mutableStateOf("") }
            OutlinedTextField(
                value = passwordText,
                onValueChange = { passwordText = it },
                label = { Text("Password (leave blank to keep)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (passwordText.isNotEmpty()) {
                            viewModel.updateBackupWebdavPassword(passwordText)
                            passwordText = ""
                        }
                    }
                ),
            )
            SwitchSetting(
                title = "Upload on Wi-Fi only",
                checked = wifiOnly,
                onCheckedChange = { viewModel.updateBackupWifiOnly(it) }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** Slider ranges for the detection/watchdog surfaces, derived from [StreamDefaults] bounds. */
internal object StreamDefaultsRange {
    val MOTION_SENSITIVITY =
        StreamDefaults.MOTION_SENSITIVITY_MIN.toFloat()..StreamDefaults.MOTION_SENSITIVITY_MAX.toFloat()
    val MOTION_POST_ROLL =
        StreamDefaults.MOTION_POST_ROLL_MIN_SECONDS.toFloat()..StreamDefaults.MOTION_POST_ROLL_MAX_SECONDS.toFloat()
    val MINUTE_OF_DAY = 0f..(StreamDefaults.MINUTES_PER_DAY - 1).toFloat()
    val SOUND_THRESHOLD =
        StreamDefaults.SOUND_THRESHOLD_MIN.toFloat()..StreamDefaults.SOUND_THRESHOLD_MAX.toFloat()
    val WATCHDOG_RETRIES =
        StreamDefaults.WATCHDOG_MAX_RETRIES_MIN.toFloat()..StreamDefaults.WATCHDOG_MAX_RETRIES_MAX.toFloat()
    val WATCHDOG_INTERVAL =
        StreamDefaults.WATCHDOG_CHECK_INTERVAL_MIN_SECONDS.toFloat()..StreamDefaults.WATCHDOG_CHECK_INTERVAL_MAX_SECONDS.toFloat()
    val ML_SCORE_PERCENT =
        StreamDefaults.ML_SCORE_MIN_PERCENT.toFloat()..StreamDefaults.ML_SCORE_MAX_PERCENT.toFloat()
    val CONTINUOUS_SEGMENT_MINUTES =
        StreamDefaults.CONTINUOUS_SEGMENT_MIN_MINUTES.toFloat()..StreamDefaults.CONTINUOUS_SEGMENT_MAX_MINUTES.toFloat()
    val MOTION_COOLDOWN =
        StreamDefaults.MOTION_COOLDOWN_MIN_SECONDS.toFloat()..StreamDefaults.MOTION_COOLDOWN_MAX_SECONDS.toFloat()
    val SOUND_COOLDOWN =
        StreamDefaults.SOUND_COOLDOWN_MIN_SECONDS.toFloat()..StreamDefaults.SOUND_COOLDOWN_MAX_SECONDS.toFloat()

    // Material3 `steps` counts the discrete points BETWEEN the endpoints, so
    // a 5-unit slider step is (span / 5) - 1.
    val ML_SCORE_STEPS = (StreamDefaults.ML_SCORE_MAX_PERCENT - StreamDefaults.ML_SCORE_MIN_PERCENT) / 5 - 1
    val CONTINUOUS_SEGMENT_STEPS =
        (StreamDefaults.CONTINUOUS_SEGMENT_MAX_MINUTES - StreamDefaults.CONTINUOUS_SEGMENT_MIN_MINUTES) / 5 - 1

    // Storage quota steps in 100 MB increments across the persisted span.
    val STORAGE_QUOTA_STEPS =
        (StreamDefaults.STORAGE_QUOTA_MB_MAX - StreamDefaults.STORAGE_QUOTA_MB_MIN) / 100 - 1
}
