package com.raulshma.lenscast.streaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half-duplex talkback verdicts: when the shared mic capture drops
 * chunks while the phone speaker plays browser audio. The boundary case is
 * the pin that matters — a clip holds strictly before `untilMs`, so
 * `nowMs == untilMs` is NOT dropped (the old `now < talkUntilMs`
 * semantics), and a Held PTT phase never expires on its own.
 */
class TalkbackPolicyTest {

    // ── Idle: nothing holds the speaker ──

    @Test
    fun `idle passes mic chunks through at any time`() {
        assertFalse(TalkbackPolicy.shouldDropMic(TalkbackPhase.Idle, nowMs = 0L))
        assertFalse(TalkbackPolicy.shouldDropMic(TalkbackPhase.Idle, nowMs = Long.MAX_VALUE))
    }

    // ── Clip: the one-shot hold, expiry is pure time ──

    @Test
    fun `a clip drops chunks strictly before its expiry`() {
        val phase = TalkbackPhase.Clip(untilMs = 1_000L)
        assertTrue(TalkbackPolicy.shouldDropMic(phase, nowMs = 0L))
        assertTrue(TalkbackPolicy.shouldDropMic(phase, nowMs = 999L))
    }

    @Test
    fun `the clip expiry boundary itself is not dropped`() {
        val phase = TalkbackPhase.Clip(untilMs = 1_000L)
        assertFalse(TalkbackPolicy.shouldDropMic(phase, nowMs = 1_000L))
        assertFalse(TalkbackPolicy.shouldDropMic(phase, nowMs = 1_001L))
    }

    // ── Held: the continuous PTT hold, no self-expiry ──

    @Test
    fun `a held PTT stream drops chunks for its whole duration`() {
        assertTrue(TalkbackPolicy.shouldDropMic(TalkbackPhase.Held, nowMs = 0L))
        assertTrue(TalkbackPolicy.shouldDropMic(TalkbackPhase.Held, nowMs = Long.MAX_VALUE))
    }

    // ── the phase transitions the manager drives ──

    @Test
    fun `a clip replaced by a held stream stays dropped past the clip expiry`() {
        // A PTT hold opened during a clip replaces the phase; the Held
        // verdict ignores the superseded clip's untilMs entirely.
        val clip = TalkbackPhase.Clip(untilMs = 1_000L)
        assertTrue(TalkbackPolicy.shouldDropMic(clip, nowMs = 999L))
        assertFalse(TalkbackPolicy.shouldDropMic(clip, nowMs = 1_000L))

        val replacedByHold = TalkbackPhase.Held
        assertTrue(TalkbackPolicy.shouldDropMic(replacedByHold, nowMs = 1_000L))
        assertTrue(TalkbackPolicy.shouldDropMic(replacedByHold, nowMs = 2_000L))
    }

    @Test
    fun `stop returns to idle and the mic un-mutes`() {
        assertTrue(TalkbackPolicy.shouldDropMic(TalkbackPhase.Held, nowMs = 500L))
        assertFalse(TalkbackPolicy.shouldDropMic(TalkbackPhase.Idle, nowMs = 500L))
    }
}
