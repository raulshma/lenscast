package com.raulshma.lenscast.streaming

import android.app.Application
import android.app.Notification
import android.content.Intent
import com.raulshma.lenscast.core.ForegroundNotifications
import com.raulshma.lenscast.core.ReliabilityRelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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
        // landing a sticky restart takes. This is ALSO the relay-less
        // degradation pin: with the plain harness application the narrow
        // cast fails, the evaluation half is delivered nowhere, and the
        // promotion still lands (its verdict execution is pinned in
        // HeartbeatControllerTest; the reachable-through-the-relay case in
        // the tests below).
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

    // ── the relay routing (the narrow cast's whole point) ──
    //
    // The service used to cast `as? AppGraph` five times; under this
    // harness's plain application every cast failed and whole branches
    // silently no-op'd — the heartbeat's evaluation half was documented as
    // unreachable from this suite. The four-member [ReliabilityRelay] is
    // implementable by a test application, so the branches are drivable.

    /** The relay-recording harness application, wired per-test via @Config(application = ...). */
    class RelayHarnessApplication : Application(), ReliabilityRelay {
        val heartbeatDeliveries = AtomicInteger(0)
        val deathRecoveryLandings = CopyOnWriteArrayList<Boolean>()
        val deniedPromotions = AtomicInteger(0)
        val swipeAways = AtomicInteger(0)

        override fun onHeartbeatDelivered() {
            heartbeatDeliveries.incrementAndGet()
        }

        override fun onDeathRecoveryLanded(rearmPersistedOutputs: Boolean) {
            deathRecoveryLandings += rearmPersistedOutputs
        }

        override fun onStreamingPromotionDenied() {
            deniedPromotions.incrementAndGet()
        }

        override fun onUserSwipedAway() {
            swipeAways.incrementAndGet()
        }
    }

    private fun harness(): RelayHarnessApplication =
        RuntimeEnvironment.getApplication() as RelayHarnessApplication

    @Test
    @Config(application = RelayHarnessApplication::class, sdk = [34])
    fun `the heartbeat's evaluation half runs through the relay`() {
        // The previously-unreachable branch: under the old fat `as? AppGraph`
        // cast this harness short-circuited the evaluation half silently.
        // Through the relay it is delivered — AFTER the promotion the
        // FGS-start contract demands (the alarm fired into a possibly-dead
        // process; the promotion must land first).
        val service = Robolectric.buildService(StreamingService::class.java).create().get()
        val app = harness()
        val intent = Intent(service, StreamingService::class.java).apply {
            action = StreamingService.ACTION_HEARTBEAT
        }

        val result = service.onStartCommand(intent, 0, 1)

        assertEquals(android.app.Service.START_STICKY, result)
        assertEquals("the evaluation half is delivered once per fire", 1, app.heartbeatDeliveries.get())
        assertEquals(
            "the promotion's aftermath lands (the FSI evaluation) but carries no re-arm",
            listOf(false),
            app.deathRecoveryLandings,
        )
        val shadow = shadowOf(service)
        assertEquals(
            "Camera server active",
            shadow.lastForegroundNotification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString(),
        )
    }

    @Test
    @Config(application = RelayHarnessApplication::class, sdk = [34])
    fun `the heartbeat mid-stream skips the promotion but still delivers the evaluation`() {
        // The streaming guard routes BOTH the promotion and the server-only
        // aftermath away — while the evaluation half (read-only + tier
        // escalation) still rides the relay.
        val service = Robolectric.buildService(StreamingService::class.java).create().get()
        val app = harness()
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

        assertEquals(1, app.heartbeatDeliveries.get())
        assertTrue(
            "the streaming guard must skip the server-only death-recovery aftermath",
            app.deathRecoveryLandings.isEmpty(),
        )
        val shadow = shadowOf(service)
        assertEquals(
            "Streaming to http://192.168.1.10:8080/stream",
            shadow.lastForegroundNotification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString(),
        )
    }

    @Test
    @Config(application = RelayHarnessApplication::class, sdk = [34])
    fun `the relaunch recovery carries the re-arm and a plain server start does not`() {
        // The App-Restart promise is the relaunch/sticky-restart branch's
        // alone: ACTION_RELAUNCH lands the death-recovery aftermath WITH the
        // persisted-output re-arm, ACTION_SERVER the same aftermath WITHOUT
        // it — the routing fact only the intent branch knows.
        val service = Robolectric.buildService(StreamingService::class.java).create().get()
        val app = harness()

        service.onStartCommand(
            Intent(service, StreamingService::class.java).apply {
                action = StreamingService.ACTION_RELAUNCH
            },
            0,
            1,
        )

        assertEquals(listOf(true), app.deathRecoveryLandings)

        service.onStartCommand(
            Intent(service, StreamingService::class.java).apply {
                action = StreamingService.ACTION_SERVER
            },
            0,
            2,
        )

        assertEquals(listOf(true, false), app.deathRecoveryLandings)
    }

    @Test
    @Config(application = RelayHarnessApplication::class, sdk = [34])
    fun `a task removal records the swipe-kill through the relay`() {
        val service = Robolectric.buildService(StreamingService::class.java).create().get()
        val app = harness()

        service.onTaskRemoved(Intent("com.raulshma.lenscast.REMOVED"))

        assertEquals(1, app.swipeAways.get())
    }
}
