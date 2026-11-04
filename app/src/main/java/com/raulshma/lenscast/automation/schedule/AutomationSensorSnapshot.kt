package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.core.LocalWallClock
import java.util.Calendar

/**
 * One consistent read of every device/app state an automation condition can
 * consult, taken at evaluation time. Pure data — the verdicts comparing a
 * condition against it live in [AutomationConditionPolicy], keeping the
 * whole decision surface JVM-testable the same way
 * [AutomationSchedulePolicy] is.
 *
 * The wall-clock triple ([minuteOfDay]/[epochDay]/[isoDayIndex]) rides along
 * so both the time-of-day trigger and the [AutomationConditionCatalog.TIME_WINDOW]
 * condition read the same derived numbers in one snapshot — the same
 * [LocalWallClock] derivations the scheduler's tick makes, captured once.
 *
 * `null` [batteryLevel] means "unknown" (the OS has not reported a level
 * yet); conditions that compare levels treat unknown as *not met* rather
 * than guessing.
 *
 * @param charging On external power right now.
 * @param batteryLevel Percent 0..100, or null when not yet known.
 * @param powerSave OS battery-saver active.
 * @param wifiConnected A Wi-Fi network is connected.
 * @param streamingActive Any streaming output is active (web/RTSP/RTMP/…).
 * @param viewerCount Live web viewer count (WHEP/HTTP/HLS/WS consumers).
 * @param torchOn The camera's torch is lit right now (live state, not the
 *   persisted setting).
 * @param recordingActive A recording is running (bounded or continuous).
 * @param thermalNormal Thermal state is normal (nothing throttling).
 * @param motionDetectionEnabled The persisted motion-detection toggle.
 */
data class AutomationSensorSnapshot(
    val minuteOfDay: Int,
    val epochDay: Long,
    val isoDayIndex: Int,
    val charging: Boolean,
    val batteryLevel: Int?,
    val powerSave: Boolean,
    val wifiConnected: Boolean,
    val streamingActive: Boolean,
    val viewerCount: Int,
    val torchOn: Boolean,
    val recordingActive: Boolean,
    val thermalNormal: Boolean,
    val motionDetectionEnabled: Boolean,
) {
    companion object {
        /**
         * Derives the wall-clock triple from [calendar] — the shared
         * [LocalWallClock] math — so a caller assembling a snapshot (the
         * scheduler's tick, the wiring's event path) never re-derives them
         * by hand and the numbers inside one snapshot cannot disagree.
         */
        fun fromCalendar(
            calendar: Calendar,
            charging: Boolean,
            batteryLevel: Int?,
            powerSave: Boolean,
            wifiConnected: Boolean,
            streamingActive: Boolean,
            viewerCount: Int,
            torchOn: Boolean,
            recordingActive: Boolean,
            thermalNormal: Boolean,
            motionDetectionEnabled: Boolean,
        ): AutomationSensorSnapshot = AutomationSensorSnapshot(
            minuteOfDay = LocalWallClock.minuteOfDay(calendar),
            epochDay = LocalWallClock.epochDay(calendar),
            isoDayIndex = LocalWallClock.isoDayIndex(calendar),
            charging = charging,
            batteryLevel = batteryLevel,
            powerSave = powerSave,
            wifiConnected = wifiConnected,
            streamingActive = streamingActive,
            viewerCount = viewerCount,
            torchOn = torchOn,
            recordingActive = recordingActive,
            thermalNormal = thermalNormal,
            motionDetectionEnabled = motionDetectionEnabled,
        )
    }
}

/**
 * The runtime seam that produces snapshots: the scheduler consults it once
 * per tick and once per event, and the production implementation reads the
 * app-scoped state holders (power, connectivity, streaming, camera,
 * recording, thermal, settings). Tests substitute a fixed snapshot.
 */
fun interface AutomationSensorSnapshotProvider {
    suspend fun snapshot(): AutomationSensorSnapshot
}
