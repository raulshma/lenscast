package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspConfigDiffTest {

    private val base = RtspConfig()

    private fun changed(vararg fields: RtspField): Set<RtspField> = fields.toSet()

    @Test
    fun `identical configs diff to the empty set and never need a restart`() {
        assertEquals(emptySet<RtspField>(), RtspConfigDiff.of(base, base.copy()))
        assertFalse(RtspConfigDiff.needsRestart(RtspConfigDiff.of(base, base.copy())))
    }

    @Test
    fun `every field is reported when it changes`() {
        val new = base.copy(
            videoWidth = 1920,
            videoHeight = 1080,
            videoBitrate = 3_000_000,
            videoFrameRate = 30,
            inputFormat = RtspInputFormat.I420,
            audioEnabled = true,
            audioSampleRateHz = 44_100,
            audioChannelCount = 2,
            audioBitrateKbps = 96,
            auth = RtspAuthSpec("user", "hash", "ha1"),
        )
        assertEquals(
            changed(
                RtspField.VIDEO_WIDTH,
                RtspField.VIDEO_HEIGHT,
                RtspField.VIDEO_BITRATE,
                RtspField.VIDEO_FRAME_RATE,
                RtspField.INPUT_FORMAT,
                RtspField.AUDIO_ENABLED,
                RtspField.AUDIO_SAMPLE_RATE_HZ,
                RtspField.AUDIO_CHANNEL_COUNT,
                RtspField.AUDIO_BITRATE_KBPS,
                RtspField.AUTH,
            ),
            RtspConfigDiff.of(base, new),
        )
    }

    @Test
    fun `video bitrate hot-swaps - the hub applies it via live setParameters`() {
        val diff = RtspConfigDiff.of(base, base.copy(videoBitrate = 4_000_000))
        assertEquals(changed(RtspField.VIDEO_BITRATE), diff)
        assertFalse(RtspConfigDiff.needsRestart(diff))
    }

    @Test
    fun `audio bitrate needs a restart - the AAC encoder only applies it on its next start`() {
        val diff = RtspConfigDiff.of(base, base.copy(audioBitrateKbps = 64))
        assertEquals(changed(RtspField.AUDIO_BITRATE_KBPS), diff)
        assertTrue(RtspConfigDiff.needsRestart(diff))
    }

    @Test
    fun `structural audio changes need a restart`() {
        for (diff in listOf(
            RtspConfigDiff.of(base, base.copy(audioEnabled = true)),
            RtspConfigDiff.of(base, base.copy(audioSampleRateHz = 16_000)),
            RtspConfigDiff.of(base, base.copy(audioChannelCount = 2)),
        )) {
            assertTrue("expected $diff to need a restart", RtspConfigDiff.needsRestart(diff))
        }
    }

    @Test
    fun `frame rate is a hot swap - preserving today's no-restart behavior`() {
        val diff = RtspConfigDiff.of(base, base.copy(videoFrameRate = 30))
        assertEquals(changed(RtspField.VIDEO_FRAME_RATE), diff)
        assertFalse(RtspConfigDiff.needsRestart(diff))
    }

    @Test
    fun `input format and auth are hot swaps`() {
        assertFalse(
            RtspConfigDiff.needsRestart(
                RtspConfigDiff.of(base, base.copy(inputFormat = RtspInputFormat.NV21))
            )
        )
        assertFalse(
            RtspConfigDiff.needsRestart(
                RtspConfigDiff.of(base, base.copy(auth = RtspAuthSpec("u", "p", "h")))
            )
        )
    }

    @Test
    fun `one needs-restart field is enough to force the restart verdict`() {
        val diff = RtspConfigDiff.of(
            base,
            base.copy(videoBitrate = 4_000_000, audioChannelCount = 2),
        )
        assertEquals(
            changed(RtspField.VIDEO_BITRATE, RtspField.AUDIO_CHANNEL_COUNT),
            diff,
        )
        assertTrue(RtspConfigDiff.needsRestart(diff))
    }

    @Test
    fun `a fresh auth instance with equal content still counts as changed`() {
        // RtspAuthSpec is identity-equals; a new spec must not be silently dropped.
        assertTrue(RtspField.AUTH in RtspConfigDiff.of(base, base.copy(auth = RtspAuthSpec("", "", ""))))
        assertEquals(emptySet<RtspField>(), RtspConfigDiff.of(base.copy(auth = null), base.copy(auth = null)))
    }
}
