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
 * The boot resume's broadcast shell: BOOT_COMPLETED starts this process,
 * and the whole resume ordering — journal consumption, verdict, execution
 * — is [ResumeController]'s ([ResumeController.Source.Boot]; its class KDoc
 * carries the land-before-execute contract and the read conventions, among
 * them the suspend read of `resumeStreamsOnBoot` through
 * `SettingsDataStore.resumeStreamsOnBootNow`, which dodges the shared
 * StateFlow's default-first race that a plain `.value` read at BOOT time
 * would lose). This receiver owns only what a receiver must: `goAsync`,
 * the failure guard, the finish.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? MainApplication ?: return
        val pending = goAsync()
        scope.launch {
            try {
                app.resumeController.resume(ResumeController.Source.Boot)
            } catch (e: Exception) {
                Log.w(TAG, "Boot stream resume failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
