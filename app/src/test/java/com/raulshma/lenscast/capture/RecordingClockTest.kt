package com.raulshma.lenscast.capture

import com.raulshma.lenscast.capture.model.RecordingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingClockTest {

    private fun clockScope() = CoroutineScope(SupervisorJob())

    @Test
    fun `idle clock reads zero`() = runBlocking {
        val scope = clockScope()
        try {
            val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
            val clock = RecordingClock(state, scope, tickMs = 10L)
            delay(30)
            assertEquals(0L, clock.elapsedMs.value)
            assertEquals(0, clock.elapsedSeconds.value)
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `clock ticks from the controller start time`() = runBlocking {
        val scope = clockScope()
        try {
            val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
            val clock = RecordingClock(state, scope, tickMs = 10L)
            state.value = RecordingState.Recording(
                startedAtMs = System.currentTimeMillis() - 5000,
                config = RecordingConfig(),
            )
            withTimeout(2000) { clock.elapsedMs.first { it >= 5000 } }
            withTimeout(2000) { clock.elapsedSeconds.first { it >= 5 } }
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `clock resets when recording stops`() = runBlocking {
        val scope = clockScope()
        try {
            val state = MutableStateFlow<RecordingState>(
                RecordingState.Recording(System.currentTimeMillis() - 5000, RecordingConfig()),
            )
            val clock = RecordingClock(state, scope, tickMs = 10L)
            withTimeout(2000) { clock.elapsedMs.first { it > 0 } }
            state.value = RecordingState.Idle
            withTimeout(2000) { clock.elapsedMs.first { it == 0L } }
            assertEquals(0, clock.elapsedSeconds.value)
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `elapsed math matches the handler formula`() {
        val startedAt = 1_000_000L
        assertEquals(5000L, RecordingClock.elapsedMsSince(startedAt, startedAt + 5000))
        assertEquals(5, (RecordingClock.elapsedMsSince(startedAt, startedAt + 5999) / 1000).toInt())
    }
}
