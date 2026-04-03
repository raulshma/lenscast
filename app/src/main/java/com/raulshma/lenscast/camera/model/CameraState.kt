package com.raulshma.lenscast.camera.model

sealed class CameraState {
    data object Idle : CameraState()
    data object Initializing : CameraState()
    data object RequestPermission : CameraState()
    data object Ready : CameraState()
    data class Error(val message: String) : CameraState()
}

data class StreamStatus(
    val isActive: Boolean = false,
    val isServerRunning: Boolean = false,
    val url: String = "",
    val clientCount: Int = 0,
    val isAudioActive: Boolean = false,
    val audioUrl: String = "",
    val isRtspActive: Boolean = false,
    val rtspUrl: String = "",
    val isWebEnabled: Boolean = true,
    val isRtspEnabled: Boolean = false,
)
