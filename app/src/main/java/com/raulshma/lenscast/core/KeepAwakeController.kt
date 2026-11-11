package com.raulshma.lenscast.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
 * The startup reconcile is a two-phase machine: the disk read seeds the hold
 * (a user who turned the setting off never gets one tick of it while the
 * eager shared flow still serves its default — the disk-over-flow convention
 * as every startup-critical read), and then every flow emission reconciles
 * against that seed — including the first. The first emission is the only
 * one that can still be `stateIn`'s pre-DataStore default, so it is trusted
 * only when a fresh disk read (taken at collect time, with any write that
 * landed in the startup gap already persisted) says the same thing. The old
 * blanket `drop(1)` had the opposite bug: it discarded a real toggle that
 * landed between the disk read and the first emission — a StateFlow does
 * not re-emit an unchanged value — and stranded the hold at the stale value
 * until the next toggle.
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
            var startupConfirmed = false
            keepAwakeEnabled.collect { enabled ->
                if (!startupConfirmed) {
                    if (keepAwakeEnabledFromDisk() == enabled) startupConfirmed = true
                    else return@collect
                }
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
