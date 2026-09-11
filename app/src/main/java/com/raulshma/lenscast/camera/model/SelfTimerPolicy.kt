package com.raulshma.lenscast.camera.model

/**
 * The self-timer duration, persisted as its name (see the Settings Store's
 * `self_timer` descriptor). `OFF` captures immediately.
 */
enum class SelfTimerMode { OFF, S3, S10 }

/**
 * The pure self-timer decision core: what a shutter press starts, what each
 * countdown tick shows, and whether a tap cancels. The ViewModel keeps only
 * the countdown job and the exposed remaining-seconds state; every verdict
 * (including the fire boundary) is decided here and pinned by test.
 */
object SelfTimerPolicy {

    /** How often the countdown re-evaluates; smooth enough for whole-second display. */
    const val TICK_MS = 200L

    /** The shutter press's verdict: capture now, or run a countdown first. */
    sealed class ShutterPress {
        object CaptureNow : ShutterPress()
        data class StartCountdown(val durationMs: Long, val initialSeconds: Int) : ShutterPress()
    }

    /** One countdown tick's verdict: still counting, or fire the capture. */
    sealed class Tick {
        data class Counting(val secondsRemaining: Int) : Tick()
        object Fire : Tick()
    }

    /** A tap on the countdown overlay's verdict. */
    enum class TapVerdict { CANCEL_COUNTDOWN, PASS_THROUGH }

    /** The countdown length in milliseconds; 0 when the timer is off. */
    fun durationMs(mode: SelfTimerMode): Long = when (mode) {
        SelfTimerMode.OFF -> 0L
        SelfTimerMode.S3 -> 3_000L
        SelfTimerMode.S10 -> 10_000L
    }

    /** The whole seconds the countdown overlay initially shows. */
    fun initialSeconds(mode: SelfTimerMode): Int = (durationMs(mode) / 1_000L).toInt()

    /**
     * What a shutter press does under [mode]: an armed timer starts a
     * countdown of its full duration, an off timer captures immediately.
     */
    fun onShutterPress(mode: SelfTimerMode): ShutterPress =
        if (mode == SelfTimerMode.OFF) {
            ShutterPress.CaptureNow
        } else {
            ShutterPress.StartCountdown(durationMs(mode), initialSeconds(mode))
        }

    /**
     * The tick verdict at [elapsedMs] into a countdown of [durationMs]:
     * counting with the whole seconds remaining (rounded up, so the display
     * starts at the full duration and never shows 0 before firing), or fire
     * once the duration has fully elapsed.
     */
    fun onTick(durationMs: Long, elapsedMs: Long): Tick {
        if (elapsedMs >= durationMs) return Tick.Fire
        val remaining = ((durationMs - elapsedMs) / 1_000L).toInt() + 1
        return Tick.Counting(remaining)
    }

    /**
     * A tap during an armed countdown cancels it (the mode stays selected, so
     * the next press counts down again); with no countdown running the tap is
     * a normal preview tap.
     */
    fun onTap(countdownActive: Boolean): TapVerdict =
        if (countdownActive) TapVerdict.CANCEL_COUNTDOWN else TapVerdict.PASS_THROUGH
}
