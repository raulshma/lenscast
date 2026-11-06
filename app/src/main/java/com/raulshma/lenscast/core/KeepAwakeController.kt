package com.raulshma.lenscast.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The keep-awake setting's runtime arm: while the setting is on, the device
 * CPU stays awake for the process's whole life — the security-phone posture,
 * where a mounted phone must not go dark because the screen locked — and
 * the hold drops the moment the setting turns off. Process death is the
 * other release guarantee: quitting is an explicit act ([MainApplication]
 * quits through an exit ladder), so the hold never has to guess whether the
 * app is still in use.
 *
 * The start read suspends on the disk value before collecting the flow, so
 * a user who turned the setting off never gets one tick of the hold while
 * the eager shared flow still serves its default — the same
 * disk-over-flow convention as every startup-critical read.
 */
class KeepAwakeController(
    private val keepAwakeEnabledFromDisk: suspend () -> Boolean,
    private val keepAwakeEnabled: Flow<Boolean>,
    private val powerManager: PowerManager,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var holding = keepAwakeEnabledFromDisk()
            if (holding) {
                Log.d(TAG, "Keep-awake on at start: holding")
                powerManager.acquireKeepAwake()
            }
            // The first collected emission is skipped: it is either the disk
            // truth just applied above (a no-op repeat) or the eager flow's
            // stale default racing the startup read — and a StateFlow always
            // re-emits its conflated current value, so the real state arrives
            // one emission later at worst.
            keepAwakeEnabled.drop(1).collect { enabled ->
                if (enabled == holding) return@collect
                holding = enabled
                if (enabled) {
                    Log.d(TAG, "Keep-awake enabled: holding")
                    powerManager.acquireKeepAwake()
                } else {
                    Log.d(TAG, "Keep-awake disabled: releasing")
                    powerManager.releaseKeepAwake()
                }
            }
        }
    }

    companion object {
        private const val TAG = "KeepAwakeController"
    }
}
