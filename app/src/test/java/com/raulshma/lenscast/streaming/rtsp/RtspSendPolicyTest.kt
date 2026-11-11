package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM pin on the per-client send-queue offer verdict ([RtspSendPolicy]):
 * drop-oldest room-making, the keyframe re-arm, and the load-bearing request
 * cooldown that keeps sustained congestion from driving the encoder
 * all-intra.
 */
class RtspSendPolicyTest {

    private fun offer(
        queueSize: Int,
        capacity: Int = RtspSendPolicy.DEFAULT_CAPACITY_AUS,
        isKeyframeAu: Boolean = false,
        nowMs: Long = 10_000L,
        lastKeyframeRequestMs: Long = -1L,
    ) = RtspSendPolicy.onOffer(queueSize, capacity, isKeyframeAu, nowMs, lastKeyframeRequestMs)

    // ── Room-making: drop oldest, never the incoming AU ──

    @Test
    fun `under and at capacity nothing drops`() {
        for (size in 0..7) {
            val verdict = offer(queueSize = size)
            assertEquals(0, verdict.dropCount)
            assertFalse(verdict.rearmKeyframe)
            assertFalse(verdict.requestKeyframe)
        }
    }

    @Test
    fun `one over capacity drops exactly the oldest au`() {
        val verdict = offer(queueSize = 8)
        assertEquals(1, verdict.dropCount)
    }

    @Test
    fun `far over capacity drops down to the bound in one offer`() {
        val verdict = offer(queueSize = 20)
        assertEquals(13, verdict.dropCount) // 20 + 1 - 8
    }

    @Test
    fun `the incoming au is never the one dropped`() {
        // dropCount may clear the whole queue but never exceeds its occupancy.
        for (size in 0..30) {
            assertTrue(offer(queueSize = size).dropCount <= size)
        }
    }

    @Test
    fun `a keyframe au drops the oldest uniformly like any other`() {
        val verdict = offer(queueSize = 12, isKeyframeAu = true)
        assertEquals(5, verdict.dropCount)
    }

    @Test
    fun `capacity zero or negative clamps to one`() {
        assertEquals(0, offer(queueSize = 0, capacity = 0).dropCount)
        assertEquals(3, offer(queueSize = 3, capacity = 0).dropCount)
        assertEquals(3, offer(queueSize = 3, capacity = -5).dropCount)
    }

    // ── Keyframe re-arm ──

    @Test
    fun `a drop on a p-frame re-arms the gate and requests a sync frame`() {
        val verdict = offer(queueSize = 8, isKeyframeAu = false)
        assertTrue(verdict.rearmKeyframe)
        assertTrue(verdict.requestKeyframe)
    }

    @Test
    fun `a drop on an incoming keyframe does not re-arm past the fresh sync point`() {
        // The incoming keyframe is what the client decodes from — re-arming
        // the gate past it would stall delivery a whole GOP for nothing.
        val verdict = offer(queueSize = 8, isKeyframeAu = true)
        assertTrue(verdict.dropCount > 0)
        assertFalse(verdict.rearmKeyframe)
        assertFalse(verdict.requestKeyframe)
    }

    @Test
    fun `no drop means no re-arm and no request`() {
        val verdict = offer(queueSize = 3, isKeyframeAu = false)
        assertFalse(verdict.rearmKeyframe)
        assertFalse(verdict.requestKeyframe)
    }

    // ── The request cooldown (load-bearing) ──

    @Test
    fun `two drops 10ms apart request only one sync frame`() {
        val first = offer(queueSize = 8, nowMs = 5_000L, lastKeyframeRequestMs = -1L)
        assertTrue(first.requestKeyframe)
        // The caller stamps the request; the second drop lands 10 ms later.
        val second = offer(queueSize = 8, nowMs = 5_010L, lastKeyframeRequestMs = 5_000L)
        assertTrue(second.rearmKeyframe)
        assertFalse(second.requestKeyframe)
    }

    @Test
    fun `the cooldown expires after exactly 1000ms`() {
        val atBoundary = offer(queueSize = 8, nowMs = 6_000L, lastKeyframeRequestMs = 5_000L)
        assertTrue(atBoundary.requestKeyframe)
        val justBefore = offer(queueSize = 8, nowMs = 5_999L, lastKeyframeRequestMs = 5_000L)
        assertFalse(justBefore.requestKeyframe)
    }

    @Test
    fun `the first ever drop requests even at clock zero`() {
        val verdict = offer(queueSize = 8, nowMs = 0L, lastKeyframeRequestMs = -1L)
        assertTrue(verdict.requestKeyframe)
    }

    @Test
    fun `sustained congestion requests at most one sync frame per second`() {
        // A drop every AU (~41 ms at 24 fps) for two seconds: the re-arm
        // fires on every drop, the encoder request only twice.
        var lastRequest = -1L
        var requests = 0
        var now = 0L
        repeat(49) {
            val verdict = offer(queueSize = 8, nowMs = now, lastKeyframeRequestMs = lastRequest)
            assertTrue(verdict.rearmKeyframe)
            if (verdict.requestKeyframe) {
                requests++
                lastRequest = now
            }
            now += 41L
        }
        assertEquals(2, requests) // t=0 and t=1025 (first >= 1000 after 0)
    }
}