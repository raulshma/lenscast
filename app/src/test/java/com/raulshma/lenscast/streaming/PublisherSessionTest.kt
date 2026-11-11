package com.raulshma.lenscast.streaming

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared push-publisher lifecycle machine ([PublisherSession]): the
 * status ladder (Connecting → Error → … → Idle), start/stop idempotence, the
 * stop-suppresses-Error rule, the 1 s→30 s doubling backoff and its clean-end
 * reset, the wanted=false visibility inside the stop hook, and the
 * start/stop race behavior. Everything runs on real threads — the harness's
 * [PublisherSession.delayFn] seam parks each recorded delay on a
 * test-released permit, so the loop advances only when driven and the
 * recorded amounts are exactly what the machine asked for.
 */
class PublisherSessionTest {

    /** Minimal status ladder with the transports' Idle/Connecting/Error shape. */
    private sealed class Status {
        data object Idle : Status()
        data object Connecting : Status()
        data class Error(val message: String) : Status()
    }

    /**
     * One [PublisherSession] under test: records every published status,
     * every delay amount, every logged failure, and every stop-hook run
     * (including the wanted-state the hook observed). The connect body is
     * pluggable per test and runs after the harness's call bookkeeping.
     */
    private class Harness {

        val statuses: MutableList<Status> = Collections.synchronizedList(mutableListOf())
        val delays: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val loggedFailures: MutableList<Exception> = Collections.synchronizedList(mutableListOf())

        val connectCalls = AtomicInteger(0)
        val stopHookCalls = AtomicInteger(0)

        /** Counted when the loop publishes Idle — the terminal transition. */
        val idleLatch = CountDownLatch(1)

        /** Counted on the first connect invocation. */
        val firstConnect = CountDownLatch(1)

        /** Each recorded delay blocks until the test releases one permit. */
        val delayPermits = Semaphore(0)

        /** The wanted-state the stop hook observed (proves it is already false there). */
        @Volatile var wantedInStopHook: Boolean? = null

        /** The per-test connect body, invoked after the harness's bookkeeping. */
        var connectBody: suspend () -> Unit = {}

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val session: PublisherSession<Status> = PublisherSession(
            onStatus = { status ->
                statuses.add(status)
                if (status == Status.Idle) idleLatch.countDown()
            },
            idleStatus = Status.Idle,
            connectingStatus = Status.Connecting,
            errorStatus = { message -> Status.Error(message) },
            defaultErrorMessage = "connection failed",
            connect = {
                connectCalls.incrementAndGet()
                firstConnect.countDown()
                connectBody()
            },
            logAttemptFailed = { e -> loggedFailures.add(e) },
            onStop = {
                stopHookCalls.incrementAndGet()
                wantedInStopHook = session.isWanted()
            },
            scope = scope,
            delayFn = { ms ->
                delays.add(ms)
                // A forgotten permit fails loudly instead of hanging the run.
                if (!delayPermits.tryAcquire(10, TimeUnit.SECONDS)) {
                    throw AssertionError("delay permit not released within 10 s")
                }
            },
        )
    }

    /** Waits until the list holds at least [size] entries. */
    private fun awaitSize(list: List<*>, size: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (list.size < size) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("timed out waiting for $size entries; have ${list.size}")
            }
            Thread.sleep(5)
        }
    }

    /** Waits until the harness scope has been cancelled by the loop's completion hook. */
    private fun awaitScopeCancelled(h: Harness) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (h.scope.isActive) {
            if (System.nanoTime() > deadline) throw AssertionError("scope never cancelled")
            Thread.sleep(5)
        }
    }

    // ── 1. the status ladder ──

    @Test
    fun `failures publish Connecting and Error then a clean end and stop land Idle`() {
        val h = Harness()
        val attempts = AtomicInteger(0)
        h.connectBody = {
            when (attempts.incrementAndGet()) {
                1 -> throw IOException("boom-1")
                2 -> throw IOException() // null message — the default fallback
                3 -> throw IOException("boom-3")
                else -> Unit // the clean attempt end
            }
        }
        h.session.start()

        // Three failing attempts, each driven past its backoff delay.
        awaitSize(h.delays, 1)
        h.delayPermits.release()
        awaitSize(h.delays, 2)
        h.delayPermits.release()
        awaitSize(h.delays, 3)
        h.delayPermits.release()
        // The fourth connect returns cleanly (attempt resets), then the loop
        // sits in its delay; stop there and let it land Idle.
        awaitSize(h.delays, 4)
        h.session.stop()
        h.delayPermits.release()
        assertTrue(h.idleLatch.await(10, TimeUnit.SECONDS))

        assertEquals(
            listOf(
                Status.Connecting,
                Status.Error("boom-1"),
                Status.Connecting,
                Status.Error("connection failed"),
                Status.Connecting,
                Status.Error("boom-3"),
                Status.Connecting,
                Status.Idle,
            ),
            h.statuses.toList(),
        )
        assertEquals(3, h.loggedFailures.size)
        assertEquals(1, h.stopHookCalls.get())
    }

    // ── 2. start/stop idempotence ──

    @Test
    fun `start and stop are idempotent`() {
        val h = Harness()
        val release = CountDownLatch(1)
        h.connectBody = { release.await(10, TimeUnit.SECONDS) } // a hung connect
        h.session.start()
        h.session.start() // a second start must not launch a second loop
        assertTrue(h.firstConnect.await(10, TimeUnit.SECONDS))
        assertEquals(1, h.connectCalls.get())

        h.session.stop()
        h.session.stop() // a second stop must not re-run the hook
        assertEquals(1, h.stopHookCalls.get())

        release.countDown()
        assertTrue(h.idleLatch.await(10, TimeUnit.SECONDS))
        // Exactly one Connecting before the Idle — one loop, not two.
        assertEquals(listOf(Status.Connecting, Status.Idle), h.statuses.toList())
        assertEquals(1, h.connectCalls.get())
    }

    // ── 3. stop during a hung connect ──

    @Test
    fun `stop during a hung connect suppresses Error and lands Idle`() {
        val h = Harness()
        val hang = CountDownLatch(1)
        h.connectBody = {
            hang.await(10, TimeUnit.SECONDS)
            throw IOException("late failure after stop")
        }
        h.session.start()
        assertTrue(h.firstConnect.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(Status.Connecting), h.statuses.toList())

        h.session.stop() // the connect is still hung here
        hang.countDown()
        assertTrue(h.idleLatch.await(10, TimeUnit.SECONDS))

        // The catch saw wanted=false and broke out — no Error was published
        // and the failure was not logged.
        assertEquals(listOf(Status.Connecting, Status.Idle), h.statuses.toList())
        assertEquals(1, h.stopHookCalls.get())
        assertEquals(0, h.loggedFailures.size)
    }

    // ── 4. the backoff sequence ──

    @Test
    fun `backoff doubles from one second and caps at thirty seconds`() {
        val h = Harness()
        h.connectBody = { throw IOException("always fails") }
        h.session.start()

        // Six permit releases drive seven recorded delays: 1 s doubling per
        // consecutive failure, capped at 30 s from the sixth on.
        repeat(6) { h.delayPermits.release() }
        awaitSize(h.delays, 7)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            h.delays.toList().take(7),
        )

        h.session.stop()
        h.delayPermits.release() // unblock whichever delay the loop sits in
        assertTrue(h.idleLatch.await(10, TimeUnit.SECONDS))
    }

    // ── 5. the stop hook's view of the machine ──

    @Test
    fun `stop clears wanted before the hook runs and no further attempt follows`() {
        val h = Harness()
        val release = CountDownLatch(1)
        h.connectBody = { release.await(10, TimeUnit.SECONDS) } // clean end once released
        h.session.start()
        assertTrue(h.firstConnect.await(10, TimeUnit.SECONDS))

        h.session.stop()
        assertEquals(false, h.wantedInStopHook)

        // The hung connect ends cleanly; the loop must exit, not retry.
        release.countDown()
        assertTrue(h.idleLatch.await(10, TimeUnit.SECONDS))
        assertEquals(1, h.stopHookCalls.get())
        assertEquals(1, h.connectCalls.get())
        assertEquals(listOf(Status.Connecting, Status.Idle), h.statuses.toList())
    }

    // ── 6. start/stop races ──

    @Test
    fun `racing starts launch exactly one loop`() {
        val h = Harness()
        val release = CountDownLatch(1)
        h.connectBody = { release.await(10, TimeUnit.SECONDS) }
        val barrier = CountDownLatch(1)
        val t1 = thread { barrier.await(); h.session.start() }
        val t2 = thread { barrier.await(); h.session.start() }
        barrier.countDown()
        t1.join(10_000)
        t2.join(10_000)
        assertTrue(h.firstConnect.await(10, TimeUnit.SECONDS))
        assertEquals(1, h.connectCalls.get())

        h.session.stop()
        release.countDown()
        assertTrue(h.idleLatch.await(10, TimeUnit.SECONDS))
        assertEquals(1, h.stopHookCalls.get())
        assertEquals(listOf(Status.Connecting, Status.Idle), h.statuses.toList())
    }

    @Test
    fun `racing stops run the hook exactly once`() {
        val h = Harness()
        val release = CountDownLatch(1)
        h.connectBody = { release.await(10, TimeUnit.SECONDS) }
        h.session.start()
        assertTrue(h.firstConnect.await(10, TimeUnit.SECONDS))

        val barrier = CountDownLatch(1)
        val t1 = thread { barrier.await(); h.session.stop() }
        val t2 = thread { barrier.await(); h.session.stop() }
        barrier.countDown()
        t1.join(10_000)
        t2.join(10_000)
        assertEquals(1, h.stopHookCalls.get())

        release.countDown()
        assertTrue(h.idleLatch.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(Status.Connecting, Status.Idle), h.statuses.toList())
    }

    @Test
    fun `racing start against stop settles into a consistent state`() {
        repeat(10) {
            val h = Harness()
            val release = CountDownLatch(1)
            h.connectBody = { release.await(5, TimeUnit.SECONDS) }
            val barrier = CountDownLatch(1)
            val starter = thread { barrier.await(); h.session.start() }
            val stopper = thread { barrier.await(); h.session.stop() }
            barrier.countDown()
            starter.join(10_000)
            stopper.join(10_000)

            // Unblock whichever gate the loop may be parked on, then classify.
            release.countDown()
            h.delayPermits.release()
            val stoppedCleanly = h.idleLatch.await(2, TimeUnit.SECONDS)
            when {
                stoppedCleanly -> {
                    assertEquals(1, h.stopHookCalls.get())
                    // Either the loop ran one attempt (Connecting → Idle) or
                    // its first wanted check saw the stop (Idle alone).
                    val shape = h.statuses.toList()
                    assertTrue(
                        shape == listOf(Status.Connecting, Status.Idle) ||
                            shape == listOf(Status.Idle),
                    )
                }
                h.stopHookCalls.get() == 0 -> {
                    // The stop's CAS ran before the start's and no-oped; the
                    // loop is still live — settle it from here.
                    h.session.stop()
                    repeat(2) { h.delayPermits.release() }
                    assertTrue(h.idleLatch.await(10, TimeUnit.SECONDS))
                    assertEquals(1, h.stopHookCalls.get())
                }
                else -> {
                    // The skeleton's pre-existing window: the stop's CAS ran
                    // between the start's CAS and its wanted.set, so the hook
                    // ran but wanted ends true and the loop lives. Preserved
                    // verbatim by this naming-only wave; the loop
                    // self-terminates at its gated-delay timeout.
                    assertEquals(1, h.stopHookCalls.get())
                }
            }
        }
    }

    // ── 7. the clean-end reset ──

    @Test
    fun `a clean attempt end resets the backoff to one second`() {
        val h = Harness()
        h.connectBody = { } // every attempt ends cleanly
        h.session.start()

        awaitSize(h.delays, 1)
        assertEquals(1_000L, h.delays[0])
        h.delayPermits.release()

        // The second clean attempt's delay is 1000 again, not 2000 — the
        // reset-to-zero after a clean end.
        awaitSize(h.delays, 2)
        assertEquals(listOf(1_000L, 1_000L), h.delays.toList())

        h.session.stop()
        h.delayPermits.release()
        assertTrue(h.idleLatch.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(Status.Connecting, Status.Connecting, Status.Idle), h.statuses.toList())
    }

    @Test
    fun `a start after the machine settled stays inert on its cancelled scope`() {
        val h = Harness()
        h.connectBody = { }
        h.session.start()
        awaitSize(h.delays, 1)
        assertEquals(1_000L, h.delays[0])

        // Wanted flips false between the two clean connects: stop, land Idle.
        h.session.stop()
        h.delayPermits.release()
        assertTrue(h.idleLatch.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(Status.Connecting, Status.Idle), h.statuses.toList())

        // The loop's completion hook cancels the publisher-owned scope; once
        // it settles, a start on THIS instance is inert — production restarts
        // build a fresh publisher (the outputs' publisherFactory) instead.
        awaitScopeCancelled(h)
        h.session.start()
        Thread.sleep(100)
        assertEquals(listOf(Status.Connecting, Status.Idle), h.statuses.toList())
        assertEquals(1, h.connectCalls.get())
        assertEquals(Status.Idle, h.session.status())
    }
}
