package com.raulshma.lenscast.streaming

import android.app.Notification
import android.content.Intent
import com.raulshma.lenscast.core.ForegroundNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The runtime service's two modes share one notification slot. The session
 * tests pin which intent is sent; these pin what the service does with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingServiceTest {

    @Test
    fun `server action posts the server notification`() {
        val service = Robolectric.buildService(StreamingService::class.java).create().get()
        val intent = Intent(service, StreamingService::class.java).apply {
            action = StreamingService.ACTION_SERVER
            putExtra(StreamingService.EXTRA_URL, "http://192.168.1.10:8080/stream")
        }

        val result = service.onStartCommand(intent, 0, 1)

        assertEquals(android.app.Service.START_STICKY, result)
        val shadow = shadowOf(service)
        assertEquals(
            ForegroundNotifications.RUNTIME_NOTIFICATION_ID,
            shadow.lastForegroundNotificationId,
        )
        assertEquals(
            "Camera server active at http://192.168.1.10:8080/stream",
            shadow.lastForegroundNotification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString(),
        )
        assertFalse(shadow.isForegroundStopped)
    }

    @Test
    fun `stream action keeps the streaming notification`() {
        val service = Robolectric.buildService(StreamingService::class.java).create().get()
        val intent = Intent(service, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_URL, "http://192.168.1.10:8080/stream")
        }

        service.onStartCommand(intent, 0, 1)

        val shadow = shadowOf(service)
        assertEquals(
            "Streaming to http://192.168.1.10:8080/stream",
            shadow.lastForegroundNotification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString(),
        )
        assertFalse(shadow.isForegroundStopped)
    }

    @Test
    fun `a sticky restart without an intent falls back to server mode`() {
        val service = Robolectric.buildService(StreamingService::class.java).create().get()

        service.onStartCommand(null, 0, 1)

        val shadow = shadowOf(service)
        assertEquals(
            "Camera server active",
            shadow.lastForegroundNotification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString(),
        )
    }

    @Test
    fun `the heartbeat alarm lands the server-only promotion`() {
        // The heartbeat's delivery: the FGS start (which recreated the
        // process if it was dead) must land a foreground promotion or the
        // start timeout kills the service — the same server-only recovery
        // landing a sticky restart takes. The evaluation half runs on the
        // graph's HeartbeatController, unreachable from this cold-started
        // application (the `as? AppGraph` guard) — its verdict execution is
        // pinned in HeartbeatControllerTest.
        val service = Robolectric.buildService(StreamingService::class.java).create().get()
        val intent = Intent(service, StreamingService::class.java).apply {
            action = StreamingService.ACTION_HEARTBEAT
        }

        val result = service.onStartCommand(intent, 0, 1)

        assertEquals(android.app.Service.START_STICKY, result)
        val shadow = shadowOf(service)
        assertEquals(
            "Camera server active",
            shadow.lastForegroundNotification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString(),
        )
    }

    @Test
    fun `the heartbeat while a streaming promotion is live never re-types the foreground`() {
        // Fix A: the CAMERA/MICROPHONE promotion is the live stream's
        // while-in-use camera standing. A 15-minute heartbeat that
        // re-promoted server-only would re-type the FGS on the shared
        // notification id and sever camera access on Android 14+ — so
        // mid-stream the heartbeat skips the promotion entirely and goes
        // straight to the controller's (read-only) health evaluation. The
        // streaming notification must still be the LAST one standing.
        val service = Robolectric.buildService(StreamingService::class.java).create().get()
        service.onStartCommand(
            Intent(service, StreamingService::class.java).apply {
                action = StreamingService.ACTION_START
                putExtra(StreamingService.EXTRA_URL, "http://192.168.1.10:8080/stream")
            },
            0,
            1,
        )

        service.onStartCommand(
            Intent(service, StreamingService::class.java).apply {
                action = StreamingService.ACTION_HEARTBEAT
            },
            0,
            2,
        )

        val shadow = shadowOf(service)
        assertEquals(
            "The heartbeat must not replace the streaming notification with the server one",
            "Streaming to http://192.168.1.10:8080/stream",
            shadow.lastForegroundNotification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString(),
        )
        assertEquals(ForegroundNotifications.RUNTIME_NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertFalse(shadow.isForegroundStopped)
    }
}
