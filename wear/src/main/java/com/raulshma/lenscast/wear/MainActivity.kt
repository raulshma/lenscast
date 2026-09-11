package com.raulshma.lenscast.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.MaterialTheme
import kotlinx.coroutines.launch

/**
 * The remote host activity: builds the controller graph (DataStore-backed
 * settings → OkHttp client → state holder), binds the poll loops to the
 * resumed lifecycle, and hands the UI over to [RemoteScreen].
 *
 * The loops live inside `repeatOnLifecycle(RESUMED)` — leaving the app
 * (watch face, screen off) cancels every poll at the scope boundary, and
 * returning relaunches them, so the watch keeps its battery without this
 * module holding a foreground service. A first-run user (no host stored)
 * is opened into [SettingsActivity] once per activity instance — missing
 * config is a settings door, never a crash.
 */
class MainActivity : ComponentActivity() {

    /** Set once the first-run door has opened; backs-out users stay on the screen. */
    private var firstRunDoorOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsStore = WearSettingsStore(applicationContext)
        val controller = WearRemoteController(
            settingsStore = settingsStore,
            client = WearApiClient(settingsStore),
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Re-read the configured gate on every resume: a user coming
                // back from a successful settings save must not bounce again.
                controller.loadSettingsGate()
                if (!controller.state.value.configured && !firstRunDoorOpened) {
                    firstRunDoorOpened = true
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
                controller.start(this)
            }
        }

        setContent {
            MaterialTheme {
                RemoteScreen(
                    controller = controller,
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                )
            }
        }
    }
}
