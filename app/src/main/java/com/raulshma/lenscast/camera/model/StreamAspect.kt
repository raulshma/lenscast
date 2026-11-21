package com.raulshma.lenscast.camera.model

/**
 * The streaming analysis window's aspect, steered by the selected encode
 * resolution's aspect (the RTSP resolution setting). Lives in camera/model
 * so the camera module never depends on streaming; the resolution mapper
 * carries the value across.
 */
enum class StreamAspect {
    WIDE_16_9, STANDARD_4_3
}

/**
 * The encode tier the streaming analysis window follows: the RTSP
 * resolution setting's dimensions and aspect. The camera binds its
 * ImageAnalysis use case up to this size (never above the capture), so the
 * MJPEG/WHEP previews and the encoded hub share one tier — a 1080p encode
 * setting raises the preview instead of leaving it at the 720p window.
 */
data class StreamTarget(
    val width: Int,
    val height: Int,
    val aspect: StreamAspect,
)
