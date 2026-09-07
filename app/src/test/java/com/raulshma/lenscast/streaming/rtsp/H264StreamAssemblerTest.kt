package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class H264StreamAssemblerTest {

    /** A type-7 SPS body (0x67 = forbidden=0, nal_ref=3, type=7). */
    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F.toByte())

    /** A type-8 PPS body (0x68). */
    private val pps = byteArrayOf(0x68.toByte(), 0xCE.toByte(), 0x38.toByte(), 0x80.toByte())

    /** A type-5 IDR slice body (0x65). */
    private val idr = byteArrayOf(0x65.toByte(), 0x01, 0x02, 0x03)

    /** A type-1 non-reference slice body (0x41). */
    private val slice = byteArrayOf(0x41, 0x0A, 0x0B)

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (nal in nals) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes)

    // ── CSD path 1: codec-config output buffer ──

    @Test
    fun `config buffer path learns sps and pps from the annex-b codec config`() {
        val assembler = H264StreamAssembler()

        val data = annexB(sps, pps)
        assembler.updateFromConfigBuffer(buffer(data), offset = 0, size = data.size)

        assertArrayEquals(sps, assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    @Test
    fun `config buffer path learns from a buffer region, not the whole buffer`() {
        val assembler = H264StreamAssembler()

        val data = annexB(sps, pps)
        val padded = ByteBuffer.wrap(ByteArray(data.size + 8)).apply { put(data) } // data at 0..size, garbage after

        assembler.updateFromConfigBuffer(padded, offset = 0, size = data.size)

        assertArrayEquals(sps, assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    @Test
    fun `config buffer path updates only the parameter sets present`() {
        val assembler = H264StreamAssembler()
        val data = annexB(pps)

        assembler.updateFromConfigBuffer(buffer(data), offset = 0, size = data.size)

        assertNull(assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    // ── CSD path 2: output format csd-0/csd-1 ──

    @Test
    fun `format path strips the leading start code from csd buffers`() {
        val assembler = H264StreamAssembler()

        assembler.updateFromFormat(
            csd0 = buffer(annexB(sps)),
            csd1 = buffer(byteArrayOf(0, 0, 1) + pps),
        )

        assertArrayEquals(sps, assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    @Test
    fun `format path accepts bare csd buffers without start codes`() {
        val assembler = H264StreamAssembler()

        assembler.updateFromFormat(csd0 = buffer(sps), csd1 = buffer(pps))

        assertArrayEquals(sps, assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    @Test
    fun `format path leaves a parameter set unchanged when its csd buffer is null`() {
        val assembler = H264StreamAssembler()
        assembler.updateFromConfigBuffer(buffer(annexB(sps)), 0, annexB(sps).size)

        assembler.updateFromFormat(csd0 = null, csd1 = buffer(pps))

        assertArrayEquals(sps, assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    // ── assembly ──

    @Test
    fun `keyframe with both parameter sets cached is prepended with them as non-key nals`() {
        val assembler = H264StreamAssembler()
        assembler.updateFromFormat(csd0 = buffer(sps), csd1 = buffer(pps))

        val assembled = assembler.assemble(listOf(idr), isKeyFrame = true)

        assertEquals(3, assembled.size)
        assertArrayEquals(sps, assembled[0].data)
        assertFalse(assembled[0].isKeyFrame)
        assertArrayEquals(pps, assembled[1].data)
        assertFalse(assembled[1].isKeyFrame)
        assertArrayEquals(idr, assembled[2].data)
        assertTrue(assembled[2].isKeyFrame)
    }

    @Test
    fun `keyframe without both parameter sets passes through marked key`() {
        val assembler = H264StreamAssembler() // no CSD learned yet

        val assembled = assembler.assemble(listOf(idr, slice), isKeyFrame = true)

        assertEquals(2, assembled.size)
        assertArrayEquals(idr, assembled[0].data)
        assertTrue(assembled[0].isKeyFrame)
        assertTrue(assembled[1].isKeyFrame)
    }

    @Test
    fun `non-keyframe passes through without the parameter sets`() {
        val assembler = H264StreamAssembler()
        assembler.updateFromFormat(csd0 = buffer(sps), csd1 = buffer(pps))

        val assembled = assembler.assemble(listOf(slice), isKeyFrame = false)

        assertEquals(1, assembled.size)
        assertArrayEquals(slice, assembled[0].data)
        assertFalse(assembled[0].isKeyFrame)
    }

    @Test
    fun `keyframe already carrying in-band parameter sets still gets them prepended`() {
        // Pins today's wire output: no deduplication of in-band parameter sets.
        val assembler = H264StreamAssembler()
        assembler.updateFromFormat(csd0 = buffer(sps), csd1 = buffer(pps))

        val assembled = assembler.assemble(listOf(sps, pps, idr), isKeyFrame = true)

        assertEquals(5, assembled.size)
        assertArrayEquals(sps, assembled[0].data) // prepended
        assertArrayEquals(pps, assembled[1].data) // prepended
        assertArrayEquals(sps, assembled[2].data) // in-band copy, kept
        assertArrayEquals(pps, assembled[3].data) // in-band copy, kept
        assertArrayEquals(idr, assembled[4].data)
    }

    @Test
    fun `empty nal list assembles to an empty list`() {
        val assembler = H264StreamAssembler()
        assembler.updateFromFormat(csd0 = buffer(sps), csd1 = buffer(pps))

        assertEquals(0, assembler.assemble(emptyList(), isKeyFrame = true).size)
        assertEquals(0, assembler.assemble(emptyList(), isKeyFrame = false).size)
    }
}
