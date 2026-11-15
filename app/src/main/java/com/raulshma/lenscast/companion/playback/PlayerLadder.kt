package com.raulshma.lenscast.companion.playback

/**
 * The companion viewer's playback rungs, ordered most → least preferred
 * (ordinal = preference). Port of web/src/video/playerLadder.ts's
 * `PLAYER_LADDER = ['whep', 'h264', 'mjpeg', 'hls']` with two deliberate
 * deviations for Android:
 *
 *  - The h264/WebCodecs rung is dropped entirely: the browser needed it
 *    because MediaSource-feeding is cheap there, while on Android the WHEP
 *    and HLS rungs already cover hardware-decoded H.264 (user decision for
 *    the companion mode).
 *  - The TS ladder ranks mjpeg above hls (raw multipart beats the ~10s HLS
 *    sliding window on latency, at the cost of audio). The companion ladder
 *    ranks HLS above MJPEG: a demotion keeps muxed A/V — and with it the
 *    /audio lane's stereo pair — available one rung longer, falling to the
 *    video-only MJPEG only when HLS is exhausted. MJPEG is terminal here;
 *    the web's hls → whep wrap lives only in manual [PlayerLadder.cycle].
 *
 * Pure decision math — the caller owns the players, the signals, and the
 * retry timers, exactly as in the TS module.
 */
enum class PlaybackRung {
    WHEP,
    HLS,
    MJPEG,
}

/**
 * The ladder state machine. Port of web/src/video/playerLadder.ts:
 * [demote] mirrors `nextPlayerMode`'s fall-down (one rung lower; the failed
 * rung is stamped with a cooldown so [reset] cannot immediately re-attempt
 * it), [cycle] mirrors `cyclePlayerMode` (one step down, wrapping
 * mjpeg → whep — the web's manual toggle semantics, cooldown-blind), and
 * [reset] mirrors `nextPlayerMode`'s "best eligible rung from the top"
 * verdict, including its exhausted-ladder behavior ("`current` comes back
 * unchanged when every rung is exhausted, so the caller keeps its own retry
 * loop").
 *
 * All transitions are synchronized: the wave-2 driver touches the ladder
 * from player error callbacks while the UI reads [current] for the rung
 * badge, mirroring how the web's signal is written from several onError
 * closures and read by the render.
 */
class PlayerLadder(
    start: PlaybackRung = PlaybackRung.WHEP,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val cooldownMs: Long = DEMOTE_COOLDOWN_MS,
) {

    private val lock = Any()

    @Volatile
    private var currentRung = start

    /** Rung → wall-clock deadline until which [reset] must not pick it. Guarded by [lock]. */
    private val coolingUntil = HashMap<PlaybackRung, Long>()

    /** The rung playback should be on right now. */
    val current: PlaybackRung
        get() = currentRung

    /**
     * Fall down one rung after the current one failed: WHEP → HLS → MJPEG,
     * staying at MJPEG (terminal — the web's mjpeg rung demotes to HLS, but
     * this ladder ranks HLS above MJPEG, so there is nothing below it). The
     * abandoned rung is stamped with [DEMOTE_COOLDOWN_MS] of cooldown so a
     * hasty [reset] lands on the next rung instead of bouncing straight back
     * to the one that just failed.
     */
    fun demote() {
        synchronized(lock) {
            val next = rungsFrom(currentRung).drop(1).firstOrNull() ?: return
            coolDown(currentRung)
            currentRung = next
        }
    }

    /**
     * The manual toggle: one step down the ladder, wrapping MJPEG → WHEP
     * (`cyclePlayerMode`'s exact index math). Cooldown-blind on purpose —
     * the web's cycle has no failure filtering, and a user tap is an
     * explicit instruction that overrides what the ladder "learned".
     */
    fun cycle() {
        synchronized(lock) {
            val rungs = PlaybackRung.entries
            currentRung = rungs[(rungs.indexOf(currentRung) + 1) % rungs.size]
        }
    }

    /**
     * Re-promote to the best eligible rung starting at [preferred] (the
     * caller calls this when a playback session truly restarts — a new
     * stream session, a reconnect). A rung inside its post-[demote] cooldown
     * is skipped, mirroring `nextPlayerMode`'s `whepFailed`-style flags
     * ("filter out rungs that already gave up this cycle"); when every rung
     * is still cooling, `current` is left unchanged — the TS function's
     * documented exhausted-ladder contract.
     */
    fun reset(preferred: PlaybackRung = PlaybackRung.WHEP) {
        synchronized(lock) {
            val eligible = rungsFrom(preferred).firstOrNull { !isCooling(it) }
            if (eligible != null) currentRung = eligible
        }
    }

    /** True while [rung] is inside its post-[demote] cooldown window. */
    fun isCoolingDown(rung: PlaybackRung): Boolean = synchronized(lock) { isCooling(rung) }

    /** Milliseconds until [rung] becomes [reset]-eligible again; 0 when already eligible. */
    fun cooldownRemainingMs(rung: PlaybackRung): Long = synchronized(lock) {
        val deadline = coolingUntil[rung] ?: return 0L
        (deadline - nowMs()).coerceAtLeast(0L)
    }

    private fun rungsFrom(from: PlaybackRung): List<PlaybackRung> =
        PlaybackRung.entries.drop(PlaybackRung.entries.indexOf(from))

    private fun isCooling(rung: PlaybackRung): Boolean {
        val deadline = coolingUntil[rung] ?: return false
        return deadline > nowMs()
    }

    private fun coolDown(rung: PlaybackRung) {
        coolingUntil[rung] = nowMs() + cooldownMs
    }

    companion object {
        /**
         * How long a demoted rung is ineligible for auto-[reset]. The TS
         * ladder has NO time-based cooldown — its `whepFailed`/`h264Failed`
         * flags are caller-owned booleans that persist per ladder cycle — so
         * this 30s window is this port's minimal hysteresis addition,
         * justified by the companion driver's call pattern: unlike the web
         * (where re-promotion only happens via the manual cycle or the HLS
         * fatal wrap), the Android driver re-enters [reset] on every new
         * session tick, and without a timer a failure→reset loop would
         * churn WHEP↔HLS against a device that is still recovering. 30s
         * sits above the web's longest retry backoff
         * (previewRetryPolicy.ts's NO_HLS_RETRY_MS = 15s) and covers several
         * LiveAudioPlayer.ts-style reconnect attempts (3 × 2s), so a rung is
         * never re-attempted inside one degradation window.
         */
        const val DEMOTE_COOLDOWN_MS = 30_000L
    }
}
