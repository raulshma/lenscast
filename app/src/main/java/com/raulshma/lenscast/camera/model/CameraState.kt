package com.raulshma.lenscast.camera.model

sealed class CameraState {
    data object Idle : CameraState()
    data object Initializing : CameraState()
    data object RequestPermission : CameraState()
    data object Ready : CameraState()

    /**
     * A published camera error. [recovery] is the [CameraErrorPolicy] verdict
     * for CameraX `StateError`s the open-state observer acted on (blueprint
     * P0 item 4) — the dashboard's one field that distinguishes "will
     * self-heal" (REBIND/WAIT_CAMERA_ADDED/CAMERAX_HANDLES) from "reboot the
     * phone" (REBOOT_REQUIRED); null for errors raised outside that path
     * (permission gaps, bind exceptions), which have no taxonomy verdict.
     * [toString] renders the verdict only when present, so the API's
     * `cameraStateName` (the data-class toString the status handler ships)
     * is unchanged for every legacy error.
     */
    data class Error(
        val message: String,
        val recovery: CameraErrorPolicy.Verdict? = null,
    ) : CameraState() {
        override fun toString(): String =
            if (recovery == null) "Error(message=$message)" else "Error(message=$message, recovery=$recovery)"
    }
}

data class StreamStatus(
    val isActive: Boolean = false,
    val isWebActive: Boolean = false,
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

/**
 * The manual reset-camera lever's typed outcome (blueprint P1 item 10):
 * [verdict] is the [CameraErrorPolicy] answer that drove the attempt,
 * [bound] whether a rebind actually ran and bound — null when no rebind was
 * attempted (the taxonomy or the spent budget answered instead), false when
 * the bind was attempted and denied (the locked-mount while-in-use denial
 * being the headline), true when the camera bound again.
 */
data class CameraResetResult(
    val verdict: CameraErrorPolicy.Verdict,
    val bound: Boolean? = null,
)
