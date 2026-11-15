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
        }
    }

    private fun captureSnapshot() = AutomationStateSnapshot(
        iso = app.settingsDataStore.settings.value.iso,
        exposureTimeMs = app.settingsDataStore.settings.value.exposureTime?.let { it / 1_000_000.0 },
        exposureCompensation = app.settingsDataStore.settings.value.exposureCompensation,
        zoomRatio = app.settingsDataStore.settings.value.zoomRatio,
        frameRate = app.settingsDataStore.settings.value.frameRate,
        lensIndex = app.cameraService.selectedLens.value.index,
        torchEnabled = app.settingsDataStore.settings.value.torchEnabled,
        torchStrength = app.settingsDataStore.settings.value.torchStrength,
        motionEnabled = app.settingsDataStore.motionDetectionEnabled.value,
        soundEnabled = app.settingsDataStore.soundDetectionEnabled.value,
        streamRunning = app.streamingManager.isServerRunning.value,
        recordingIdle = app.recordingController.state.value is RecordingState.Idle,
    )

    private companion object {
        const val TAG = "AutomationTest"
    }
}
