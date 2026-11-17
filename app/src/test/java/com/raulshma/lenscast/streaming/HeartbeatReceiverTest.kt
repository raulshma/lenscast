package com.raulshma.lenscast.streaming

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The heartbeat delivery trampoline under the framework (the
 * AutomationReceiverRoboTest shape): the real [HeartbeatReceiver.onReceive]
 * on a plain Application — exactly the cold-started-process shape the
 * `as? AppGraph` guard exists for, so the service start must land even
 * without a composition root. The controller's re-arm and the verdict
 * execution are pinned in [com.raulshma.lenscast.core.HeartbeatControllerTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeartbeatReceiverTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the heartbeat alarm starts the streaming service with the heartbeat action`() {
        HeartbeatReceiver().onReceive(
            context,
            Intent(StreamingService.ACTION_HEARTBEAT).setPackage(context.packageName),
        )

        val started = shadowOf(context).nextStartedService
        assertEquals(StreamingService::class.java.name, started.component?.className)
        assertEquals(StreamingService.ACTION_HEARTBEAT, started.action)
    }

    @Test
    fun `a foreign broadcast starts nothing`() {
        HeartbeatReceiver().onReceive(
            context,
            Intent("com.example.NOT_OUR_ALARM"),
        )

        assertNull(shadowOf(context).nextStartedService)
    }
}
