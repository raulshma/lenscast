package com.raulshma.lenscast.capture.model

/**
 * The pure decisions around the persisted [FlashMode] setting — the main
 * shutter's flash request, shared by the camera screen's cycling chip and any
 * other writer. The mapping onto CameraX's `ImageCapture.FLASH_MODE_*`
 * constants lives at the capture seam (the `when` over this enum next to the
 * `captureToGallery` call), because those constants are Android classes; the
 * cycle order — the one decision a caller would otherwise re-derive — is
 * pinned here.
 */
object FlashModePolicy {

    /**
     * The mode a tap on the flash chip moves to: OFF → AUTO → ON → OFF — the
     * camera-app convention, ordered weakest to strongest so a mis-tap lands
     * on a safer mode than "fire the flash".
     */
    fun next(mode: FlashMode): FlashMode = when (mode) {
        FlashMode.OFF -> FlashMode.AUTO
        FlashMode.AUTO -> FlashMode.ON
        FlashMode.ON -> FlashMode.OFF
    }
}
