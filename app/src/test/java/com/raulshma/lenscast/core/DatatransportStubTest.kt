package com.raulshma.lenscast.core

import android.content.Context
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Proves the prebuilt MediaPipe tasks-core AAR links and runs against the
 * no-op datatransport stubs (com.google.android.datatransport in main
 * sources) with the real transport jars excluded from every configuration.
 *
 * This is the exact path TaskRunner.create walks on every detector/classifier
 * creation: TasksStatsLoggerFactory.create unconditionally builds a
 * TasksStatsProtoLogger whose RemoteLoggingClient constructor calls
 * TransportRuntime.initialize → getInstance → newFactory(CCTDestination.
 * INSTANCE) → TransportFactory.getTransport(...). With the jars excluded, all
 * of those resolve to the stubs; if a stub signature ever drifts from what
 * tasks-core references (e.g. after a mediaPipe version bump), this test
 * fails with a linkage error instead of the detection gate crashing on a
 * user's device. The log* calls drive RemoteLoggingClient.logEvent → the
 * stubbed Transport.send, which must silently drop the event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatatransportStubTest {

    @Test
    fun `stats logger factory builds and logs through the stubs without the transport jars`() {
        val context: Context = RuntimeEnvironment.getApplication()

        val logger = com.google.mediapipe.tasks.core.logging.TasksStatsLoggerFactory.create(
            context,
            "test_task",
            "test_mode",
        )
        assertNotNull(logger)

        // Session start/end cross RemoteLoggingClient.logEvent — the stubbed
        // Transport.send path. None of these may throw or hit the network.
        logger.logSessionStart()
        logger.logSessionEnd()
    }
}
