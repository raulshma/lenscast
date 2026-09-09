package com.raulshma.lenscast.streaming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.raulshma.lenscast.MainApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restores the user's stream session after a device reboot: the
 * BOOT_COMPLETED broadcast starts this process, and the resume verdict —
 * [BootResumePolicy.decide] over the `resumeStreamsOnBoot` setting plus the
 * [StreamStateJournal] — runs the same Stream Toggle ladder the camera screen
 * and the Web API start through. No manifest toggle logic is duplicated here.
 *
 * The setting is read through [SettingsDataStore.resumeStreamsOnBootNow],
 * which suspends on DataStore's disk read — the shared StateFlow's first
 * value is the descriptor default (`false`), and BOOT_COMPLETED can arrive
 * tens of milliseconds after Application.onCreate, so a plain `.value` read
 * would silently skip the resume.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? MainApplication ?: return
        val pending = goAsync()
        scope.launch {
            try {
                resumeIfWanted(app)
            } catch (e: Exception) {
                Log.w(TAG, "Boot stream resume failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun resumeIfWanted(app: MainApplication) {
        val verdict = BootResumePolicy.decide(
            settingEnabled = app.settingsDataStore.resumeStreamsOnBootNow(),
            journal = app.streamStateJournal.load(),
        )
        Log.d(TAG, "Boot stream resume verdict: $verdict")
        BootResumePolicy.execute(toggle = app.streamToggle, verdict = verdict)
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
