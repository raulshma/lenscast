package com.raulshma.lenscast

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.raulshma.lenscast.navigation.NavigationGraph
import com.raulshma.lenscast.ui.theme.LensCastTheme
import com.raulshma.lenscast.update.UpdateNotifier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class MainActivity : ComponentActivity() {

    // Deep-link destination events, collected by the NavigationGraph. Policy:
    // extraBufferCapacity 8 with DROP_OLDEST — a burst of intents arriving
    // before (or faster than) collection can never wedge `tryEmit` (the
    // old capacity-1 flow silently dropped a second event when the buffer
    // was full), and when the buffer does fill the oldest uncollected
    // destination is dropped first, so the freshest intent always wins.
    private val _navigationEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigationEvents: SharedFlow<String> = _navigationEvents

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as AppGraph
        app.cameraService.setLifecycleOwner(this)

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    app.cameraService.onActivityResume()
                    // Retry a foreground promotion Android may have denied
                    // while the app was backgrounded.
                    app.ensureServerForeground()
                }
                Lifecycle.Event.ON_STOP -> app.cameraService.onActivityStop()
                else -> {}
            }
        })

        handleNavigationIntent(intent)

        setContent {
            LensCastTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavigationGraph()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        intent?.getStringExtra(UpdateNotifier.EXTRA_NAVIGATE_TO)?.let { destination ->
            intent.removeExtra(UpdateNotifier.EXTRA_NAVIGATE_TO)
            _navigationEvents.tryEmit(destination)
        }
    }
}
