package com.raulshma.lenscast.core

enum class ThermalState {
    NORMAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
}

data class ThermalThrottlingResult(
    val bitrateMultiplier: Float,
    val frameRateMultiplier: Float,
    val jpegQuality: Int,
    val shouldPause: Boolean,
)
