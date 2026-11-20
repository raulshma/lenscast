package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The increment-then-decide convention's owner, pinned: [MissCounter.recordFailure]
 * advances before the policy asks and the returned count includes the event
 * just judged; [MissCounter.reset] zeroes on success. The two policy call
 * sites (StreamWatchdog's tick loop, HeartbeatController's fired alarm) trust
 * these semantics — WatchdogPolicyTest and WatchdogAlarmPolicyTest pin the
 * decisions on top of them unchanged.
 */
class MissCounterTest {

    @Test
    fun `recordFailure includes the current event in the returned count`() {
        // The convention the policies document: miss 1 is the FIRST tier's
        // input, not a zeroth look — the count comes back already advanced.
        val counter = MissCounter()
        assertEquals(0, counter.current)
        assertEquals(1, counter.recordFailure())
        assertEquals(2, counter.recordFailure())
        assertEquals(2, counter.current)
    }

    @Test
    fun `reset zeroes the run and the next failure starts at one`() {
        // The healthy-event case: the policy's RESET verdict zeroes the
        // bookkeeping and the ladder starts from the top on the next miss.
        val counter = MissCounter()
        counter.recordFailure()
        counter.recordFailure()
        counter.reset()
        assertEquals(0, counter.current)
        assertEquals(1, counter.recordFailure())
    }

    @Test
    fun `consecutive runs stay independent across a reset`() {
        val counter = MissCounter()
        counter.recordFailure()
        counter.reset()
        counter.recordFailure()
        counter.recordFailure()
        assertEquals(2, counter.current)
    }

    @Test
    fun `a fresh counter reads zero`() {
        assertEquals(0, MissCounter().current)
    }
}
