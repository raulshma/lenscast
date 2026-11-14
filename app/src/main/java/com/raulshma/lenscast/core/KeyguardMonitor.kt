package com.raulshma.lenscast.core

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped observer of the device's keyguard state. Exposes the live
 * locked/unlocked fact as a flow — callers observe instead of taking
 * one-shot snapshots that go stale — and emits an [unlockEvents] cue at the
 * moment the keyguard goes away (ACTION_USER_PRESENT).
 *
 * The consumer is the locked-mount streaming story: a stream started while
 * the device is locked cannot bind the camera (the while-in-use policy
 * denies a background open), and the session flags that state as
 * blocked-by-lock. The unlock cue is the earliest possible moment any heal
 * can be attempted — before the app-visible resume seam — so the stream
 * comes back the instant the user unlocks, not on the next poll cadence.
 *
 * Both directions are tracked: ACTION_USER_PRESENT clears the flag and
 * ACTION_SCREEN_OFF re-reads it (a screen going off is where the keyguard
 * engages, so a process alive since before the lock learns the new truth
 * without waiting for a later SCREEN_ON). Decision sites still refresh()
 * before reading — broadcast delivery to a backgrounded process is not a
 * contract the wake depends on.
 */
class KeyguardMonitor(private val context: Context) {

    private val _isKeyguardLocked = MutableStateFlow(lockedNow())
    val isKeyguardLocked: StateFlow<Boolean> = _isKeyguardLocked.asStateFlow()

    /**
     * One event per keyguard dismissal (ACTION_USER_PRESENT). BufferOverflow
     * policy matches the navigation-events flow (DROP_OLDEST, capacity 8): a
     * burst of events arriving faster than collection can never wedge
     * [MutableSharedFlow.tryEmit], and when the buffer does fill the oldest
     * uncollected cue is dropped first — every live cue re-evaluates the
     * current state anyway, so a stale dropped cue costs nothing.
     */
    private val _unlockEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val unlockEvents: SharedFlow<Unit> = _unlockEvents

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    _isKeyguardLocked.value = false
                    _unlockEvents.tryEmit(Unit)
                    Log.d(TAG, "Keyguard dismissed (user present)")
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> refresh()
            }
        }
    }

    private var registered = false

    fun start() {
        if (registered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            registered = true
            Log.d(TAG, "Keyguard monitoring started (locked=${_isKeyguardLocked.value})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register keyguard receivers", e)
        }
    }

    /** Re-read the keyguard truth — the receiver's state and the manager can drift (e.g. a missed broadcast). */
    fun refresh() {
        val locked = lockedNow()
        if (_isKeyguardLocked.value != locked) {
            _isKeyguardLocked.value = locked
            Log.d(TAG, "Keyguard refreshed: locked=$locked")
        }
    }

    private fun lockedNow(): Boolean = runCatching {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    }.getOrDefault(false)

    companion object {
        private const val TAG = "KeyguardMonitor"
    }
}
