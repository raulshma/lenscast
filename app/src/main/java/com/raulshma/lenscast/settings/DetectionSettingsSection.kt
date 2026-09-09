package com.raulshma.lenscast.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.raulshma.lenscast.core.StreamDefaults

/**
 * The detection (motion + sound), watchdog, and backup settings sections.
 * Like every settings surface: the screen only writes the store — the
 * Settings Applier applies values to the runtime detectors.
 */
@Composable
fun DetectionSettingsSection(viewModel: SettingsViewModel) {
    val motionDetectionEnabled by viewModel.motionDetectionEnabled.collectAsState()
    val motionSensitivity by viewModel.motionSensitivityPercent.collectAsState()
    val motionRecordingEnabled by viewModel.motionRecordingEnabled.collectAsState()
    val motionPostRoll by viewModel.motionPostRollSeconds.collectAsState()
    val armScheduleEnabled by viewModel.motionArmScheduleEnabled.collectAsState()
    val armStartMinute by viewModel.motionArmStartMinute.collectAsState()
    val armEndMinute by viewModel.motionArmEndMinute.collectAsState()
    val soundEnabled by viewModel.soundDetectionEnabled.collectAsState()
    val soundThreshold by viewModel.soundThresholdPercent.collectAsState()
    val mlEnabled by viewModel.mlDetectionEnabled.collectAsState()
    val mlMinScore by viewModel.mlMinScorePercent.collectAsState()
    val notificationEnabled by viewModel.detectionNotificationsEnabled.collectAsState()
    val tamperEnabled by viewModel.tamperDetectionEnabled.collectAsState()

    SettingsSection(title = "Detection & Alerts") {
        // Persisted toggles: the screen writes the store, the Settings
        // Applier applies them to the runtime detectors.
        SwitchSetting(
            title = "Motion Detection",
            checked = motionDetectionEnabled,
            onCheckedChange = { viewModel.updateMotionDetectionEnabled(it) }
        )
        if (motionDetectionEnabled) {
            SliderSetting(
                title = "Motion Sensitivity (%)",
                value = motionSensitivity.toFloat(),
                range = StreamDefaultsRange.MOTION_SENSITIVITY,
                onValueChange = { viewModel.updateMotionSensitivity(it.toInt()) }
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
        }
        SwitchSetting(
            title = "Local Alerts on Detection",
            checked = notificationEnabled,
            onCheckedChange = { viewModel.updateDetectionNotificationsEnabled(it) }
        )
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
        }
    }
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

    // Material3 `steps` counts the discrete points BETWEEN the endpoints, so
    // a 5-unit slider step is (span / 5) - 1.
    val ML_SCORE_STEPS = (StreamDefaults.ML_SCORE_MAX_PERCENT - StreamDefaults.ML_SCORE_MIN_PERCENT) / 5 - 1
    val CONTINUOUS_SEGMENT_STEPS =
        (StreamDefaults.CONTINUOUS_SEGMENT_MAX_MINUTES - StreamDefaults.CONTINUOUS_SEGMENT_MIN_MINUTES) / 5 - 1
}
