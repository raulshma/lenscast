package com.raulshma.lenscast.streaming

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.raulshma.lenscast.MainApplication
import com.raulshma.lenscast.R
import com.raulshma.lenscast.automation.AutomationReceiver

/**
 * The home-screen widget: one-tap stream start/stop and photo capture without
 * opening the app — the same two operations the Quick Settings tile and the
 * automation receiver expose, fired as broadcasts into [AutomationReceiver]
 * so there is exactly one implementation of each action.
 *
 * State honesty is action-driven: every automation action (widget tap
 * included) ends with a [refresh], which re-renders every widget instance
 * from the manager's live state; [onUpdate] covers widget add and process
 * resurrection. There is no background state polling.
 */
class StreamWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }

    companion object {
        private const val TAG = "StreamWidgetProvider"

        /** Re-renders every widget instance from live state. Safe on cold starts. */
        fun refresh(context: Context) {
            val app = context.applicationContext as? MainApplication ?: return
            val manager = AppWidgetManager.getInstance(context) ?: return
            val streaming = runCatching { app.streamingManager.isLiveStreaming() }
                .onFailure { Log.w(TAG, "Widget state read failed: ${it.message}") }
                .getOrDefault(false)
            val views = android.widget.RemoteViews(context.packageName, R.layout.stream_widget)
            views.setTextViewText(R.id.stream_widget_status, if (streaming) "Streaming" else "Idle")
            views.setTextViewText(
                R.id.stream_widget_toggle,
                if (streaming) "Stop Stream" else "Start Stream",
            )
            views.setOnClickPendingIntent(
                R.id.stream_widget_toggle,
                pendingBroadcast(
                    context,
                    if (streaming) AutomationReceiver.ACTION_STOP_STREAM
                    else AutomationReceiver.ACTION_START_STREAM,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.stream_widget_photo,
                pendingBroadcast(context, AutomationReceiver.ACTION_CAPTURE_PHOTO),
            )
            manager.updateAppWidget(
                ComponentName(context, StreamWidgetProvider::class.java),
                views,
            )
        }

        private fun pendingBroadcast(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                AutomationReceiver.intent(context, action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
