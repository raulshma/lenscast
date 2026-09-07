package com.raulshma.lenscast.core

/**
 * The network-adaptation policy, pure: the quality-level ladder over measured
 * throughput, the network quality/fps factors the frame path adapts through,
 * and the 0-while-idle display rule. The [NetworkQualityMonitor] keeps the
 * sampling and bookkeeping (per-client stats, the level cache) and delegates
 * every decision here, so thresholds and factors are one edit and one test
 * surface.
 *
 * Display bandwidth stays 0 while nobody is connected while the adaptation
 * ladder keeps its default-aware view (see CONTEXT.md's Stream Quality
 * Policy entry) — [displayKbps] is the 0-while-idle rule's single home.
 */
object NetworkAdaptationPolicy {

    /** Throughput rungs of the quality ladder, in kbps. */
    const val GOOD_BANDWIDTH_THRESHOLD_KBPS = 3000
    const val FAIR_BANDWIDTH_THRESHOLD_KBPS = 1500
    const val POOR_BANDWIDTH_THRESHOLD_KBPS = 500

    /**
     * The adaptation ladder's default-aware view: with no samples at all
     * (idle or no client data), the ladder assumes this bandwidth rather
     * than stalling at the bottom rung.
     */
    const val DEFAULT_BANDWIDTH_KBPS = 5000

    /** The slowest frame rate the fps adaptation may settle at. */
    const val MIN_FPS = 3

    fun levelFor(
        minThroughputKbps: Int,
        activeClients: Int,
    ): NetworkQualityMonitor.NetworkQualityLevel = when {
        minThroughputKbps >= GOOD_BANDWIDTH_THRESHOLD_KBPS && activeClients <= 1 ->
            NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT
        minThroughputKbps >= GOOD_BANDWIDTH_THRESHOLD_KBPS ->
            NetworkQualityMonitor.NetworkQualityLevel.GOOD
        minThroughputKbps >= FAIR_BANDWIDTH_THRESHOLD_KBPS ->
            NetworkQualityMonitor.NetworkQualityLevel.FAIR
        minThroughputKbps >= POOR_BANDWIDTH_THRESHOLD_KBPS ->
            NetworkQualityMonitor.NetworkQualityLevel.POOR
        else -> NetworkQualityMonitor.NetworkQualityLevel.CRITICAL
    }

    /**
     * The JPEG quality for a network level: the thermal-adjusted quality
     * scales by the level's factor, clamped to the adaptive minimum and the
     * base quality's valid range, never above what thermal already allows.
     */
    fun qualityFor(
        level: NetworkQualityMonitor.NetworkQualityLevel,
        thermalAdjustedQuality: Int,
        baseQuality: Int,
    ): Int {
        val minQuality = StreamDefaults.ADAPTIVE_JPEG_QUALITY_MIN
        val maxQuality = baseQuality.coerceIn(StreamDefaults.JPEG_QUALITY_MIN, StreamDefaults.JPEG_QUALITY_MAX)

        val networkFactor = when (level) {
            NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT -> 1.0f
            NetworkQualityMonitor.NetworkQualityLevel.GOOD -> 0.9f
            NetworkQualityMonitor.NetworkQualityLevel.FAIR -> 0.75f
            NetworkQualityMonitor.NetworkQualityLevel.POOR -> 0.55f
            NetworkQualityMonitor.NetworkQualityLevel.CRITICAL -> 0.35f
        }

        val networkQuality = (thermalAdjustedQuality * networkFactor).toInt()
            .coerceIn(minQuality, maxQuality)

        return networkQuality.coerceAtMost(thermalAdjustedQuality)
    }

    /**
     * The frame interval for a network level: the thermal-adjusted interval
     * stands at full factor; lower factors slow the base frame rate down,
     * floored at [MIN_FPS] and never faster than thermal allows.
     */
    fun intervalFor(
        level: NetworkQualityMonitor.NetworkQualityLevel,
        baseIntervalMs: Long,
        thermalAdjustedIntervalMs: Long,
    ): Long {
        val fpsFactor = when (level) {
            NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT -> 1.0f
            NetworkQualityMonitor.NetworkQualityLevel.GOOD -> 1.0f
            NetworkQualityMonitor.NetworkQualityLevel.FAIR -> 0.75f
            NetworkQualityMonitor.NetworkQualityLevel.POOR -> 0.5f
            NetworkQualityMonitor.NetworkQualityLevel.CRITICAL -> 0.3f
        }

        if (fpsFactor >= 1.0f) return thermalAdjustedIntervalMs

        val baseFps = (1000f / baseIntervalMs)
        val adaptedFps = (baseFps * fpsFactor).coerceAtLeast(MIN_FPS.toFloat())
        val adaptedInterval = (1000f / adaptedFps).toLong()

        return adaptedInterval.coerceAtLeast(thermalAdjustedIntervalMs)
    }

    /**
     * The 0-while-idle display rule, once: measured bandwidth is shown as
     * exactly what was measured, and 0 while no client is sending — never
     * the adaptation ladder's default constant.
     */
    fun displayKbps(measuredKbps: Int, hasActiveClients: Boolean): Int =
        if (hasActiveClients) measuredKbps else 0
}
