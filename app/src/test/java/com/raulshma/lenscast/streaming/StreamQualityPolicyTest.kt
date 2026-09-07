package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamQualityPolicyTest {

    private class FakeThermal(
        var quality: Int = 80,
        var intervalMs: Long = 100L,
    ) : ThermalAdjustmentSource {
        override fun getAdjustedQuality(baseQuality: Int): Int = quality
        override fun getAdjustedFrameDelay(baseIntervalMs: Long): Long = intervalMs
    }

    private class FakeNetwork(
        var qualityFactor: Float = 1.0f,
        var interval: Long? = null,
    ) : NetworkAdjustmentSource {
        var lastQualityInputs: Pair<Int, Int>? = null
        var lastIntervalInputs: Pair<Long, Long>? = null

        override fun getAdaptiveQuality(baseQuality: Int, thermalAdjustedQuality: Int): Int {
            lastQualityInputs = baseQuality to thermalAdjustedQuality
            return (thermalAdjustedQuality * qualityFactor).toInt()
        }

        override fun getAdaptiveFrameInterval(baseIntervalMs: Long, thermalAdjustedIntervalMs: Long): Long {
            lastIntervalInputs = baseIntervalMs to thermalAdjustedIntervalMs
            return interval ?: thermalAdjustedIntervalMs
        }
    }

    @Test
    fun `resolution order is battery base then thermal then network`() {
        val thermal = FakeThermal(quality = 55, intervalMs = 120L)
        val network = FakeNetwork(qualityFactor = 0.5f)
        val policy = StreamQualityPolicy(thermal, network)

        assertEquals(27, policy.resolveQuality(80))
        assertEquals(120L, policy.resolveInterval(100L))
        // Thermal saw the battery base; network saw base and thermal values.
        assertEquals(80 to 55, network.lastQualityInputs)
        assertEquals(100L to 120L, network.lastIntervalInputs)
    }

    @Test
    fun `identity sensors pass the base straight through`() {
        val policy = StreamQualityPolicy(FakeThermal(), FakeNetwork())
        assertEquals(80, policy.resolveQuality(80))
        assertEquals(100L, policy.resolveInterval(100L))
    }

    @Test
    fun `thermal pause propagates as an infinite interval`() {
        val thermal = FakeThermal(quality = 20, intervalMs = Long.MAX_VALUE)
        val policy = StreamQualityPolicy(thermal, FakeNetwork())
        assertEquals(Long.MAX_VALUE, policy.resolveInterval(100L))
        assertEquals(20, policy.resolveQuality(80))
    }

    @Test
    fun `network stretch applies on top of the thermal interval`() {
        val thermal = FakeThermal(intervalMs = 100L)
        val network = FakeNetwork(interval = 400L)
        val policy = StreamQualityPolicy(thermal, network)
        assertEquals(400L, policy.resolveInterval(100L))
    }
}
