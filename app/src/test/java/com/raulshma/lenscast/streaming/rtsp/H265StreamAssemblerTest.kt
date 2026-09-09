package com.raulshma.lenscast.streaming.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class H265StreamAssemblerTest {

    /** A type-32 VPS body (byte0 = 32<<1 = 0x40). */
    private val vps = byteArrayOf(0x40, 0x01, 0x0A)

    /** A type-33 SPS body (byte0 = 33<<1 = 0x42). */
    private val sps = byteArrayOf(0x42, 0x01, 0x0B, 0x0C)

    /** A type-34 PPS body (byte0 = 34<<1 = 0x44). */
    private val pps = byteArrayOf(0x44, 0x01, 0x0D)

    /** A type-20 IDR_N_LP slice body (byte0 = 0x28). */
    private val idr = byteArrayOf(0x28, 0x01, 0x02, 0x03)

    /** A type-1 TRAIL_R slice body (byte0 = 0x02). */
    private val slice = byteArrayOf(0x02, 0x0A, 0x0B)

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (nal in nals) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes)

    // ── CSD path 1: codec-config output buffer (the single-blob case) ──

    @Test
    fun `config buffer path learns vps sps and pps from one annex-b blob`() {
        val assembler = H265StreamAssembler()

        val data = annexB(vps, sps, pps)
        assembler.updateFromConfigBuffer(buffer(data), offset = 0, size = data.size)

        assertArrayEquals(vps, assembler.vps)
        assertArrayEquals(sps, assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    @Test
    fun `config buffer path learns from a buffer region, not the whole buffer`() {
        val assembler = H265StreamAssembler()

        val data = annexB(vps, sps, pps)
        val padded = ByteBuffer.wrap(ByteArray(data.size + 8)).apply { put(data) } // data at 0..size, garbage after

        assembler.updateFromConfigBuffer(padded, offset = 0, size = data.size)

        assertArrayEquals(vps, assembler.vps)
        assertArrayEquals(sps, assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    @Test
    fun `config buffer path updates only the parameter sets present`() {
        val assembler = H265StreamAssembler()
        val data = annexB(sps)

        assembler.updateFromConfigBuffer(buffer(data), offset = 0, size = data.size)

        assertNull(assembler.vps)
        assertArrayEquals(sps, assembler.sps)
        assertNull(assembler.pps)
    }

    // ── CSD path 2: output format csd-0/csd-1/csd-2 ──

    @Test
    fun `format path accepts the fully split csd triple without start codes`() {
        val assembler = H265StreamAssembler()

        assembler.updateFromFormat(csd0 = buffer(vps), csd1 = buffer(sps), csd2 = buffer(pps))

        assertArrayEquals(vps, assembler.vps)
        assertArrayEquals(sps, assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    @Test
    fun `format path accepts one csd-0 blob carrying all three parameter sets`() {
        val assembler = H265StreamAssembler()

        assembler.updateFromFormat(
            csd0 = buffer(annexB(vps, sps, pps)),
            csd1 = null,
            csd2 = null,
        )

        assertArrayEquals(vps, assembler.vps)
        assertArrayEquals(sps, assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    @Test
    fun `format path strips leading start codes from split csd buffers`() {
        val assembler = H265StreamAssembler()

        assembler.updateFromFormat(
            csd0 = buffer(annexB(vps)),
            csd1 = buffer(byteArrayOf(0, 0, 1) + sps),
            csd2 = buffer(pps),
        )

        assertArrayEquals(vps, assembler.vps)
        assertArrayEquals(sps, assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    @Test
    fun `format path leaves parameter sets unchanged when their csd buffers are null`() {
        val assembler = H265StreamAssembler()
        assembler.updateFromFormat(csd0 = buffer(vps), csd1 = buffer(sps))

        assembler.updateFromFormat(csd0 = null, csd1 = null, csd2 = buffer(pps))

        assertArrayEquals(vps, assembler.vps)
        assertArrayEquals(sps, assembler.sps)
        assertArrayEquals(pps, assembler.pps)
    }

    // ── assembly ──

    @Test
    fun `keyframe with all parameter sets cached is prepended with vps sps pps as non-key nals`() {
        val assembler = H265StreamAssembler()
        assembler.updateFromFormat(csd0 = buffer(vps), csd1 = buffer(sps), csd2 = buffer(pps))

        val assembled = assembler.assemble(listOf(idr), isKeyFrame = true)

        assertEquals(4, assembled.size)
        assertArrayEquals(vps, assembled[0].data)
        assertFalse(assembled[0].isKeyFrame)
        assertArrayEquals(sps, assembled[1].data)
        assertFalse(assembled[1].isKeyFrame)
        assertArrayEquals(pps, assembled[2].data)
        assertFalse(assembled[2].isKeyFrame)
        assertArrayEquals(idr, assembled[3].data)
        assertTrue(assembled[3].isKeyFrame)
    }

    @Test
    fun `keyframe without the full parameter set trio passes through marked key`() {
        val assembler = H265StreamAssembler() // nothing learned yet
        val assembled = assembler.assemble(listOf(idr, slice), isKeyFrame = true)

        assertEquals(2, assembled.size)
        assertArrayEquals(idr, assembled[0].data)
        assertTrue(assembled[0].isKeyFrame)
        assertTrue(assembled[1].isKeyFrame)
    }

    @Test
    fun `a missing vps alone suppresses the prepend - h265 needs all three`() {
        val assembler = H265StreamAssembler()
        assembler.updateFromConfigBuffer(buffer(annexB(sps, pps)), 0, annexB(sps, pps).size)

        val assembled = assembler.assemble(listOf(idr), isKeyFrame = true)

        assertEquals(1, assembled.size)
        assertArrayEquals(idr, assembled[0].data)
        assertTrue(assembled[0].isKeyFrame)
    }

    @Test
    fun `non-keyframe passes through without the parameter sets`() {
        val assembler = H265StreamAssembler()
        assembler.updateFromFormat(csd0 = buffer(vps), csd1 = buffer(sps), csd2 = buffer(pps))

        val assembled = assembler.assemble(listOf(slice), isKeyFrame = false)

        assertEquals(1, assembled.size)
        assertArrayEquals(slice, assembled[0].data)
        assertFalse(assembled[0].isKeyFrame)
    }

    @Test
    fun `keyframe already carrying in-band parameter sets still gets them prepended`() {
        // Pins today's wire output: no deduplication of in-band parameter sets.
        val assembler = H265StreamAssembler()
        assembler.updateFromFormat(csd0 = buffer(vps), csd1 = buffer(sps), csd2 = buffer(pps))

        val assembled = assembler.assemble(listOf(vps, sps, pps, idr), isKeyFrame = true)

        assertEquals(7, assembled.size)
        assertArrayEquals(vps, assembled[0].data) // prepended
        assertArrayEquals(sps, assembled[1].data) // prepended
        assertArrayEquals(pps, assembled[2].data) // prepended
        assertArrayEquals(vps, assembled[3].data) // in-band copy, kept
        assertArrayEquals(sps, assembled[4].data) // in-band copy, kept
        assertArrayEquals(pps, assembled[5].data) // in-band copy, kept
        assertArrayEquals(idr, assembled[6].data)
    }

    @Test
    fun `empty nal list assembles to an empty list`() {
        val assembler = H265StreamAssembler()
        assembler.updateFromFormat(csd0 = buffer(vps), csd1 = buffer(sps), csd2 = buffer(pps))

        assertEquals(0, assembler.assemble(emptyList(), isKeyFrame = true).size)
        assertEquals(0, assembler.assemble(emptyList(), isKeyFrame = false).size)
    }
}
