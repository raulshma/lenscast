package com.raulshma.lenscast.camera.model

/**
 * The pure dashboard snapshot: manager flows in, one [StreamStatus] out.
 * The camera screen feeds two typed combines (video, audio) plus the two
 * enable flags through [build], so the field mapping — previously a nested
 * combine over `List<Any>` with a cast per field — is testable without a
 * manager.
 */
object StreamStatusSnapshot {

    data class VideoInputs(
        val isStreaming: Boolean,
        val isWebActive: Boolean,
        val isServerRunning: Boolean,
        val url: String,
        val clientCount: Int,
    )

    data class AudioInputs(
        val isAudioActive: Boolean,
        val audioUrl: String,
        val isRtspActive: Boolean,
        val rtspUrl: String,
    )

    fun build(
        video: VideoInputs,
        audio: AudioInputs,
        isWebEnabled: Boolean,
        isRtspEnabled: Boolean,
    ): StreamStatus = StreamStatus(
        isActive = video.isStreaming,
        isWebActive = video.isWebActive,
        isServerRunning = video.isServerRunning,
        url = video.url,
        clientCount = video.clientCount,
        isAudioActive = audio.isAudioActive,
        audioUrl = audio.audioUrl,
        isRtspActive = audio.isRtspActive,
        rtspUrl = audio.rtspUrl,
        isWebEnabled = isWebEnabled,
        isRtspEnabled = isRtspEnabled,
    )
}
