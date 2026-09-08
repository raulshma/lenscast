package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundNotificationsTest {

    // ── the registry: one distinct slot per foreground producer ──

    @Test
    fun `registry ids are all distinct`() {
        val ids = listOf(
            ForegroundNotifications.RECORDING_NOTIFICATION_ID,
            ForegroundNotifications.STREAMING_NOTIFICATION_ID,
            ForegroundNotifications.UPDATE_NOTIFICATION_ID,
            ForegroundNotifications.INTERVAL_CAPTURE_NOTIFICATION_ID,
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `registry ids keep their pinned slots`() {
        assertEquals(1001, ForegroundNotifications.RECORDING_NOTIFICATION_ID)
        assertEquals(1002, ForegroundNotifications.STREAMING_NOTIFICATION_ID)
        assertEquals(1003, ForegroundNotifications.UPDATE_NOTIFICATION_ID)
        // Interval capture previously collided with streaming on 1002.
        assertEquals(1004, ForegroundNotifications.INTERVAL_CAPTURE_NOTIFICATION_ID)
    }

    // ── streaming message: url present names it, audio adds its clause ──

    @Test
    fun `streaming message names the target url`() {
        assertEquals(
            "Streaming to rtsp://192.168.1.10:8554/live",
            ForegroundNotifications.streamingMessage("rtsp://192.168.1.10:8554/live", includeAudio = false)
        )
    }

    @Test
    fun `streaming message with audio names video and audio`() {
        assertEquals(
            "Streaming video and audio to rtsp://host:8554/live",
            ForegroundNotifications.streamingMessage("rtsp://host:8554/live", includeAudio = true)
        )
    }

    @Test
    fun `streaming message without url falls back to the generic feed line`() {
        assertEquals(
            "Streaming camera feed",
            ForegroundNotifications.streamingMessage(null, includeAudio = false)
        )
        assertEquals(
            "Streaming camera feed with audio",
            ForegroundNotifications.streamingMessage(null, includeAudio = true)
        )
    }

    @Test
    fun `empty url counts as missing for the streaming message`() {
        // isNullOrEmpty, not just null: a blank extra must not render
        // "Streaming to ".
        assertEquals(
            "Streaming camera feed",
            ForegroundNotifications.streamingMessage("", includeAudio = false)
        )
        assertEquals(
            "Streaming camera feed with audio",
            ForegroundNotifications.streamingMessage("", includeAudio = true)
        )
    }

    // ── interval-capture message: the in-flight photo of the series ──

    @Test
    fun `interval message shows the first photo as photo one`() {
        assertEquals(
            "Capturing photo 1 of 4",
            ForegroundNotifications.intervalCaptureMessage(completedCaptures = 0, totalCaptures = 4)
        )
    }

    @Test
    fun `interval message mid-series counts from the completed captures`() {
        assertEquals(
            "Capturing photo 3 of 5",
            ForegroundNotifications.intervalCaptureMessage(completedCaptures = 2, totalCaptures = 5)
        )
    }

    @Test
    fun `interval message final tick shows the last photo not a done state`() {
        // The next tick after the final capture is never enqueued; the last
        // foreground notification is the one taking the final photo.
        assertEquals(
            "Capturing photo 4 of 4",
            ForegroundNotifications.intervalCaptureMessage(completedCaptures = 3, totalCaptures = 4)
        )
    }

    @Test
    fun `interval message with a single capture is photo one of one`() {
        assertEquals(
            "Capturing photo 1 of 1",
            ForegroundNotifications.intervalCaptureMessage(completedCaptures = 0, totalCaptures = 1)
        )
    }

    @Test
    fun `interval message without a total stays generic`() {
        // totalCaptures of 0 means the series never declared a size; the
        // in-flight count alone would read "photo 1 of 0", so the line
        // stays generic instead.
        assertEquals(
            "Capturing interval photo",
            ForegroundNotifications.intervalCaptureMessage(completedCaptures = 0, totalCaptures = 0)
        )
        assertEquals(
            "Capturing interval photo",
            ForegroundNotifications.intervalCaptureMessage(completedCaptures = 2, totalCaptures = 0)
        )
    }
}
