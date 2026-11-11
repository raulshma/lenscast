package com.raulshma.lenscast.camera

import com.raulshma.lenscast.camera.model.StreamAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraBindingMathTest {

    @Test
    fun `ladder starts with the full set plus extra`() {
        val combos = orderedCombinations(listOf("p", "c", "a"), extra = "v")
        assertEquals(listOf("p", "c", "a", "v"), combos.first())
        assertEquals(listOf("v"), combos.last())
    }

    @Test
    fun `ladder sizes never increase`() {
        val combos = orderedCombinations(listOf("p", "c", "a"), extra = "v")
        val sizes = combos.map { it.size }
        assertEquals(sizes.sortedDescending(), sizes)
    }

    @Test
    fun `every rung carries the extra use case`() {
        val combos = orderedCombinations(listOf("p", "c"), extra = "v")
        assertTrue(combos.all { "v" in it })
        assertEquals(4, combos.size)
    }

    @Test
    fun `ladder without extra covers every subset`() {
        val combos = orderedCombinations(listOf("p", "c"), extra = null)
        assertEquals(
            setOf(
                listOf("p", "c"),
                listOf("p"),
                listOf("c"),
                emptyList(),
            ),
            combos.toSet(),
        )
    }

    @Test
    fun `small captures pass through under both aspects`() {
        assertEquals(640 to 480, analysisSizeFor(640, 480, 1280, 720))
        assertEquals(1280 to 720, analysisSizeFor(1280, 720, 1280, 720))
        assertEquals(640 to 480, analysisSizeFor(640, 480, 1280, 720, StreamAspect.STANDARD_4_3))
        assertEquals(720 to 480, analysisSizeFor(720, 480, 1280, 720, StreamAspect.STANDARD_4_3))
    }

    @Test
    fun `large captures under the four-three aspect drop to 960x720`() {
        assertEquals(960 to 720, analysisSizeFor(1920, 1080, 1280, 720, StreamAspect.STANDARD_4_3))
        assertEquals(960 to 720, analysisSizeFor(1920, 1440, 1280, 720, StreamAspect.STANDARD_4_3))
        assertEquals(960 to 720, analysisSizeFor(3840, 2160, 1280, 720, StreamAspect.STANDARD_4_3))
    }

    @Test
    fun `large captures under the wide aspect drop to the ceiling regardless of capture shape`() {
        assertEquals(1280 to 720, analysisSizeFor(1920, 1080, 1280, 720))
        assertEquals(1280 to 720, analysisSizeFor(3840, 2160, 1280, 720))
        // The aspect is the encode target's, not the capture's: a 4:3 capture
        // under a 16:9 encode still analyzes at the wide ceiling.
        assertEquals(1280 to 720, analysisSizeFor(1920, 1440, 1280, 720, StreamAspect.WIDE_16_9))
        assertEquals(1280 to 720, analysisSizeFor(1600, 1200, 1280, 720))
    }
}
