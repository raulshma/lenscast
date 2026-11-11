package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM pin on the /sub-stream session twin ([RtspSubStreamState]).
 *
 * The holder is deliberately aggregate-blind: the coupling with the
 * aggregate machine — the demote-safe INIT→READY write on a /sub SETUP and
 * the sub-PLAY-never-touches-the-aggregate-state rule — stays server-side
 * in RtspServer by design, so these tests pin only the holder's own
 * verdicts (SETUP gate, PLAY gate, keyframe wait, channel pair).
 */
class RtspSubStreamStateTest {

    // ── Fresh state ──

    @Test
    fun `fresh state accepts nothing and advertises nothing on the default channels`() {
        val sub = RtspSubStreamState()
        assertFalse(sub.acceptAu(isKeyframeAu = true))
        assertFalse(sub.acceptAu(isKeyframeAu = false))
        assertFalse(sub.advertised)
        assertFalse(sub.isSetup)
        assertFalse(sub.isWanted)
        assertTrue(sub.awaitingKeyframe)
        assertEquals(0, sub.rtpChannel)
        assertEquals(1, sub.rtcpChannel)
    }

    // ── SETUP ──

    @Test
    fun `setup with a channel pair advertises and lands the channels while the play gate stays shut`() {
        val sub = RtspSubStreamState()
        sub.onChannelsNegotiated(rtp = 4, rtcp = 5)
        sub.onSetupAccepted()

        assertTrue(sub.advertised)
        assertTrue(sub.isSetup)
        assertEquals(4, sub.rtpChannel)
        assertEquals(5, sub.rtcpChannel)

        // SETUP alone never opens delivery — the PLAY gate is still shut.
        assertFalse(sub.isWanted)
        assertFalse(sub.acceptAu(isKeyframeAu = true))
    }

    @Test
    fun `setup without explicit channels keeps the default pair`() {
        // A Transport header without a parseable interleaved= pair never
        // calls onChannelsNegotiated — the defaults survive.
        val sub = RtspSubStreamState()
        sub.onSetupAccepted()

        assertTrue(sub.advertised)
        assertEquals(0, sub.rtpChannel)
        assertEquals(1, sub.rtcpChannel)
    }

    // ── PLAY arming and the keyframe gate ──

    @Test
    fun `play arms the stream and re-arms the keyframe wait`() {
        val sub = RtspSubStreamState()
        sub.onSetupAccepted()
        sub.onPlayArmed()

        assertTrue(sub.isWanted)
        assertTrue(sub.awaitingKeyframe)
    }

    @Test
    fun `the gate drops p-frames passes the first keyframe then everything`() {
        val sub = RtspSubStreamState()
        sub.onSetupAccepted()
        sub.onPlayArmed()

        // Mid-GOP join: P-frames reference unseen frames — undecodable filler.
        assertFalse(sub.acceptAu(isKeyframeAu = false))
        assertTrue(sub.awaitingKeyframe)

        // The keyframe clears the wait…
        assertTrue(sub.acceptAu(isKeyframeAu = true))
        assertFalse(sub.awaitingKeyframe)

        // …and everything after flows.
        assertTrue(sub.acceptAu(isKeyframeAu = false))
        assertTrue(sub.acceptAu(isKeyframeAu = true))
    }

    @Test
    fun `a re-play re-arms the wait`() {
        val sub = RtspSubStreamState()
        sub.onSetupAccepted()
        sub.onPlayArmed()
        assertTrue(sub.acceptAu(isKeyframeAu = true)) // gate cleared
        assertFalse(sub.awaitingKeyframe)

        // The next PLAY re-arms: delivery stops until the next keyframe.
        sub.onPlayArmed()
        assertTrue(sub.awaitingKeyframe)
        assertFalse(sub.acceptAu(isKeyframeAu = false))
        assertTrue(sub.acceptAu(isKeyframeAu = true))
        assertTrue(sub.acceptAu(isKeyframeAu = false))
    }

    @Test
    fun `a keyframe while not wanted is dropped and the wait stays armed`() {
        val sub = RtspSubStreamState()
        sub.onSetupAccepted()

        // Not wanted yet: even a keyframe is dropped…
        assertFalse(sub.acceptAu(isKeyframeAu = true))
        assertTrue(sub.awaitingKeyframe)

        // …so the later PLAY still demands a fresh keyframe.
        sub.onPlayArmed()
        assertFalse(sub.acceptAu(isKeyframeAu = false))
        assertTrue(sub.acceptAu(isKeyframeAu = true))
        assertTrue(sub.acceptAu(isKeyframeAu = false))
    }

    // ── TEARDOWN ──

    @Test
    fun `teardown mid-await drops everything and keeps the wait armed`() {
        val sub = RtspSubStreamState()
        sub.onChannelsNegotiated(rtp = 6, rtcp = 7)
        sub.onSetupAccepted()
        sub.onPlayArmed() // mid-await: no AU accepted yet

        sub.onTeardown()

        assertFalse(sub.isWanted)
        assertFalse(sub.isSetup)
        assertFalse(sub.advertised)
        assertTrue(sub.awaitingKeyframe)
    }

    @Test
    fun `setup and play after teardown work like a fresh session`() {
        val sub = RtspSubStreamState()
        sub.onSetupAccepted()
        sub.onPlayArmed()
        sub.onTeardown()

        // The client re-SETUPs and re-PLAYs on new channels.
        sub.onChannelsNegotiated(rtp = 6, rtcp = 7)
        sub.onSetupAccepted()
        sub.onPlayArmed()

        assertTrue(sub.advertised)
        assertTrue(sub.isWanted)
        assertEquals(6, sub.rtpChannel)
        assertEquals(7, sub.rtcpChannel)
        assertTrue(sub.awaitingKeyframe)
        assertFalse(sub.acceptAu(isKeyframeAu = false))
        assertTrue(sub.acceptAu(isKeyframeAu = true))
        assertTrue(sub.acceptAu(isKeyframeAu = false))
    }

    // ── Aggregate blindness ──

    @Test
    fun `a sub setup during playing touches nothing but the setup gate`() {
        // The holder carries no aggregate state — the demote-safe INIT→READY
        // write and the PLAYING guard live in RtspServer. A re-SETUP while
        // the sub plays may only refresh the SETUP gate and channels.
        val sub = RtspSubStreamState()
        sub.onSetupAccepted()
        sub.onPlayArmed()
        assertTrue(sub.acceptAu(isKeyframeAu = true)) // gate cleared, flowing

        sub.onChannelsNegotiated(rtp = 8, rtcp = 9)
        sub.onSetupAccepted()

        assertTrue(sub.isSetup)
        assertTrue(sub.isWanted) // the PLAY gate is SETUP's to drop, not to open
        assertFalse(sub.awaitingKeyframe) // the keyframe gate is untouched
        assertEquals(8, sub.rtpChannel)
        assertEquals(9, sub.rtcpChannel)
        assertTrue(sub.acceptAu(isKeyframeAu = false)) // delivery continues
    }
}
