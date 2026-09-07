package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStatusSnapshotTest {

    private fun video() = StreamStatusSnapshot.VideoInputs(
        isStreaming = true,
        isWebActive = true,
        isServerRunning = true,
        url = "http://phone:8080/stream",
        clientCount = 2,
    )

    private fun audio() = StreamStatusSnapshot.AudioInputs(
        isAudioActive = true,
        audioUrl = "http://phone:8080/audio",
        isRtspActive = true,
        rtspUrl = "rtsp://phone:8554",
    )

    @Test
    fun `every field lands on its own slot`() {
        val status = StreamStatusSnapshot.build(
            video = video(),
            audio = audio(),
            isWebEnabled = true,
            isRtspEnabled = true,
        )
        assertEquals(true, status.isActive)
        assertEquals(true, status.isWebActive)
        assertEquals(true, status.isServerRunning)
        assertEquals("http://phone:8080/stream", status.url)
        assertEquals(2, status.clientCount)
        assertEquals(true, status.isAudioActive)
        assertEquals("http://phone:8080/audio", status.audioUrl)
        assertEquals(true, status.isRtspActive)
        assertEquals("rtsp://phone:8554", status.rtspUrl)
        assertEquals(true, status.isWebEnabled)
        assertEquals(true, status.isRtspEnabled)
    }

    @Test
    fun `idle inputs map to the default status`() {
        val status = StreamStatusSnapshot.build(
            video = video().copy(isStreaming = false, clientCount = 0, url = ""),
            audio = audio().copy(isAudioActive = false, isRtspActive = false),
            isWebEnabled = true,
            isRtspEnabled = false,
        )
        assertEquals(
            StreamStatus(
                isActive = false,
                isWebActive = true,
                isServerRunning = true,
                url = "",
                clientCount = 0,
                isAudioActive = false,
                audioUrl = "http://phone:8080/audio",
                isRtspActive = false,
                rtspUrl = "rtsp://phone:8554",
                isWebEnabled = true,
                isRtspEnabled = false,
            ),
            status,
        )
    }
}
