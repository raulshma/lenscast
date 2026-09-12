package com.raulshma.lenscast.capture.model

import com.raulshma.lenscast.capture.TimelapseAssembler
import org.junit.Assert.assertEquals
import org.junit.Test

/** The completion→assemble handoff verdicts behind the interval worker. */
class TimelapseCompletionPolicyTest {

    @Test
    fun `toggle off always skips`() {
        assertEquals(
            TimelapseCompletionPolicy.Verdict.Skip,
            TimelapseCompletionPolicy.onSeriesComplete(autoTimelapseEnabled = false, availablePhotos = 500),
        )
    }

    @Test
    fun `below the assembler floor skips`() {
        assertEquals(
            TimelapseCompletionPolicy.Verdict.Skip,
            TimelapseCompletionPolicy.onSeriesComplete(
                autoTimelapseEnabled = true,
                availablePhotos = TimelapseAssembler.MIN_SOURCES - 1,
            ),
        )
    }

    @Test
    fun `at or above the floor assembles with the available count`() {
        val verdict = TimelapseCompletionPolicy.onSeriesComplete(
            autoTimelapseEnabled = true,
            availablePhotos = TimelapseAssembler.MIN_SOURCES,
        )
        assertEquals(TimelapseCompletionPolicy.Verdict.Assemble(TimelapseAssembler.MIN_SOURCES), verdict)

        assertEquals(
            TimelapseCompletionPolicy.Verdict.Assemble(250),
            TimelapseCompletionPolicy.onSeriesComplete(autoTimelapseEnabled = true, availablePhotos = 250),
        )
    }
}
