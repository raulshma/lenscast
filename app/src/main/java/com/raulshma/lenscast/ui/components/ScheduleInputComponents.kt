package com.raulshma.lenscast.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.raulshma.lenscast.R
import com.raulshma.lenscast.capture.MotionArmingPolicy
import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.settings.FilterChip

/**
 * Shared schedule-input widgets: the day-of-week mask chips (generalized out
 * of the detection arm schedule) and the minute-of-day time picker dialog.
 * Both carry the one day-bit convention ([MotionArmingPolicy], bit 0 = Monday)
 * and the one minute span ([StreamDefaults.MINUTES_PER_DAY]) the whole app
 * persists schedules in.
 */

/**
 * The day-of-week mask chips: one chip per ISO day (Monday first), selected
 * when its bit is set in [daysMask]. Toggling reports the ISO day index; the
 * caller folds it into the mask (usually via [MotionArmingPolicy.toggleDay],
 * which never clears the last armed day). [title] renders the row caption the
 * detection arm schedule shows; pass null for a bare chip row.
 */
@Composable
fun DayMaskChips(
    daysMask: Int,
    onToggleDay: (isoDayIndex: Int) -> Unit,
    title: String? = null,
) {
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
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
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
 * The minute-of-day time picker: opens a Material3 time-input dialog seeded
 * from [initialMinuteOfDay] (clamped into 0..1439) and reports the picked
 * instant back as a minute-of-day. 24-hour input, matching the arm-schedule
 * and scheduled-recording pickers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationTimePickerDialog(
    initialMinuteOfDay: Int,
    onConfirm: (minuteOfDay: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = initialMinuteOfDay.coerceIn(0, StreamDefaults.MINUTES_PER_DAY - 1)
    val timePickerState = rememberTimePickerState(
        initialHour = initial / 60,
        initialMinute = initial % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(timePickerState.hour * 60 + timePickerState.minute) }) {
                Text(stringResource(R.string.automation_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.automation_cancel))
            }
        },
        text = {
            TimeInput(state = timePickerState)
        }
    )
}
