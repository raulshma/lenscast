package com.raulshma.lenscast.capture

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Power-unplug tamper detection, Haven-style: while the device is on charge
 * AND a stream is live, a charging→discharging transition is a tamper event —
 * someone yanked the power from a mounted camera. The pure verdict lives in
 * [TamperPolicy]; this monitor only watches the charging flow and calls
 * [onTamper] on the dispatch path. It runs for the process lifetime — the
 * gates read live on every emission, so it stays inert when disarmed.
 */
class TamperMonitor(
    // A non-conflating flow (buffered SharedFlow): every charging transition
    // must reach the collector, or a flappy connector's tamper edge is lost.
    private val isCharging: Flow<Boolean>,
    // Nullable: an unknown level (no battery reading yet) flows through to
    // the event payload's omitted batteryPercent rather than a fake percent.
    private val batteryPercent: () -> Int?,
    private val enabled: () -> Boolean,
    private val isStreamActive: () -> Boolean,
    private val onTamper: (batteryPercent: Int?) -> Unit,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var previous: Boolean? = null
            isCharging.collect { charging ->
                val last = previous
                previous = charging
                if (last == null) return@collect // initial emission primes the baseline
                if (!TamperPolicy.shouldFire(
                        previousCharging = last,
                        currentCharging = charging,
                        enabled = enabled(),
                        streamActive = isStreamActive(),
                    )
                ) {
                    return@collect
                }
                Log.w("TamperMonitor", "Tamper detected: power disconnected while streaming")
                onTamper(batteryPercent())
            }
        }
    }
}

/** Pure tamper verdict: only an armed, live-stream charging→discharging edge fires. */
object TamperPolicy {
    fun shouldFire(
        previousCharging: Boolean,
        currentCharging: Boolean,
        enabled: Boolean,
        streamActive: Boolean,
    ): Boolean = enabled && streamActive && previousCharging && !currentCharging
}
