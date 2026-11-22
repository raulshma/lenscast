package com.raulshma.lenscast.camera

import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.raulshma.lenscast.camera.model.CountdownState
import com.raulshma.lenscast.camera.model.SelfTimerMode
import com.raulshma.lenscast.camera.model.SelfTimerPolicy

/**
 * The self-timer countdown's shared holder — the job + [CountdownState]
 * machinery both console backends run, extracted verbatim from the local
 * [CameraViewModel] so the remote backend's countdown is bit-identical to
 * the local one (same tick loop, same synchronous initial [Counting] write,
 * same [SelfTimerPolicy.TICK_MS] cadence). Every VERDICT stays in
 * [SelfTimerPolicy]: the runner only owns the coroutine that walks the
 * policy's answers and the state flow the overlays render — no decision of
 * its own.
 *
 * The shutter dispatch is here too, so no backend re-rolls it:
 * [onShutterPress] asks the policy whether the press captures now or counts
 * down — a now-capture is delegated to the caller's `captureNow` lambda (the
 * two backends fire different transports), a countdown starts here and calls
 * the shared `onFire` lambda at the policy's Fire boundary. Cancellation
 * (a tap while counting, the mode flipping to OFF) is [cancelSelfTimer]'s
 * policy-checked tap path or the unconditional [cancelCountdown].
 *
 * Injectable clock + pacing keep the loop JVM-testable without virtual
 * time: production reads [SystemClock.elapsedRealtime] and sleeps
 * [SelfTimerPolicy.TICK_MS] per tick; a test advances a fake clock inside a
 * no-op pause and the whole countdown runs deterministically on real
 * threads. Lifetime: [dispose] cancels the job (the owner ViewModel's
 * `onCleared` calls it); the runner holds no other resources.
 */
class SelfTimerRunner(
    /** The countdown job's home (the owner's scope — `viewModelScope` or the console's). */
    private val scope: CoroutineScope,
    /** Called exactly once at the policy's Fire boundary — the backend's capture transport. */
    private val onFire: () -> Unit,
    /** The monotonic clock the countdown measures against. */
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    /** The tick pacing seam: production delays; tests advance the fake clock. */
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
) {

    private val _state = MutableStateFlow<CountdownState>(CountdownState.Idle)

    /** The countdown state machine (Idle → Counting → Firing → Idle). */
    val state: StateFlow<CountdownState> = _state

    /**
     * Derived remaining-seconds the countdown overlay renders (null when no
     * countdown is counting — Idle and Firing both render as "none").
     */
    val secondsRemaining: StateFlow<Int?> = _state
        .map { (it as? CountdownState.Counting)?.secondsRemaining }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private var job: Job? = null

    /**
     * The shutter press under [mode]: a now-capture runs [captureNow] inline,
     * an armed timer starts the countdown whose Fire calls the constructor's
     * [onFire]. A press while a countdown already runs restarts it (the old
     * job is cancelled first — same duration anyway, but the display snaps
     * to the full count).
     */
    fun onShutterPress(mode: SelfTimerMode, captureNow: () -> Unit) {
        when (val press = SelfTimerPolicy.onShutterPress(mode)) {
            is SelfTimerPolicy.ShutterPress.CaptureNow -> {
                // The policy says the timer is OFF: any still-pending
                // countdown is a stale remnant (the mode flip landing after
                // this press — the backends' OFF-collectors are async).
                // Cancel it synchronously, BEFORE the immediate capture, so
                // one press cannot fire twice — the pending tick never
                // dispatches after this (a tick only runs between pauses,
                // and both consoles' shutter callbacks are main-thread).
                cancelCountdown()
                captureNow()
            }
            is SelfTimerPolicy.ShutterPress.StartCountdown -> startCountdown(press)
        }
    }

    /** A tap on the countdown overlay: cancels a running countdown, passes through otherwise. */
    fun cancelSelfTimer() {
        if (SelfTimerPolicy.onTap(_state.value is CountdownState.Counting) ==
            SelfTimerPolicy.TapVerdict.CANCEL_COUNTDOWN
        ) {
            cancelCountdown()
        }
    }

    /** The one unconditional cancel seam: kill the job and return the machine to Idle. */
    fun cancelCountdown() {
        job?.cancel()
        job = null
        _state.value = CountdownState.Idle
    }

    /** Owner teardown: the countdown never fires after this. */
    fun dispose() {
        job?.cancel()
        job = null
    }

    private fun startCountdown(countdown: SelfTimerPolicy.ShutterPress.StartCountdown) {
        job?.cancel()
        // Synchronous, before the job launches: an immediate cancel must see
        // Counting, or it reads Idle and cannot cancel.
        _state.value = CountdownState.Counting(countdown.initialSeconds)
        job = scope.launch {
            val startedAtMs = elapsedMs()
            while (true) {
                pauseMs(SelfTimerPolicy.TICK_MS)
                // The pause seam may not itself be cancellable (the test
                // pacing is a no-op around the fake clock), so a cancelled
                // job can return from it mid-cancellation — check active
                // state before the tick applies or the Fire dispatches, or
                // a disposed/cancelled countdown would still fire.
                ensureActive()
                when (
                    val tick = SelfTimerPolicy.onTick(
                        countdown.durationMs,
                        elapsedMs() - startedAtMs,
                    )
                ) {
                    is SelfTimerPolicy.Tick.Counting ->
                        _state.value = CountdownState.Counting(tick.secondsRemaining)
                    SelfTimerPolicy.Tick.Fire -> {
                        _state.value = CountdownState.Firing
                        job = null
                        onFire()
                        _state.value = CountdownState.Idle
                        return@launch
                    }
                }
            }
        }
    }
}
