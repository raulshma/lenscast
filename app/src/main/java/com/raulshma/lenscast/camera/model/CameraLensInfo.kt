package com.raulshma.lenscast.camera.model

import androidx.camera.core.CameraSelector

/**
 * Represents a selectable camera lens on the device.
 *
 * @param id Unique identifier for this camera (Camera2 camera ID)
 * @param label Human-readable name (e.g., "Wide", "Ultrawide", "Telephoto", "Front")
 * @param lensFacing CameraSelector lens facing constant
 * @param focalLength Focal length in mm (used for sorting / labeling)
 * @param cameraSelector CameraSelector that targets this specific camera
 * @param physicalCameraId Optional physical camera ID if this isn't the primary logical camera
 */
data class CameraLensInfo(
    val id: String,
    val label: String,
    val lensFacing: Int,
    val focalLength: Float,
    val cameraSelector: CameraSelector,
    val physicalCameraId: String? = null,
)
