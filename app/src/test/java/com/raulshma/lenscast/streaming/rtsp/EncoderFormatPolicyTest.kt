package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderFormatPolicyTest {

    private val planar = EncoderFormatPolicy.COLOR_FORMAT_YUV420_PLANAR
    private val packedSemiPlanar = EncoderFormatPolicy.COLOR_FORMAT_YUV420_PACKED_SEMI_PLANAR
    private val semiPlanar = EncoderFormatPolicy.COLOR_FORMAT_YUV420_SEMI_PLANAR
    private val flexible = EncoderFormatPolicy.COLOR_FORMAT_YUV420_FLEXIBLE

    @Test
    fun `auto follows the preference order semi-planar planar flexible packed`() {
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(semiPlanar, RtspInputFormat.NV12),
            EncoderFormatPolicy.choose(setOf(planar, semiPlanar, packedSemiPlanar), RtspInputFormat.AUTO)
        )
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(planar, RtspInputFormat.I420),
            EncoderFormatPolicy.choose(setOf(planar, packedSemiPlanar), RtspInputFormat.AUTO)
        )
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(flexible, RtspInputFormat.I420),
            EncoderFormatPolicy.choose(setOf(flexible), RtspInputFormat.AUTO)
        )
        // Packed semi-planar alone is chosen only as the last resort — and maps to NV12.
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(packedSemiPlanar, RtspInputFormat.NV12),
            EncoderFormatPolicy.choose(setOf(packedSemiPlanar), RtspInputFormat.AUTO)
        )
    }

    @Test
    fun `auto choice never reports a fallback`() {
        val selected = EncoderFormatPolicy.choose(setOf(semiPlanar), RtspInputFormat.AUTO)
        assertFalse(selected.fellBackToAuto)
    }

    @Test
    fun `requested NV21 maps to packed semi-planar when supported`() {
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(packedSemiPlanar, RtspInputFormat.NV21),
            EncoderFormatPolicy.choose(setOf(packedSemiPlanar, semiPlanar), RtspInputFormat.NV21)
        )
    }

    @Test
    fun `requested NV12 maps to semi-planar when supported`() {
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(semiPlanar, RtspInputFormat.NV12),
            EncoderFormatPolicy.choose(setOf(semiPlanar, planar), RtspInputFormat.NV12)
        )
    }

    @Test
    fun `requested I420 prefers planar then flexible`() {
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(planar, RtspInputFormat.I420),
            EncoderFormatPolicy.choose(setOf(planar, flexible), RtspInputFormat.I420)
        )
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(flexible, RtspInputFormat.I420),
            EncoderFormatPolicy.choose(setOf(flexible), RtspInputFormat.I420)
        )
    }

    @Test
    fun `unsupported request falls back to the auto ladder and reports it`() {
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(semiPlanar, RtspInputFormat.NV12, fellBackToAuto = true),
            EncoderFormatPolicy.choose(setOf(semiPlanar), RtspInputFormat.NV21)
        )
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(planar, RtspInputFormat.I420, fellBackToAuto = true),
            EncoderFormatPolicy.choose(setOf(planar), RtspInputFormat.NV12)
        )
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(semiPlanar, RtspInputFormat.NV12, fellBackToAuto = true),
            EncoderFormatPolicy.choose(setOf(semiPlanar), RtspInputFormat.I420)
        )
    }

    @Test
    fun `packed semi-planar is only ever fed NV12 - the tint quirk`() {
        // A direct NV21 request that resolves (packed format supported) keeps NV21...
        assertEquals(
            EncoderFormatPolicy.SelectedFormat(packedSemiPlanar, RtspInputFormat.NV21),
            EncoderFormatPolicy.choose(setOf(packedSemiPlanar), RtspInputFormat.NV21)
        )
        // ...but the AUTO ladder, when stuck with the ambiguous packed format,
        // treats it as NV12 — never NV21 — avoiding magenta/green tint on devices
        // with inconsistent packed-semi-planar ordering.
        val auto = EncoderFormatPolicy.choose(setOf(packedSemiPlanar), RtspInputFormat.AUTO)
        assertEquals(packedSemiPlanar, auto.colorFormat)
        assertEquals(RtspInputFormat.NV12, auto.effectiveInputFormat)
    }

    @Test
    fun `empty supported set falls hard to semi-planar NV12`() {
        for (requested in RtspInputFormat.entries) {
            val selected = EncoderFormatPolicy.choose(emptySet(), requested)
            assertEquals(semiPlanar, selected.colorFormat)
            assertEquals(RtspInputFormat.NV12, selected.effectiveInputFormat)
            assertFalse(selected.fellBackToAuto)
        }
    }

    @Test
    fun `supported set with nothing on our ladder falls hard to semi-planar NV12`() {
        val selected = EncoderFormatPolicy.choose(setOf(17, 0x7F000000), RtspInputFormat.AUTO)
        assertEquals(semiPlanar, selected.colorFormat)
        assertEquals(RtspInputFormat.NV12, selected.effectiveInputFormat)
        assertFalse(selected.fellBackToAuto)
    }
}
