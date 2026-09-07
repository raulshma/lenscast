package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.NetworkQualityMonitor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MjpegStreamPumpTest {

    @Test
    fun `first part header matches the wire format`() {
        val header = MjpegStreamPump.buildPartHeader("LensCastBoundary", true, 4823)
        assertEquals(
            "--LensCastBoundary\r\nContent-Type: image/jpeg\r\nContent-Length: 4823\r\n\r\n",
            String(header, Charsets.US_ASCII),
        )
    }

    @Test
    fun `subsequent part header carries the leading CRLF`() {
        val header = MjpegStreamPump.buildPartHeader("LensCastBoundary", false, 7)
        assertEquals(
            "\r\n--LensCastBoundary\r\nContent-Type: image/jpeg\r\nContent-Length: 7\r\n\r\n",
            String(header, Charsets.US_ASCII),
        )
    }

    @Test
    fun `chunk copy respects bounds`() {
        val source = byteArrayOf(1, 2, 3, 4)
        val target = ByteArray(4)
        assertEquals(2, MjpegStreamPump.copyChunk(source, 1, target, 0, 2))
        assertArrayEquals(byteArrayOf(2, 3, 0, 0), target)
        assertEquals(0, MjpegStreamPump.copyChunk(source, 9, target, 0, 2))
        assertEquals(0, MjpegStreamPump.copyChunk(source, 0, target, 0, 0))
        assertEquals(1, MjpegStreamPump.copyChunk(source, 3, target, 0, 99))
    }

    @Test
    fun `disabled pump refuses the stream`() {
        val pump = MjpegStreamPump(NetworkQualityMonitor(), "LensCastBoundary")
        pump.setEnabled(false)
        val result = pump.openStream()
        assertEquals(503, result.statusCode)
        assertEquals(0, pump.getClientCount())
    }

    @Test
    fun `opened stream carries the multipart mime type`() {
        val pump = MjpegStreamPump(NetworkQualityMonitor(), "LensCastBoundary")
        val result = pump.openStream()
        assertEquals(200, result.statusCode)
        assertEquals(
            "multipart/x-mixed-replace; boundary=LensCastBoundary",
            result.mimeType,
        )
        assertEquals(1, pump.getClientCount())
        (result.body as HttpResult.ResponseBody.Stream).stream.close()
        assertEquals(0, pump.getClientCount())
    }

    @Test
    fun `pump holds the latest frame for snapshots`() {
        val pump = MjpegStreamPump(NetworkQualityMonitor(), "LensCastBoundary")
        assertNull(pump.latestFrame())
        val frame = byteArrayOf(1, 2, 3)
        pump.updateFrame(frame)
        assertTrue(pump.latestFrame() === frame)
    }
}
