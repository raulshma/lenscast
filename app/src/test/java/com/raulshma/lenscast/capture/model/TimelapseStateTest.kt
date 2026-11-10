package com.raulshma.lenscast.capture.model

import com.raulshma.lenscast.capture.TimelapseAssembler
import com.raulshma.lenscast.capture.TimelapseComposer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The result → message mapping the capture screen renders: each reason maps
 * onto its own message shape, everything else folds to Failed — the same
 * resources and text the two-flow implementation produced.
 */
class TimelapseStateTest {

    @Test
    fun `saved result carries the file name and frame count`() {
        assertEquals(
            TimelapseMessage.Saved(fileName = "TL_2026.mp4", frameCount = 42),
            timelapseMessage(TimelapseComposer.Result.Saved("TL_2026.mp4", 42)),
        )
    }

    @Test
    fun `not enough sources carries the assembler floor`() {
        assertEquals(
            TimelapseMessage.NeedSources(TimelapseAssembler.MIN_SOURCES),
            timelapseMessage(
                TimelapseComposer.Result.NotSaved(TimelapseComposer.Reason.NOT_ENOUGH_SOURCES, "have 3"),
            ),
        )
    }

    @Test
    fun `unreadable sources carries the detail`() {
        assertEquals(
            TimelapseMessage.Unreadable("read 4"),
            timelapseMessage(
                TimelapseComposer.Result.NotSaved(TimelapseComposer.Reason.UNREADABLE_SOURCES, "read 4"),
            ),
        )
    }

    @Test
    fun `every other reason folds to failed`() {
        for (reason in listOf(
            TimelapseComposer.Reason.ASSEMBLY_FAILED,
            TimelapseComposer.Reason.PUBLISH_FAILED,
        )) {
            assertEquals(
                TimelapseMessage.Failed,
                timelapseMessage(TimelapseComposer.Result.NotSaved(reason)),
            )
        }
    }
}
