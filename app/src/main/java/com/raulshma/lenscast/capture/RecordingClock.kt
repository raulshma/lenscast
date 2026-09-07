package com.raulshma.lenscast.capture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The single recording clock. Both screens derived their own tickers from
 * the Recording Controller's state (different rates, different units);
 * this module derives millisecond and second flows from that same state
 * once, so the displayed elapsed time can't drift from the service's
 * actual start time. The Web API handler reads the same pure elapsed
 * function for its one-shot status.
 */
class RecordingClock(
    recordingState: StateFlow<RecordingState>,
    scope: CoroutineScope,
    private val tickMs: Long = 500L,
) {

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    init {
        scope.launch {
            recordingState.collectLatest { state ->
                if (state is RecordingState.Recording) {
                    while (true) {
                        val elapsed = elapsedMsSince(state.startedAtMs)
                        _elapsedMs.value = elapsed
                        _elapsedSeconds.value = (elapsed / 1000).toInt()
                        delay(tickMs)
                    }
                } else {
                    _elapsedMs.value = 0L
                    _elapsedSeconds.value = 0
                }
            }
        }
    }

    companion object {
        internal fun elapsedMsSince(
            startedAtMs: Long,
            nowMs: Long = System.currentTimeMillis(),
        ): Long = nowMs - startedAtMs
    }
}
