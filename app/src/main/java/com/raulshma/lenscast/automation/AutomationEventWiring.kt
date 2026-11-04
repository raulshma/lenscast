package com.raulshma.lenscast.automation

import com.raulshma.lenscast.automation.schedule.AutomationEvent
import com.raulshma.lenscast.automation.schedule.AutomationScheduler
import com.raulshma.lenscast.automation.schedule.AutomationSensorSnapshot
import com.raulshma.lenscast.automation.schedule.AutomationSensorSnapshotProvider
import com.raulshma.lenscast.capture.DetectionEventStore
import com.raulshma.lenscast.capture.RecordingController
import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.core.EventKind
import com.raulshma.lenscast.core.PowerManager
import com.raulshma.lenscast.core.ThermalMonitor
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.StreamingManager
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The production [AutomationSensorSnapshotProvider]: one consistent read of
 * every app-scoped state holder a condition can consult, on demand. Reads
 * are `.value` pulls (and the two pull seams the app already exposes —
 * [CameraService.isTorchOn], [PowerManager.isChargingNow]) because the
 * scheduler asks exactly when it evaluates, not continuously; the snapshot's
 * wall-clock triple is re-stamped by the scheduler from its own injected
 * clock, so the values here only carry the sensor state.
 */
class AutomationSnapshotProvider(
    private val powerManager: PowerManager,
    private val connectivityMonitor: com.raulshma.lenscast.core.ConnectivityMonitor,
    private val thermalMonitor: ThermalMonitor,
    private val streamingManager: StreamingManager,
    private val cameraService: CameraService,
    private val recordingController: RecordingController,
    private val settingsDataStore: SettingsDataStore,
) : AutomationSensorSnapshotProvider {

    override suspend fun snapshot(): AutomationSensorSnapshot =
        AutomationSensorSnapshot.fromCalendar(
            calendar = Calendar.getInstance(),
            charging = powerManager.isChargingNow(),
            batteryLevel = powerManager.batteryLevel.value,
            powerSave = powerManager.isPowerSaveMode.value,
            wifiConnected = connectivityMonitor.isWifiConnected.value,
            streamingActive = streamingManager.isStreaming.value,
            viewerCount = streamingManager.clientCount.value,
            torchOn = cameraService.isTorchOn(),
            recordingActive = recordingController.isRecording.value,
            thermalNormal = thermalMonitor.thermalState.value == ThermalState.NORMAL,
            motionDetectionEnabled = settingsDataStore.motionDetectionEnabled.value,
        )
}

/**
 * The automation engine's event plumbing: turns the app's existing state
 * flows and the detection event stream into [AutomationEvent]s delivered to
 * the scheduler — motion/sound/tamper from the [DetectionEventStore]'s hot
 * flow, charger edges and battery-level changes from [PowerManager],
 * stream start/stop and viewer join/leave from [StreamingManager].
 *
 * Every flow-based collector suppresses its initial emission: StateFlows
 * always replay the current value on collect and the charging SharedFlow
 * replays its last value, so seeding each collector with the current state
 * means only genuine *edges* reach the scheduler — a wiring start must never
 * look like "the charger was just plugged in". The detection flow needs no
 * such guard (it is a live, no-replay stream).
 *
 * App-runtime composition, not screen state: rules must fire even when no
 * screen has composed (headless start via the tile or the Web API), so this
 * is started once from MainApplication like the other collectors.
 */
class AutomationEventWiring(
    private val scheduler: AutomationScheduler,
    private val detectionEventStore: DetectionEventStore,
    private val powerManager: PowerManager,
    private val streamingManager: StreamingManager,
    private val scope: CoroutineScope,
) {

    fun start() {
        watchDetectionEvents()
        watchCharging()
        watchBatteryLevel()
        watchStreaming()
        watchViewers()
    }

    /** Detection events (motion/sound/tamper) → their automation events. */
    private fun watchDetectionEvents() {
        detectionEventStore.eventsFlow
            .onEach { event ->
                when (EventKind.fromWireNameOrNull(event.type)) {
                    EventKind.MOTION -> scheduler.onEvent(AutomationEvent.MotionDetected)
                    EventKind.SOUND -> scheduler.onEvent(AutomationEvent.SoundDetected)
                    EventKind.TAMPER -> scheduler.onEvent(AutomationEvent.TamperDetected)
                    // The manual test alert (and any undecodable name) is not
                    // a device cue; no rule fires.
                    else -> Unit
                }
            }
            .launchIn(scope)
    }

    /**
     * Charger connect/disconnect edges. The SharedFlow replays its last
     * value, so the collector seeds from the synchronous read and emits
     * only genuine transitions.
     */
    private fun watchCharging() {
        var lastCharging = powerManager.isChargingNow()
        powerManager.isCharging
            .onEach { charging ->
                if (charging != lastCharging) {
                    lastCharging = charging
                    scheduler.onEvent(AutomationEvent.ChargingChanged(charging))
                }
            }
            .launchIn(scope)
    }

    /** Battery level changes (integer percents) for threshold-crossing rules. */
    private fun watchBatteryLevel() {
        var lastLevel: Int? = powerManager.batteryLevel.value
        powerManager.batteryLevel
            .onEach { level ->
                if (level != null && level != lastLevel) {
                    scheduler.onEvent(AutomationEvent.BatteryLevelChanged(lastLevel, level))
                }
                if (level != null) lastLevel = level
            }
            .launchIn(scope)
    }

    /** Stream start/stop edges over the any-output-active flow. */
    private fun watchStreaming() {
        var lastStreaming = streamingManager.isStreaming.value
        streamingManager.isStreaming
            .onEach { streaming ->
                if (streaming != lastStreaming) {
                    lastStreaming = streaming
                    scheduler.onEvent(AutomationEvent.StreamingChanged(streaming))
                }
            }
            .launchIn(scope)
    }

    /**
     * Web viewer join/leave: the client count's zero boundary. The count
     * only updates while web streaming is active (it is derived from the
     * frame pump's client read), which is the only time viewers can exist —
     * the trigger is therefore documented as a live-stream viewer cue.
     */
    private fun watchViewers() {
        var lastConnected = streamingManager.clientCount.value > 0
        streamingManager.clientCount
            .onEach { count ->
                val connected = count > 0
                if (connected != lastConnected) {
                    lastConnected = connected
                    scheduler.onEvent(AutomationEvent.ViewersChanged(connected))
                }
            }
            .launchIn(scope)
    }
}
