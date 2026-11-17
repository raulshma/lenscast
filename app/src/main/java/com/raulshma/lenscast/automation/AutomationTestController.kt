package com.raulshma.lenscast.automation

import android.util.Log
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.automation.schedule.AutomationActionSpec
import com.raulshma.lenscast.automation.schedule.AutomationRestorePolicy
import com.raulshma.lenscast.automation.schedule.AutomationStateSnapshot
import com.raulshma.lenscast.capture.RecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Automation Test's owner (issue #18 item 3): arm a rule — snapshot the
 * restorable state, then fire the rule's actions immediately through the
 * SAME executor a scheduled fire uses — and restore: run the
 * [AutomationRestorePolicy]'s verdict back through that executor. One armed
 * test at a time; arming rule B restores rule A first. The armed rule id is
 * published so both UIs (and a reloaded dashboard, via the automation state
 * document) can show a "restore" affordance instead of losing the undo.
 *
 * Deliberately NOT journaled: the fire journal and cooldowns are the
 * scheduler's — a test fire leaves no `lastFiredAt` trace and never
 * satisfies or arms a rule's cooldown. Arm/restore serialize through one
 * mutex, so a second arm's implicit restore and the explicit restore cannot
 * interleave.
 *
 * Stateless like the executor otherwise: the snapshot lives only while a
 * test is armed (process-lifetime — a restart with a test armed restores
 * nothing, which the dashboard re-learns as "nothing armed").
 *
 * The restore drives the live camera itself. Persisting the snapshot values
 * alone leaves the live session at the fired state until the Settings
 * Applier's async emission happens to reach it — and if that emission's
 * apply was still mid-flight from the ARM side, the fired state could land
 * after the restore's and stand indefinitely (the POCO F1 on-device failure:
 * store rewound, camera still manual with the torch burning). So
 * [restoreLocked] first joins the applier's in-flight camera apply, then
 * drives the restored value straight onto the session through the applier's
 * serialized seam — the documented idempotent-overlap contract, not a second
 * application path (the collector's eventual re-apply of the same persisted
 * value is a no-op restatement).
 */
class AutomationTestController(private val app: MainApplication) {

    private val mutex = Mutex()

    private val _armedRuleId = MutableStateFlow<String?>(null)

    /** The armed rule's id, null when nothing is armed. */
    val armedRuleId: StateFlow<String?> = _armedRuleId.asStateFlow()

    private var snapshot: AutomationStateSnapshot? = null
    private var snapshotRuleActions: List<AutomationActionSpec> = emptyList()

    /**
     * Fires [ruleId]'s actions once, immediately (restore-first if another
     * test is armed). Returns false when the id matches no persisted rule —
     * the caller surfaces that, nothing here throws into it.
     */
    suspend fun arm(ruleId: String): Boolean = mutex.withLock {
        val rule = app.settingsDataStore.automationRulesFromDisk()
            .firstOrNull { it.id == ruleId }
        if (rule == null) {
            Log.w(TAG, "Automation test: unknown rule id $ruleId")
            return@withLock false
        }
        // One armed test at a time: the previous test's state is restored
        // before this snapshot is taken, so the snapshot describes the world
        // this fire actually starts from.
        restoreLocked()
        snapshot = captureSnapshot()
        snapshotRuleActions = rule.actions
        Log.i(TAG, "Automation test: firing rule '${rule.name}' ($ruleId)")
        AutomationActionExecutor(app).runActions(rule.actions)
        _armedRuleId.value = ruleId
        true
    }

    /** Winds the armed test's touched axes back to their pre-test values; a no-op when nothing is armed. */
    suspend fun restore() = mutex.withLock {
        restoreLocked()
    }

    private suspend fun restoreLocked() {
        val snap = snapshot ?: return
        val actions = AutomationRestorePolicy.restoreActions(snapshotRuleActions, snap)
        snapshot = null
        snapshotRuleActions = emptyList()
        _armedRuleId.value = null
        if (actions.isNotEmpty()) {
            Log.i(TAG, "Automation test: restoring ${actions.size} action(s)")
            AutomationActionExecutor(app).runActions(actions)
            applyRestoredCameraState(actions)
        }
    }

    /**
     * The deterministic live half of the rewind, run after the restore's
     * persisted writes: join whatever camera apply the arm-side emission
     * still has in flight on the applier (so no pre-restore value can land
     * after ours), then apply the exact post-restore settings — snapshot
     * values on the touched axes (auto where the snapshot had auto, torch
     * off where it was off) — straight onto the live session through the
     * applier's serialized seam. When the camera session is unbound the
     * apply parks in `activeSettings` and the next bind's post-bind rungs
     * pick it up, exactly like every other settings apply.
     */
    private suspend fun applyRestoredCameraState(actions: List<AutomationActionSpec>) {
        val applier = app.settingsApplier
        applier.flushCameraApply()
        applier.applyCameraSettingsNow(
            AutomationRestorePolicy.restoredCameraSettings(
                actions,
                app.settingsDataStore.settings.value,
            ),
        )
    }

    /**
     * Reads the world from DISK, not the shared flows' `.value`: the implicit
     * restore in [arm] may have written the store milliseconds ago, and the
     * DataStore → StateFlow share-in hop has no completion edge — a `.value`
     * read here can still answer the PRE-restore values, which would become
     * the "pre-test" snapshot and make the next restore faithfully restore
     * exactly what it was meant to rewind (the store-left-unreset cycle).
     * A fresh DataStore read is the read-your-write seam.
     *
     * The one deliberate exception is the torch TOGGLE, which comes from the
     * live CameraService fact (`isTorchOn`), not the store: live-only writers
     * (the web torch route, the deterrence coordinator) flip the LED without
     * ever persisting, so the stored intent can disagree with reality — and
     * a restore that answers from the stale stored pair re-lights a dark LED
     * (or darkens a lit one) instead of winding the test back (the on-device
     * 0.2.2 repro). Every wholesale camera write converged to this same fact
     * in e84b36d; the snapshot reads it too. STRENGTH stays the stored
     * intent: a live flip drives the device-default brightness and carries
     * no strength intent of its own (SettingsWebHandler's rebuild precedent).
     */
    private suspend fun captureSnapshot(): AutomationStateSnapshot {
        val saved = app.settingsDataStore.settingsFromDisk()
        return AutomationStateSnapshot(
            iso = saved.iso,
            exposureTimeMs = saved.exposureTime?.let { it / 1_000_000.0 },
            exposureCompensation = saved.exposureCompensation,
            zoomRatio = saved.zoomRatio,
            frameRate = saved.frameRate,
            lensIndex = app.cameraService.selectedLens.value.index,
            torchEnabled = app.cameraService.isTorchOn(),
            torchStrength = saved.torchStrength,
            motionEnabled = app.settingsDataStore.motionDetectionEnabledFromDisk(),
            soundEnabled = app.settingsDataStore.soundDetectionEnabledFromDisk(),
            streamRunning = app.streamingManager.isServerRunning.value,
            recordingIdle = app.recordingController.state.value is RecordingState.Idle,
        )
    }

    private companion object {
        const val TAG = "AutomationTest"
    }
}
