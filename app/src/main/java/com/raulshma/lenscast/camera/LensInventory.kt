package com.raulshma.lenscast.camera

import androidx.camera.core.CameraSelector
import com.raulshma.lenscast.camera.model.CameraLensInfo

/**
 * The lens-inventory knowledge: labels, OEM-duplicate removal, ordering,
 * the main-lens default, the switch-cycle index, the empty-inventory
 * front/back toggle, and the enumeration-failure fallback. Pure —
 * the service keeps the provider iteration and the Camera2 reads, and
 * delegates every decision here, so lens behavior is testable without a
 * device.
 */
object LensInventory {

    fun buildLabel(lensFacing: Int, focalLength: Float, cameraId: String): String {
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            return "Front"
        }
        // Back camera label based on focal length ranges
        return when {
            focalLength <= 0f -> "Camera $cameraId"
            focalLength < 2.5f -> "Ultrawide"
            focalLength < 5f -> "Wide"
            focalLength < 8f -> "2x"
            focalLength < 15f -> "3x"
            focalLength < 25f -> "5x"
            else -> "${focalLength.toInt()}mm"
        }
    }

    /** OEMs often duplicate lenses sharing facing and focal length. */
    fun deduplicate(lenses: List<CameraLensInfo>): List<CameraLensInfo> =
        lenses.distinctBy { Pair(it.lensFacing, it.focalLength) }

    /** Back cameras by ascending focal length, then front cameras. */
    fun sortLenses(lenses: List<CameraLensInfo>): List<CameraLensInfo> =
        lenses.sortedWith(
            compareBy<CameraLensInfo> { it.lensFacing != CameraSelector.LENS_FACING_BACK }
                .thenBy { it.focalLength }
        )

    /**
     * The main logical back camera: the first back entry. Direct binding to
     * physical cameras on start causes black screens on many OEM drivers,
     * so the default must stay logical. 0 when there is no back lens —
     * callers fall back to the first entry, as before.
     */
    fun defaultBackIndex(sorted: List<CameraLensInfo>): Int =
        sorted.indexOfFirst { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            .coerceAtLeast(0)

    /**
     * The next lens in the camera-switch cycle: one forward, wrapping to 0
     * after the last entry. [size] must be non-empty — callers consult
     * [fallbackSelector] first when the inventory is empty.
     */
    fun nextIndex(currentIndex: Int, size: Int): Int = (currentIndex + 1) % size

    /**
     * The empty-inventory fallback: a plain front/back toggle — currently
     * front flips to the default back camera, anything else to the default
     * front camera. Returns the selector to bind and the facing it
     * represents.
     */
    fun fallbackSelector(currentFront: Boolean): Pair<CameraSelector, Boolean> =
        if (currentFront) {
            CameraSelector.DEFAULT_BACK_CAMERA to false
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA to true
        }

    fun fallbackLenses(): List<CameraLensInfo> = listOf(
        CameraLensInfo(
            id = "0",
            label = "Back",
            lensFacing = CameraSelector.LENS_FACING_BACK,
            focalLength = 0f,
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        ),
        CameraLensInfo(
            id = "1",
            label = "Front",
            lensFacing = CameraSelector.LENS_FACING_FRONT,
            focalLength = 0f,
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
        )
    )
}
