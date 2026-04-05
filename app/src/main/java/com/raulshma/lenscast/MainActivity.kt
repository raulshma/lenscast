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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class MainActivity : ComponentActivity() {

    private val _navigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<String> = _navigationEvents

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as MainApplication
        app.cameraService.setLifecycleOwner(this)

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> app.cameraService.onActivityResume()
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
