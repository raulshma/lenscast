package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.camera.model.CameraSettings

/**
 * The pre-test state snapshot the Automation Test's restore answers from —
 * the rendered wire value of every restorable snapshot axis, keyed by axis.
 * One plain value the [com.raulshma.lenscast.automation.AutomationTestController]
 * captures from the live seams, pure so the restore verdicts are
 * JVM-testable.
 *
 * The key set is NOT hand-wired: it is exactly the axes the catalog entries
 * declare ([AutomationActionCatalog.SNAPSHOT_AXES]) — capture is one loop
 * over that list ([captureSnapshot]), so an entry's declared axes are the
 * only way a value enters the snapshot.
 *
 * @param values The rendered value per axis. null = the axis had no value
 *   (the device default — e.g. torch strength unset) and the restore skips
 *   it; the exposure axes fold their absent reading to the "0" auto
 *   sentinel inline at capture, so they are never null.
 */
data class AutomationStateSnapshot(
    val values: Map<RestoreAxis, String?>,
) {
    /** The boolean reading of a captured flag axis (flag captures render as Boolean.toString). */
    fun flag(axis: RestoreAxis): Boolean = values[axis] == "true"
}

/**
 * The pure restore half of the Automation Test (issue #18 item 3 — "press
 * again to restore the previous state"): the rule's actions + the pre-test
 * snapshot → the action list that winds the touched axes back. The per-entry
 * verdicts live on the catalog entries ([AutomationActionType.restore] —
 * restore axes per touched param, the stop flavors, the not-restorable
 * capture); this object is the generic interpreter over them, so the verdicts
 * cannot drift from their declarations. Invariants the declarations encode:
 *
 * - Only the axes the rule itself touched are restored, per touched param —
 *   a rule that set only ISO does not also force exposure back to auto.
 * - Camera-valued and detection restores converge the PERSISTED state (the
 *   executor's camera/detection semantics), so the Settings Applier lands
 *   them everywhere, exactly like a scheduled fire would.
 * - `siren` restores as a stop (a test must never re-arm a siren on the way
 *   back); `recording` restores as a stop ONLY when the controller was Idle
 *   at arm time — the user's own recording survives the restore.
 * - `capture_photo` is not restorable; unknown ids restore nothing.
 *
 * The controller runs the returned specs through the same executor a
 * scheduled fire uses — no second execution path can drift.
 */
object AutomationRestorePolicy {

    /**
     * The snapshot capture loop: render every catalog-declared axis from ONE
     * reading of the world, in place of the old hand-wired per-axis fields.
     * The rendered strings are exactly the values the restore writes back.
     */
    fun captureSnapshot(live: AutomationLiveState): AutomationStateSnapshot =
        AutomationStateSnapshot(AutomationActionCatalog.SNAPSHOT_AXES.associateWith { axis -> axis.capture(live) })

    fun restoreActions(
        actions: List<AutomationActionSpec>,
        snapshot: AutomationStateSnapshot,
    ): List<AutomationActionSpec> = buildList {
        for (action in actions) {
            val entry = AutomationActionCatalog.typeFor(action.type) ?: continue
            when (val restore = entry.restore) {
                is RestoreSpec.Axes -> {
                    val params = buildMap {
                        for (declared in restore.axes) {
                            // Optional-param axes restore only what the rule touched;
                            // valueless snapshot axes (device defaults) restore nothing.
                            if (declared.onlyIfTouched && declared.axis.param !in action.params) continue
                            val value = snapshot.values[declared.axis] ?: continue
                            put(declared.axis.param ?: continue, value)
                        }
                    }
                    if (params.isNotEmpty()) add(AutomationActionSpec(type = action.type, params = params))
                }
                RestoreSpec.StopWhenArmed ->
                    // A test that started the siren stops it on the way back; a
                    // test that only stopped it has nothing to restore.
                    if (action.params["enabled"] != "false") {
                        add(AutomationActionSpec(type = action.type, params = mapOf("enabled" to "false")))
                    }
                RestoreSpec.StopWhenArmedAndIdle ->
                    // A test that started a recording stops it only when nothing
                    // else was recording at arm time.
                    if (action.params["enabled"] != "false" && snapshot.flag(RestoreAxis.RECORDING_IDLE)) {
                        add(AutomationActionSpec(type = action.type, params = mapOf("enabled" to "false")))
                    }
                RestoreSpec.NotRestorable -> Unit
            }
        }
    }

    /**
     * The post-restore [CameraSettings] the LIVE camera session must show:
     * [restoreActions] folded over the current persisted value through the
     * same [AutomationSchedulePolicy.mergeCameraSettings] merge the executor
     * persists with, so the value applied live is exactly the value the
     * restore saved (touched axes = the snapshot's, everything else
     * untouched). This is the payload of the Automation Test Controller's
     * direct live apply — the pure half of the "restore drives the live
     * camera itself" contract; the auto sentinel "0" folds to null here the
     * same way it does on the persisted path, so a snapshot with auto
     * ISO/exposure lands as driver-auto, not as a stale manual carry-over.
     */
    fun restoredCameraSettings(
        actions: List<AutomationActionSpec>,
        base: CameraSettings,
    ): CameraSettings = actions.fold(base) { acc, action ->
        AutomationSchedulePolicy.mergeCameraSettings(acc, action)
    }
}
