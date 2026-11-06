package com.raulshma.lenscast.core

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The keep-awake hold's collector logic over a mockk [PowerManager] seam
 * (plain JVM — [android.util.Log] rides the unit-test default-values mode):
 * the disk-first start read wins over the eager flow's stale default, and
 * the hold tracks the setting's every transition.
 */
class KeepAwakeControllerTest {

    private val powerManager: PowerManager = mockk(relaxed = true)

    private fun awaitUntil(message: String = "condition never became true", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { message }
            Thread.sleep(10)
        }
    }

    private fun controller(
        diskValue: Boolean,
        flow: MutableStateFlow<Boolean>,
        scope: CoroutineScope,
    ) = KeepAwakeController(
        keepAwakeEnabledFromDisk = { diskValue },
        keepAwakeEnabled = flow,
        powerManager = powerManager,
        scope = scope,
    )

    @Test
    fun `start holds when the disk reads on`() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            controller(diskValue = true, flow = MutableStateFlow(true), scope = scope).start()

            awaitUntil { runCatching { verify(exactly = 1) { powerManager.acquireKeepAwake() } }.isSuccess }
            verify(exactly = 0) { powerManager.releaseKeepAwake() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `start with the disk off never holds, not even on the flow's stale default`() {
        // The eager shared flow still serves its on-by-default value while the
        // startup disk read runs — the collector must neither acquire on it
        // nor treat the later truth as a change.
        val flow = MutableStateFlow(true)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            controller(diskValue = false, flow = flow, scope = scope).start()
            Thread.sleep(200)
            verify(exactly = 0) { powerManager.acquireKeepAwake() }

            // The disk truth lands — still no transition, holding is already false.
            flow.value = false
            Thread.sleep(200)
            verify(exactly = 0) { powerManager.acquireKeepAwake() }
            verify(exactly = 0) { powerManager.releaseKeepAwake() }

            // The user enables: the hold starts.
            flow.value = true
            awaitUntil {
                runCatching { verify(exactly = 1) { powerManager.acquireKeepAwake() } }.isSuccess
            }
            verify(exactly = 0) { powerManager.releaseKeepAwake() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `toggling off releases and toggling back on re-acquires`() {
        val flow = MutableStateFlow(true)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            controller(diskValue = true, flow = flow, scope = scope).start()
            awaitUntil {
                runCatching { verify(exactly = 1) { powerManager.acquireKeepAwake() } }.isSuccess
            }

            flow.value = false
            awaitUntil {
                runCatching { verify(exactly = 1) { powerManager.releaseKeepAwake() } }.isSuccess
            }

            flow.value = true
            awaitUntil {
                runCatching { verify(exactly = 2) { powerManager.acquireKeepAwake() } }.isSuccess
            }

            // A repeat of the held state is not a second release: StateFlow
            // conflates, but an explicit same-value emission must stay inert.
            flow.value = true
            Thread.sleep(200)
            verify(exactly = 1) { powerManager.releaseKeepAwake() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `start is idempotent - a second call adds no collector`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val controller = controller(diskValue = true, flow = MutableStateFlow(true), scope = scope)
            controller.start()
            awaitUntil {
                runCatching { verify(exactly = 1) { powerManager.acquireKeepAwake() } }.isSuccess
            }
            controller.start()
            Thread.sleep(200)
            verify(exactly = 1) { powerManager.acquireKeepAwake() }
        } finally {
            scope.cancel()
        }
    }
}
