package com.raulshma.lenscast.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.raulshma.lenscast.capture.model.RecordingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the recording pipeline is doing. Published by [RecordingController];
 * [RecordingService] is the truth source, so every consumer — camera screen,
 * capture screen, Web API — sees the same state and optimistic copies become
 * impossible.
 */
sealed interface RecordingState {
    /** No recording live and none scheduled. */
    data object Idle : RecordingState

    /** A start is waiting for [startAtMs]; the service is not yet running. */
    data class Scheduled(val startAtMs: Long, val config: RecordingConfig) : RecordingState

    /** The service is holding a recording (or draining its last bytes). */
    data class Recording(
        val startedAtMs: Long,
        val config: RecordingConfig?,
        val finalizing: Boolean = false,
    ) : RecordingState
}

/**
 * Single owner of recording choreography: intent construction for
 * [RecordingService], delay-until scheduling, and the observable
 * [RecordingState]. The service reports what actually happened through the
 * `onService*` callbacks — the controller never guesses live state; callers
 * only ever start/stop and observe.
 *
 * All mutators are serialized on [lock]: they are called from Main (service,
 * ViewModels) and from the scheduler scope concurrently.
 */
class RecordingController(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val lock = Any()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    val isRecording: StateFlow<Boolean> = state
        .map { it is RecordingState.Recording }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var scheduledJob: Job? = null
    private var durationJob: Job? = null
    private var repeatJob: Job? = null

    // Bumped on every user-initiated stop()/start(). The policy compares it
    // under [lock] before arming its repeat, closing the window where a stop
    // landing between the auto-stop and the repeat arming would otherwise be
    // overridden by the restart. Also true (in [policyStopPending]) while the
    // service drains a policy-initiated stop, so an ordinary stop report
    // doesn't cancel the armed repeat.
    private var policyEpoch = 0
    private var policyStopPending = false

    /**
     * Start recording with [config]. When [startAtMs] (or the config's own
     * `startTimeMs`) is in the future, the state becomes [RecordingState.Scheduled]
     * and the service is launched once that time arrives. A pending schedule
     * is always superseded.
     */
    fun start(config: RecordingConfig, startAtMs: Long? = null) {
        synchronized(lock) {
            policyEpoch++
            cancelScheduleLocked()
            // A brand-new start supersedes any pending auto-stop/repeat cycle.
            cancelDurationPolicyLocked()
        }
        val effectiveStart = startAtMs ?: config.startTimeMs
        if (effectiveStart != null && effectiveStart > System.currentTimeMillis()) {
            schedule(config, effectiveStart)
        } else {
            launchService(startIntent(config), foreground = true)
        }
    }

    /** Stop any live recording and cancel any pending scheduled start. */
    fun stop() {
        synchronized(lock) {
            policyEpoch++
            if (_state.value is RecordingState.Scheduled) {
                cancelScheduleLocked()
            }
            // A user stop also kills the auto-stop/repeat cycle.
            cancelDurationPolicyLocked()
        }
        // Always send the stop intent, even straight after a cancel: the
        // scheduled job may have already fired (cancel no-ops past its delay
        // point), and the queued STOP then lands after the START in the
        // service's serialized onStartCommand. The service reports
        // Finalizing/Stopped as it drains — no optimistic writes here, so a
        // stop aimed at a dead service cannot wedge the state either.
        launchService(stopIntent(), foreground = false)
    }

    /** Cancel a pending scheduled start without touching a live recording. */
    fun cancelSchedule() {
        synchronized(lock) { cancelScheduleLocked() }
    }

    // ── Service reports: the only writers of live state ──

    fun onServiceStarted(startedAtMs: Long, config: RecordingConfig?) {
        synchronized(lock) {
            scheduledJob = null
            durationJob?.cancel()
            durationJob = null
            _state.value = RecordingState.Recording(startedAtMs, config)
            if (config != null && config.durationSeconds > 0) {
                armDurationPolicyLocked(config, startedAtMs, policyEpoch)
            }
        }
    }

    /**
     * Bounded-recording policy, armed on every service-confirmed start whose
     * config sets [RecordingConfig.durationSeconds]: auto-stop when the
     * duration elapses, optionally re-start after [RecordingConfig.repeatIntervalSeconds].
     * The policy lives here — not in any single caller — so every client
     * (camera screen, capture screen, Web API) gets the same behavior and its
     * lifetime is the controller's, not a screen's. [epoch] is the value of
     * [policyEpoch] at arm time; every step re-checks it under [lock] so a
     * user stop()/start() always wins.
     */
    private fun armDurationPolicyLocked(config: RecordingConfig, startedAtMs: Long, epoch: Int) {
        durationJob = scope.launch {
            val self = kotlin.coroutines.coroutineContext[Job]
            val remainMs = config.durationSeconds * 1000 - (System.currentTimeMillis() - startedAtMs)
            if (remainMs > 0) delay(remainMs)
            synchronized(lock) {
                if (durationJob !== self || policyEpoch != epoch) return@launch
                durationJob = null
                policyStopPending = true
            }
            // Stop through the service intent — not [stop] — so this job's own
            // repeat follow-up survives.
            launchService(stopIntent(), foreground = false)

            if (config.repeatIntervalSeconds > 0) {
                synchronized(lock) {
                    if (policyEpoch == epoch) {
                        repeatJob?.cancel()
                        repeatJob = scope.launch {
                            val repeatSelf = kotlin.coroutines.coroutineContext[Job]
                            delay(config.repeatIntervalSeconds * 1000)
                            synchronized(lock) {
                                val fire = repeatJob === repeatSelf && policyEpoch == epoch &&
                                    _state.value is RecordingState.Idle
                                if (fire) {
                                    repeatJob = null
                                    launchService(startIntent(config), foreground = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun cancelDurationPolicyLocked() {
        durationJob?.cancel()
        durationJob = null
        repeatJob?.cancel()
        repeatJob = null
    }

    fun onServiceFinalizing() {
        synchronized(lock) {
            val current = _state.value
            if (current is RecordingState.Recording) {
                _state.value = current.copy(finalizing = true)
            }
        }
    }

    fun onServiceStopped() {
        synchronized(lock) {
            scheduledJob?.cancel()
            scheduledJob = null
            durationJob?.cancel()
            durationJob = null
            if (policyStopPending) {
                policyStopPending = false
                // repeatJob survives: it is waiting out the repeat gap after
                // the policy's own auto-stop; it re-checks Idle and the epoch
                // before re-starting. A user stop() bumps the epoch and
                // cancels it there.
            } else {
                // The stop came from elsewhere (user, error, service death) —
                // an armed repeat must not fire behind it.
                repeatJob?.cancel()
                repeatJob = null
            }
            _state.value = RecordingState.Idle
        }
    }

    private fun cancelScheduleLocked() {
        scheduledJob?.cancel()
        scheduledJob = null
        if (_state.value is RecordingState.Scheduled) {
            _state.value = RecordingState.Idle
        }
    }

    private fun schedule(config: RecordingConfig, startAtMs: Long) {
        synchronized(lock) {
            scheduledJob?.cancel()
            _state.value = RecordingState.Scheduled(startAtMs, config)
            scheduledJob = scope.launch {
                val self = kotlin.coroutines.coroutineContext[Job]
                val delayMs = startAtMs - System.currentTimeMillis()
                if (delayMs > 0) delay(delayMs)
                // A stop() past the delay point cannot cancel this job anymore;
                // only fire if we are still the active schedule.
                val fire = synchronized(lock) { scheduledJob === self }
                if (fire) {
                    launchService(startIntent(config), foreground = true)
                }
            }
        }
    }

    private fun startIntent(config: RecordingConfig): Intent =
        Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_CONFIG, RecordingConfigJson.encode(config))
        }

    private fun stopIntent(): Intent =
        Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }

    private fun launchService(intent: Intent, foreground: Boolean) {
        runCatching {
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure { Log.e(TAG, "Failed to launch RecordingService", it) }
    }

    companion object {
        private const val TAG = "RecordingController"
    }
}

/** Moshi helper shared by everyone that puts a [RecordingConfig] on an intent. */
object RecordingConfigJson {
    private val adapter by lazy { com.raulshma.lenscast.core.AppJson.moshi.adapter(RecordingConfig::class.java) }

    fun encode(config: RecordingConfig): String = adapter.toJson(config)
    fun decode(json: String): RecordingConfig? = adapter.fromJson(json)
}
