package com.raulshma.lenscast.capture.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated YAMNet class map's invariants and the classifier's pure
 * window conversion — the two pieces of the audio path that must hold on
 * plain JVM before any MediaPipe code runs (the engine's task-library
 * contact is lazy, exactly so this test can exist).
 */
class YamnetClassificationTest {

    // ── YamnetLabels — the index→name contract ──

    @Test
    fun `the class map carries exactly 521 unique names in index order`() {
        assertEquals(521, YamnetLabels.CLASSES.size)
        assertEquals(YamnetLabels.CLASS_COUNT, YamnetLabels.CLASSES.size)
        assertEquals("the class names must be unique", YamnetLabels.CLASSES.size, YamnetLabels.CLASSES.toSet().size)
    }

    @Test
    fun `anchor indices match the official class map`() {
        // Spots pinned from the upstream yamnet_class_map.csv: if any of these
        // moved, the whole index mapping is shifted and every label is wrong.
        assertEquals("Speech", YamnetLabels.labelFor(0))
        assertEquals("Dog", YamnetLabels.labelFor(69))
        assertEquals("Fire", YamnetLabels.labelFor(292))
        assertEquals("Alarm", YamnetLabels.labelFor(382))
        assertEquals("Siren", YamnetLabels.labelFor(390))
        assertEquals("Smoke detector, smoke alarm", YamnetLabels.labelFor(393))
        assertEquals("Gunshot, gunfire", YamnetLabels.labelFor(421))
        assertEquals("Glass", YamnetLabels.labelFor(435))
        assertEquals("Shatter", YamnetLabels.labelFor(437))
        assertEquals("Field recording", YamnetLabels.labelFor(520))
        assertEquals(0, YamnetLabels.indexOf("Speech"))
        assertEquals(520, YamnetLabels.indexOf("Field recording"))
    }

    @Test
    fun `out-of-range indices answer null`() {
        assertNull(YamnetLabels.labelFor(-1))
        assertNull(YamnetLabels.labelFor(521))
    }

    // ── toMonoFloat16k — the pure window conversion ──

    @Test
    fun `a 16k mono chunk decodes one-to-one into floats`() {
        // The pass-through short-circuit: sample values map to -1..1 exactly.
        val pcm = ByteArray(8) {
            if (it % 2 == 0) 0 else (16384 shr 8).toByte() // every frame = +16384
        }
        val out = SoundClassificationEngine.toMonoFloat16k(pcm, channelCount = 1, sampleRateHz = 16_000, outSamples = 4)
        assertEquals(4, out.size)
        out.forEach { assertEquals(0.5f, it, 1e-4f) }
    }

    @Test
    fun `a 48k mono chunk resamples to a 16k window by linear interpolation`() {
        // Every third 48k frame lands in the 16k window verbatim.
        val frames = 15_360 * 3
        val pcm = ByteArray(frames * 2)
        for (f in 0 until frames) {
            val v = f / 3 // so out[i] should equal i exactly
            pcm[f * 2] = (v and 0xFF).toByte()
            pcm[f * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val out = SoundClassificationEngine.toMonoFloat16k(pcm, channelCount = 1, sampleRateHz = 48_000)
        assertEquals(SoundClassificationEngine.WINDOW_SAMPLES, out.size)
        for (i in 0 until 15_360) {
            assertEquals(i / 32768f, out[i], 1e-4f)
        }
    }

    @Test
    fun `stereo frames average to mono before resampling`() {
        // Left = +32767, right = -32768 → a silent mono frame.
        val pcm = byteArrayOf(
            0xFF.toByte(), 0x7F, 0x00, 0x80.toByte(), // frame 0: +32767 / -32768
            0xFF.toByte(), 0x7F, 0x00, 0x80.toByte(), // frame 1
        )
        val out = SoundClassificationEngine.toMonoFloat16k(pcm, channelCount = 2, sampleRateHz = 16_000, outSamples = 2)
        assertEquals(2, out.size)
        out.forEach { assertEquals(0f, it, 1e-3f) }
    }

    @Test
    fun `a short chunk zero-pads the tail instead of crashing`() {
        val pcm = ByteArray(4) // one stereo frame
        val out = SoundClassificationEngine.toMonoFloat16k(pcm, channelCount = 1, sampleRateHz = 48_000, outSamples = 8)
        assertEquals(8, out.size)
        // The resampled frames beyond the input clamp to the last frame, the
        // rest is silence-safe: everything finite.
        out.forEach { assertTrue(it.isFinite()) }
    }

    @Test
    fun `an empty chunk answers a silent window`() {
        val out = SoundClassificationEngine.toMonoFloat16k(ByteArray(0), channelCount = 1, sampleRateHz = 48_000)
        assertEquals(SoundClassificationEngine.WINDOW_SAMPLES, out.size)
        out.forEach { assertEquals(0f, it, 0f) }
    }
}
