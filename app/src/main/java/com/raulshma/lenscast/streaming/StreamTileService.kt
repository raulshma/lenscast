package com.raulshma.lenscast.streaming

import android.annotation.TargetApi
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.raulshma.lenscast.MainApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile for the streaming server: active while any stream
 * output is live, tap toggles through the one Stream Toggle ladder. The on
 * path starts both outputs when the journal recorded that as the user's
 * desired shape ([BootResumePolicy.tileStart]), else the web output, so a
 * tile click reproduces the session the user last ran.
 *
 * A tile click cold-starts the process, so every access guards against the
 * composition root not being the expected application, and the state read is
 * the manager's live StateFlow value — no separate snapshot kept.
 */
@TargetApi(24)
class StreamTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun app(): MainApplication? = applicationContext as? MainApplication

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val app = app() ?: return
        val tile = qsTile ?: return
        if (app.streamingManager.isLiveStreaming()) {
            runTileAction(tile, "Tile stop failed") {
                app.streamToggle.stopServer()
            }
        } else {
            runTileAction(tile, "Tile start failed") {
                BootResumePolicy.execute(app.streamToggle, BootResumePolicy.tileStart(app.streamStateJournal.load()))
            }
        }
    }

    /** The one tile-click shape: mark the tile unavailable, run the action off-main, re-render. */
    private fun runTileAction(tile: Tile, failureMessage: String, action: suspend () -> Unit) {
        tile.state = Tile.STATE_UNAVAILABLE
        tile.updateTile()
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                Log.w(TAG, failureMessage, e)
            } finally {
                // qsTile is a main-thread surface; only the action runs off-main.
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    updateTile()
                }
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val live = app()?.streamingManager?.isLiveStreaming() == true
        tile.state = if (live) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StreamTileService"
    }
}
