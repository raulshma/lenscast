package com.raulshma.lenscast.companion.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class QrLuminanceTest {

    @Test
    fun `extractYPlaneLuminance rewinds tight-packed buffer consumed by prior reader`() {
        val expected = byteArrayOf(10, 20, 30, 40, 50, 60)
        val buffer = ByteBuffer.wrap(expected.copyOf())
        // Simulate a prior consumer (such as ImageProxy.toBitmap()) advancing position to limit.
        buffer.position(buffer.limit())

        val result = extractYPlaneLuminance(
            buffer = buffer,
            width = 3,
            height = 2,
            rowStride = 3,
            pixelStride = 1,
        )

        assertArrayEquals(expected, result)
    }

    @Test
    fun `extractYPlaneLuminance handles padded row stride and non-unit pixel stride`() {
        // 2x2 image with rowStride=4, pixelStride=2 -> bytes at 0, 2, 4, 6
        val raw = byteArrayOf(11, 99, 22, 99, 33, 99, 44, 99)
        val buffer = ByteBuffer.wrap(raw)
        buffer.position(raw.size)

        val result = extractYPlaneLuminance(
            buffer = buffer,
            width = 2,
            height = 2,
            rowStride = 4,
            pixelStride = 2,
        )

        assertArrayEquals(byteArrayOf(11, 22, 33, 44), result)
    }

    @Test
    fun `extractYPlaneLuminance returns null for empty dimensions`() {
        val buffer = ByteBuffer.allocate(4)
        assertNull(extractYPlaneLuminance(buffer, width = 0, height = 2, rowStride = 2, pixelStride = 1))
        assertNull(extractYPlaneLuminance(buffer, width = 2, height = 0, rowStride = 2, pixelStride = 1))
    }
}
