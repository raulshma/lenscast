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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import com.raulshma.lenscast.R
import com.raulshma.lenscast.capture.MotionArmingPolicy
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.capture.model.SoundClassPolicy
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.core.push.VapidSubjectPolicy

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
    val soundClassificationEnabled by viewModel.soundClassificationEnabled.collectAsState()
    val soundClassificationConfidence by viewModel.soundClassificationConfidencePercent.collectAsState()
    val soundClassificationAllowed by viewModel.soundClassificationAllowedClasses.collectAsState()
    val soundTriggerEnabled by viewModel.soundTriggerEnabled.collectAsState()
    val soundTriggerClasses by viewModel.soundTriggerClasses.collectAsState()
    val audioModelState by viewModel.audioModelState.collectAsState()
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
    val pushEnabled by viewModel.pushEnabled.collectAsState()
    val pushVapidSubject by viewModel.pushVapidSubject.collectAsState()

    SettingsSection(title = stringResource(R.string.detection_section_title)) {
        // Persisted toggles: the screen writes the store, the Settings
        // Applier applies them to the runtime detectors.
        SwitchSetting(
            title = stringResource(R.string.detection_motion),
            checked = motionDetectionEnabled,
            onCheckedChange = { viewModel.updateMotionDetectionEnabled(it) }
        )
        if (onOpenEventLog != null) {
            TextButton(onClick = onOpenEventLog) {
                Text(stringResource(R.string.detection_open_event_log))
            }
        }
        if (motionDetectionEnabled) {
            SliderSetting(
                title = stringResource(R.string.detection_motion_sensitivity),
                value = motionSensitivity.toFloat(),
                range = StreamDefaultsRange.MOTION_SENSITIVITY,
                onValueChange = { viewModel.updateMotionSensitivity(it.toInt()) }
            )
            SliderSetting(
                title = stringResource(R.string.detection_event_cooldown),
                value = motionCooldown.toFloat(),
                range = StreamDefaultsRange.MOTION_COOLDOWN,
                onValueChange = { viewModel.updateMotionCooldownSeconds(it.toInt()) }
            )
            SwitchSetting(
                title = stringResource(R.string.detection_record_on_motion),
                checked = motionRecordingEnabled,
                onCheckedChange = { viewModel.updateMotionRecordingEnabled(it) }
            )
            if (motionRecordingEnabled) {
                SliderSetting(
                    title = stringResource(R.string.detection_post_roll),
                    value = motionPostRoll.toFloat(),
                    range = StreamDefaultsRange.MOTION_POST_ROLL,
                    onValueChange = { viewModel.updateMotionPostRollSeconds(it.toInt()) }
                )
            }
            SwitchSetting(
                title = stringResource(R.string.detection_arm_on_schedule),
                checked = armScheduleEnabled,
                onCheckedChange = { viewModel.updateMotionArmScheduleEnabled(it) }
            )
            if (armScheduleEnabled) {
                SliderSetting(
                    title = stringResource(R.string.detection_arm_from),
                    value = armStartMinute.toFloat(),
                    range = StreamDefaultsRange.MINUTE_OF_DAY,
                    steps = 95,
                    onValueChange = { viewModel.updateMotionArmStartMinute(it.toInt()) }
                )
                SliderSetting(
                    title = stringResource(R.string.detection_arm_until),
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
            title = stringResource(R.string.detection_sound),
            checked = soundEnabled,
            onCheckedChange = { viewModel.updateSoundDetectionEnabled(it) }
        )
        if (soundEnabled) {
            SliderSetting(
                title = stringResource(R.string.detection_sound_threshold),
                value = soundThreshold.toFloat(),
                range = StreamDefaultsRange.SOUND_THRESHOLD,
                onValueChange = { viewModel.updateSoundThresholdPercent(it.toInt()) }
            )
            SwitchSetting(
                title = stringResource(R.string.detection_adaptive_noise_floor),
                checked = soundAdaptiveFloor,
                onCheckedChange = { viewModel.updateSoundAdaptiveNoiseFloor(it) }
            )
            if (soundAdaptiveFloor) {
                Text(
                    text = stringResource(R.string.detection_adaptive_noise_floor_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SliderSetting(
                title = stringResource(R.string.detection_event_cooldown),
                value = soundCooldown.toFloat(),
                range = StreamDefaultsRange.SOUND_COOLDOWN,
                onValueChange = { viewModel.updateSoundCooldownSeconds(it.toInt()) }
            )
            SwitchSetting(
                title = stringResource(R.string.detection_record_on_sound),
                checked = soundRecordingEnabled,
                onCheckedChange = { viewModel.updateSoundRecordingEnabled(it) }
            )
            if (soundRecordingEnabled) {
                Text(
                    text = stringResource(R.string.detection_record_on_sound_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SwitchSetting(
                title = stringResource(R.string.detection_sound_classification),
                checked = soundClassificationEnabled,
                onCheckedChange = { viewModel.updateSoundClassificationEnabled(it) }
            )
            if (soundClassificationEnabled) {
                // Annotate-only: the RMS event always fires; classification
                // labels it. Requires Android 7.0+ (API 24), like the ML gate.
                Text(
                    text = stringResource(R.string.detection_sound_classification_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SliderSetting(
                    title = stringResource(R.string.detection_min_confidence),
                    value = soundClassificationConfidence.toFloat(),
                    range = StreamDefaultsRange.SOUND_CLASSIFICATION,
                    steps = StreamDefaultsRange.SOUND_CLASSIFICATION_STEPS,
                    onValueChange = { viewModel.updateSoundClassificationConfidencePercent(it.toInt()) }
                )
                SoundClassChips(
                    allowed = soundClassificationAllowed,
                    onToggle = { label ->
                        val next = if (label in soundClassificationAllowed) {
                            soundClassificationAllowed - label
                        } else {
                            soundClassificationAllowed + label
                        }
                        // The store's descriptor folds an all-off save back to
                        // the curated default, like the arm-schedule's day mask.
                        viewModel.updateSoundClassificationAllowedClasses(next)
                    }
                )
                // The second decision path: opt-in class triggers. A chosen
                // class at/above the confidence floor fires a detection event
                // of its own — additive to the RMS detector, never a gate on
                // it (the RMS events and their labels are untouched).
                SwitchSetting(
                    title = stringResource(R.string.detection_sound_trigger),
                    checked = soundTriggerEnabled,
                    onCheckedChange = { viewModel.updateSoundTriggerEnabled(it) }
                )
                if (soundTriggerEnabled) {
                    Text(
                        text = stringResource(R.string.detection_sound_trigger_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SoundTriggerClassChips(
                        selected = soundTriggerClasses,
                        onToggle = { label ->
                            val next = if (label in soundTriggerClasses) {
                                soundTriggerClasses - label
                            } else {
                                soundTriggerClasses + label
                            }
                            // Same narrowing convention as the allow-list: an
                            // all-off save folds back to the curated default.
                            viewModel.updateSoundTriggerClasses(next)
                        }
                    )
                }
                // The YAMNet model ships outside the APK — this row is its only
                // user-facing fetch control (the classifier's feed also
                // auto-requests the download, throttled).
                AudioModelRow(
                    state = audioModelState,
                    onDownload = { viewModel.downloadAudioModel() },
                )
            }
        }
        SwitchSetting(
            title = stringResource(R.string.detection_local_alerts),
            checked = notificationEnabled,
            onCheckedChange = { viewModel.updateDetectionNotificationsEnabled(it) }
        )
        if (notificationEnabled) {
            SwitchSetting(
                title = stringResource(R.string.detection_quiet_hours),
                checked = quietHoursEnabled,
                onCheckedChange = { viewModel.updateAlertQuietHoursEnabled(it) }
            )
            if (quietHoursEnabled) {
                Text(
                    text = stringResource(R.string.detection_quiet_hours_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SliderSetting(
                    title = stringResource(R.string.detection_quiet_from),
                    value = quietHoursStart.toFloat(),
                    range = StreamDefaultsRange.MINUTE_OF_DAY,
                    steps = 95,
                    onValueChange = { viewModel.updateAlertQuietHoursStartMinute(it.toInt()) }
                )
                SliderSetting(
                    title = stringResource(R.string.detection_quiet_until),
                    value = quietHoursEnd.toFloat(),
                    range = StreamDefaultsRange.MINUTE_OF_DAY,
                    steps = 95,
                    onValueChange = { viewModel.updateAlertQuietHoursEndMinute(it.toInt()) }
                )
            }
        }
        SwitchSetting(
            title = stringResource(R.string.detection_tamper),
            checked = tamperEnabled,
            onCheckedChange = { viewModel.updateTamperDetectionEnabled(it) }
        )
        SwitchSetting(
            title = stringResource(R.string.detection_push),
            checked = pushEnabled,
            onCheckedChange = { viewModel.updatePushEnabled(it) }
        )
        if (pushEnabled) {
            // Web Push pushes to dashboards subscribed through the web UI's
            // Web Push card — the browser's service worker shows the
            // notification even with the tab closed. Subscriptions live
            // there; the phone only carries the master gate, the identity,
            // and the VAPID contact (RFC 8292 sub — device-local by design,
            // it never rides the Web API).
            Text(
                text = stringResource(R.string.detection_push_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Live RFC 8292 sub check: the field saves per keystroke, and an
            // invalid sub only fails later at dispatch — every push service
            // rejects the JWT outright, so the mistake must be visible here.
            val vapidSubjectUsable = VapidSubjectPolicy.isUsable(pushVapidSubject)
            OutlinedTextField(
                value = pushVapidSubject,
                onValueChange = { viewModel.updatePushVapidSubject(it) },
                label = { Text(stringResource(R.string.detection_push_vapid_subject)) },
                isError = !vapidSubjectUsable,
                supportingText = if (!vapidSubjectUsable) {
                    { Text(stringResource(R.string.detection_push_vapid_subject_invalid)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        SwitchSetting(
            title = stringResource(R.string.detection_ml),
            checked = mlEnabled,
            onCheckedChange = { viewModel.updateMlDetectionEnabled(it) }
        )
        if (mlEnabled) {
            // Applies on top of motion detection: motion still arms the event;
            // the on-device model decides whether it carries an allowed class.
            Text(
                text = stringResource(R.string.detection_ml_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SliderSetting(
                title = stringResource(R.string.detection_min_confidence),
                value = mlMinScore.toFloat(),
                range = StreamDefaultsRange.ML_SCORE_PERCENT,
                steps = StreamDefaultsRange.ML_SCORE_STEPS,
                onValueChange = { viewModel.updateMlMinScorePercent(it.toInt()) }
            )
            SwitchSetting(
                title = stringResource(R.string.detection_alert_people),
                checked = mlPerson,
                onCheckedChange = { viewModel.updateMlIncludePerson(it) }
            )
            SwitchSetting(
                title = stringResource(R.string.detection_alert_animals),
                checked = mlPets,
                onCheckedChange = { viewModel.updateMlIncludePets(it) }
            )
            SwitchSetting(
                title = stringResource(R.string.detection_alert_vehicles),
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
    val labels = listOf(
        stringResource(R.string.detection_day_mon),
        stringResource(R.string.detection_day_tue),
        stringResource(R.string.detection_day_wed),
        stringResource(R.string.detection_day_thu),
        stringResource(R.string.detection_day_fri),
        stringResource(R.string.detection_day_sat),
        stringResource(R.string.detection_day_sun),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.detection_arm_days),
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
 * The display label for one curated YAMNet wire label: a resource map from
 * the exact AudioSet spelling ([SoundClassPolicy.SECURITY_CLASSES] member —
 * the persistence and payload key) onto localized chip text. Anything without
 * a resource falls back to [SoundClassPolicy.humanReadable], so future custom
 * labels still render and the wire set itself stays untouched.
 */
@Composable
private fun soundClassChipLabel(label: String): String = when (label) {
    "Speech" -> stringResource(R.string.sound_class_speech)
    "Shout" -> stringResource(R.string.sound_class_shout)
    "Screaming" -> stringResource(R.string.sound_class_screaming)
    "Yell" -> stringResource(R.string.sound_class_yell)
    "Wail, moan" -> stringResource(R.string.sound_class_wail_moan)
    "Dog" -> stringResource(R.string.sound_class_dog)
    "Bark" -> stringResource(R.string.sound_class_bark)
    "Knock" -> stringResource(R.string.sound_class_knock)
    "Doorbell" -> stringResource(R.string.sound_class_doorbell)
    "Glass" -> stringResource(R.string.sound_class_glass)
    "Shatter" -> stringResource(R.string.sound_class_shatter)
    "Alarm" -> stringResource(R.string.sound_class_alarm)
    "Smoke detector, smoke alarm" -> stringResource(R.string.sound_class_smoke_detector)
    "Fire alarm" -> stringResource(R.string.sound_class_fire_alarm)
    "Siren" -> stringResource(R.string.sound_class_siren)
    "Gunshot, gunfire" -> stringResource(R.string.sound_class_gunshot)
    "Cap gun" -> stringResource(R.string.sound_class_cap_gun)
    "Engine starting" -> stringResource(R.string.sound_class_engine_starting)
    else -> SoundClassPolicy.humanReadable(label)
}

/**
 * The sound-classification allow-list chips: one per curated YAMNet class
 * ([SoundClassPolicy.SECURITY_CLASSES] order), selected when persisted. The
 * descriptor folds an all-off save back to the curated default, so the chips
 * narrow, never disarm. Chip text routes through [soundClassChipLabel] —
 * the toggled/report value is always the wire label, never the display.
 */
@Composable
private fun SoundClassChips(allowed: Set<String>, onToggle: (label: String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.detection_sound_classes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // The curated list is long (18 chips); three per row keeps the section
        // scannable without a second layout dependency.
        SoundClassPolicy.SECURITY_CLASSES.chunked(StreamDefaultsRange.SOUND_CHIPS_PER_ROW).forEach { rowClasses ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowClasses.forEach { label ->
                    FilterChip(
                        label = soundClassChipLabel(label),
                        selected = label in allowed,
                        onClick = { onToggle(label) }
                    )
                }
            }
        }
    }
}

/**
 * The sound-trigger chips: one per curated trigger class
 * ([SoundClassPolicy.TRIGGER_CLASSES] order), selected when persisted. The
 * store's descriptor folds an all-off save back to the curated default, so
 * the chips narrow, never disarm. Chip text routes through
 * [soundClassChipLabel] — the toggled/persisted value is the wire label.
 */
@Composable
private fun SoundTriggerClassChips(selected: Set<String>, onToggle: (label: String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.detection_sound_trigger_classes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SoundClassPolicy.TRIGGER_CLASSES.chunked(StreamDefaultsRange.SOUND_CHIPS_PER_ROW).forEach { rowClasses ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowClasses.forEach { label ->
                    FilterChip(
                        label = soundClassChipLabel(label),
                        selected = label in selected,
                        onClick = { onToggle(label) }
                    )
                }
            }
        }
    }
}

/**
 * The on-demand YAMNet model's status row — the audio twin of
 * [DetectionModelRow]: download when missing, progress while fetching, the
 * reason + retry after a failure. Pure echo of [AudioModelStore.State].
 */
@Composable
private fun AudioModelRow(state: AudioModelStore.State, onDownload: () -> Unit) {
    Spacer(modifier = Modifier.height(4.dp))
    when (state) {
        is AudioModelStore.State.NotDownloaded -> {
            Text(
                text = stringResource(R.string.detection_audio_model_not_downloaded, AudioModelStore.DISPLAY_SIZE_MB),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onDownload) {
                Text(stringResource(R.string.detection_model_download))
            }
        }
        is AudioModelStore.State.Downloading -> {
            Text(
                text = stringResource(R.string.detection_audio_model_downloading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.progress >= 0f) {
                LinearProgressIndicator(progress = { state.progress })
            } else {
                LinearProgressIndicator()
            }
        }
        is AudioModelStore.State.Ready -> Text(
            text = stringResource(R.string.detection_audio_model_ready, AudioModelStore.DISPLAY_SIZE_MB),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is AudioModelStore.State.Failed -> {
            Text(
                text = stringResource(R.string.detection_audio_model_failed, state.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onDownload) {
                Text(stringResource(R.string.detection_model_retry))
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
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
                text = stringResource(R.string.detection_ml_model_not_downloaded, DetectionModelStore.DISPLAY_SIZE_MB),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onDownload) {
                Text(stringResource(R.string.detection_model_download))
            }
        }
        is DetectionModelStore.State.Downloading -> {
            Text(
                text = stringResource(R.string.detection_ml_model_downloading),
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
            text = stringResource(R.string.detection_ml_model_ready, DetectionModelStore.DISPLAY_SIZE_MB),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is DetectionModelStore.State.Failed -> {
            Text(
                text = stringResource(R.string.detection_ml_model_failed, state.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onDownload) {
                Text(stringResource(R.string.detection_model_retry))
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

    SettingsSection(title = stringResource(R.string.settings_section_watchdog)) {
        SwitchSetting(
            title = stringResource(R.string.settings_watchdog_enable),
            checked = watchdogEnabled,
            onCheckedChange = { viewModel.updateWatchdogEnabled(it) }
        )
        if (watchdogEnabled) {
            SliderSetting(
                title = stringResource(R.string.settings_watchdog_max_retries),
                value = maxRetries.toFloat(),
                range = StreamDefaultsRange.WATCHDOG_RETRIES,
                onValueChange = { viewModel.updateWatchdogMaxRetries(it.toInt()) }
            )
            SliderSetting(
                title = stringResource(R.string.settings_watchdog_interval),
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

    SettingsSection(title = stringResource(R.string.settings_section_backup)) {
        SwitchSetting(
            title = stringResource(R.string.settings_backup_auto_upload),
            checked = backupEnabled,
            onCheckedChange = { viewModel.updateBackupEnabled(it) }
        )
        if (backupEnabled) {
            // Committed-on-done text fields, mirroring the Security section's
            // password pattern; the password stays write-only.
            OutlinedTextField(
                value = url,
                onValueChange = { viewModel.updateBackupWebdavUrl(it) },
                label = { Text(stringResource(R.string.settings_backup_url)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { viewModel.updateBackupWebdavUsername(it) },
                label = { Text(stringResource(R.string.settings_username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(4.dp))
            var passwordText by remember { mutableStateOf("") }
            OutlinedTextField(
                value = passwordText,
                onValueChange = { passwordText = it },
                label = { Text(stringResource(R.string.settings_backup_password)) },
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
                title = stringResource(R.string.settings_backup_wifi_only),
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
    val SOUND_CLASSIFICATION =
        StreamDefaults.SOUND_CLASSIFICATION_MIN_PERCENT.toFloat()..
            StreamDefaults.SOUND_CLASSIFICATION_MAX_PERCENT.toFloat()
    val CONTINUOUS_SEGMENT_MINUTES =
        StreamDefaults.CONTINUOUS_SEGMENT_MIN_MINUTES.toFloat()..StreamDefaults.CONTINUOUS_SEGMENT_MAX_MINUTES.toFloat()
    val MOTION_COOLDOWN =
        StreamDefaults.MOTION_COOLDOWN_MIN_SECONDS.toFloat()..StreamDefaults.MOTION_COOLDOWN_MAX_SECONDS.toFloat()
    val SOUND_COOLDOWN =
        StreamDefaults.SOUND_COOLDOWN_MIN_SECONDS.toFloat()..StreamDefaults.SOUND_COOLDOWN_MAX_SECONDS.toFloat()
    // HLS DVR window: 0 (live) .. 120 segments, 10-segment steps.
    val HLS_DVR_SEGMENTS =
        0f..StreamDefaults.HLS_DVR_SEGMENTS_MAX.toFloat()
    val HLS_DVR_STEPS = StreamDefaults.HLS_DVR_SEGMENTS_MAX / 10 - 1

    // Material3 `steps` counts the discrete points BETWEEN the endpoints, so
    // a 5-unit slider step is (span / 5) - 1.
    val ML_SCORE_STEPS = (StreamDefaults.ML_SCORE_MAX_PERCENT - StreamDefaults.ML_SCORE_MIN_PERCENT) / 5 - 1
    val CONTINUOUS_SEGMENT_STEPS =
        (StreamDefaults.CONTINUOUS_SEGMENT_MAX_MINUTES - StreamDefaults.CONTINUOUS_SEGMENT_MIN_MINUTES) / 5 - 1

    // Same 5-unit step as the ML score slider (same 10..95 span).
    val SOUND_CLASSIFICATION_STEPS =
        (StreamDefaults.SOUND_CLASSIFICATION_MAX_PERCENT - StreamDefaults.SOUND_CLASSIFICATION_MIN_PERCENT) / 5 - 1

    /** How many allow-list chips render per row in the sound-classification section. */
    const val SOUND_CHIPS_PER_ROW = 3

    // Storage quota steps in 100 MB increments across the persisted span.
    val STORAGE_QUOTA_STEPS =
        (StreamDefaults.STORAGE_QUOTA_MB_MAX - StreamDefaults.STORAGE_QUOTA_MB_MIN) / 100 - 1
}
