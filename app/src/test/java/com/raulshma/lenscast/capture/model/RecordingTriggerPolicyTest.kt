package com.raulshma.lenscast.capture.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The producer-side trigger stamping decisions behind [RecordingTrigger]. */
class RecordingTriggerPolicyTest {

    @Test
    fun `explicit trigger wins over the scheduled fold`() {
        assertEquals(
            RecordingTrigger.MOTION,
            RecordingTriggerPolicy.forStart(
                RecordingConfig(trigger = RecordingTrigger.MOTION),
                scheduledStart = true,
            ),
        )
        assertEquals(
            RecordingTrigger.CONTINUOUS_LOOP,
            RecordingTriggerPolicy.forStart(
                RecordingConfig(trigger = RecordingTrigger.CONTINUOUS_LOOP),
                scheduledStart = false,
            ),
        )
    }

    @Test
    fun `a manual config reaching the scheduled start path folds to SCHEDULED`() {
        assertEquals(
            RecordingTrigger.SCHEDULED,
            RecordingTriggerPolicy.forStart(RecordingConfig(), scheduledStart = true),
        )
    }

    @Test
    fun `plain manual config stays manual`() {
        assertEquals(
            RecordingTrigger.MANUAL,
            RecordingTriggerPolicy.forStart(RecordingConfig(), scheduledStart = false),
        )
    }

    @Test
    fun `a null config is manual`() {
        assertEquals(RecordingTrigger.MANUAL, RecordingTriggerPolicy.forStart(null, scheduledStart = true))
    }

    @Test
    fun `wire names round trip with manual as the unknown fallback`() {
        RecordingTrigger.entries.forEach { trigger ->
            assertEquals(trigger, RecordingTrigger.fromWireName(trigger.wireName))
        }
        assertEquals(RecordingTrigger.MANUAL, RecordingTrigger.fromWireName("nonsense"))
        assertEquals(RecordingTrigger.MANUAL, RecordingTrigger.fromWireName(null))
    }
}
