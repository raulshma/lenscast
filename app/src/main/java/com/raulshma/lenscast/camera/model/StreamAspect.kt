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