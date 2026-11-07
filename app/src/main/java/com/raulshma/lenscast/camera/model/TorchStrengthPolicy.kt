package com.raulshma.lenscast.camera.model

/**
 * Pure decisions for the API 33+ torch strength API.
 *
 * Why this exists: `CameraControl.enableTorch` is binary — on or off. API 33
 * (Android 13) added `CameraManager.turnOnTorchWithStrengthLevel(cameraId, level)`
 * plus the
 * `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` characteristic, which let a multi-level
 * flash run below full power. The strength path is CameraManager-level (it
 * works without a bound camera and turns the torch ON as a side effect when
 * it was off), so CameraService only drives it while the torch should be
 * ON, riding the same `applyCameraControls` re-assert seam that keeps the
 * binary torch alive across rebinds.
 *
 * Why 33, not CameraX's gate: CameraX 1.5's `CameraControl.setTorchStrengthLevel`
 * wraps only the API 35 `CaptureRequest.FLASH_STRENGTH_LEVEL` request key and is
 * API-35-only, but the CameraManager pair this policy drives exists since
 * Android 13 — that mismatch is exactly how a "35" gate once locked out
 * capable Android 13 devices (issue #16; full sourcing in
 * docs/research/torch-strength.md).
 *
 * The no-op ladder (every rung returns null = "don't call the strength API;
 * plain enableTorch already gives the user everything the device can do"):
 *
 * 1. Unsupported SDK (< 33) — the strength API does not exist.
 * 2. No requested strength ([requestedStrength] null — the persisted default,
 *    "device default brightness").
 * 3. The device's max level is unknown ([deviceMaxLevel] null — the
 *    characteristic is missing or the bind-time query failed) or is a
 *    single-level flash (max <= 1): there is exactly one brightness and
 *    enableTorch already produces it.
 * 4. Otherwise the requested level clamped into [MIN_LEVEL]..[deviceMaxLevel].
 *    The device's real max always wins as the ceiling: MAX_REQUESTED_LEVEL is
 *    the catalog/persistence bound (what the settings layer may store), not an
 *    apply-time ceiling — a raw request above it is clamped to whatever the
 *    hardware actually supports.
 */
object TorchStrengthPolicy {
    /** The strength floor per the API 33 contract (FLASH_INFO_STRENGTH_DEFAULT_LEVEL is 1-based). */
    const val MIN_LEVEL = 1

    /**
     * The sanitize bound the persistence/catalog layer applies before a
     * strength is stored. NOT an apply-time ceiling — the device's real max
     * ([CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL]) always wins
     * at apply time.
     */
    const val MAX_REQUESTED_LEVEL = 8

    /** True when this device/API level can control torch strength at all. */
    fun isSupported(sdkInt: Int): Boolean = sdkInt >= 33

    /**
     * The level to request, or null for "don't call the strength API".
     * Ladder: unsupported SDK -> null; null/absent requested strength -> null;
     * device max level <= 1 (single-level flash) -> null (plain enableTorch already covers it);
     * otherwise requested clamped into 1..deviceMax.
     * [deviceMaxLevel] nullable: null = characteristic missing -> treat as unsupported -> null.
     */
    fun effectiveLevel(requestedStrength: Int?, deviceMaxLevel: Int?, sdkInt: Int): Int? {
        if (!isSupported(sdkInt)) return null
        if (requestedStrength == null) return null
        val deviceMax = deviceMaxLevel ?: return null
        if (deviceMax <= MIN_LEVEL) return null
        return requestedStrength.coerceIn(MIN_LEVEL, deviceMax)
    }
}
