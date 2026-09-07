package com.raulshma.lenscast.camera.model

import androidx.compose.ui.graphics.Color
import com.raulshma.lenscast.core.NetworkQualityMonitor.ClientStatsSnapshot
import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkQualityLevel
import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkStatsSnapshot
import com.raulshma.lenscast.core.ThermalState
import java.util.Locale

/**
 * The pure camera-dashboard verdicts and display formats: the wifi banner
 * predicate + message, the server-status tier and status text ladders, the
 * stream-shutter button's shared web/RTSP ladder, the thermal banner, the
 * network-quality badge, the connection panel's visibility verdict and stat
 * rows, the client summary line, byte formatting, and the slider
 * endpoint/value labels. The screen only maps policy data onto colors and
 * composables — every branch here is JVM-testable. Ladder colors that depend
 * on the theme (or the theme-adjacent overlay palette) are expressed as named
 * tiers; fixed colors are plain [Color] values, which the JVM can evaluate.
 */
object CameraDashboardPolicy {

    // ── Wifi banner ──

    /** The banner shows only when the server runs but the device left wifi. */
    fun shouldShowWifiBanner(wifiConnected: Boolean, isServerRunning: Boolean): Boolean =
        !wifiConnected && isServerRunning

    /** Shorter message while a stream is live — reachability still matters less than the live session. */
    fun wifiBannerMessage(streamActive: Boolean): String =
        if (streamActive) "Not on WiFi" else "Not on WiFi — server may not be reachable"

    // ── Server status ──

    /** The icon-tint ladder as named tiers; the screen maps each tier onto its color. */
    enum class ServerStatusTier { LIVE, READY, OFFLINE }

    fun serverStatusTier(isActive: Boolean, isServerRunning: Boolean): ServerStatusTier = when {
        isActive -> ServerStatusTier.LIVE
        isServerRunning -> ServerStatusTier.READY
        else -> ServerStatusTier.OFFLINE
    }

    /** The status text ladder: connected viewers win over live, live over ready. */
    fun serverStatusText(clientCount: Int, isActive: Boolean, isServerRunning: Boolean): String = when {
        clientCount > 0 -> "$clientCount viewer(s) connected"
        isActive -> "Live stream active"
        isServerRunning -> "Server ready"
        else -> "Offline"
    }

    // ── Stream shutter button ──

    /** The stream-shutter button's background as named tiers; the screen maps each tier onto its color. */
    enum class StreamShutterContainer { RECORDING, ENABLED, DISABLED }

    /** The shared web/RTSP shutter button's state: container tier, icon tint, label, and click gate. */
    data class StreamShutterVisual(
        val container: StreamShutterContainer,
        val tint: Color,
        val contentDescription: String,
        val clickEnabled: Boolean,
    ) {
        companion object {

            /**
             * The web/RTSP shutter buttons' one ladder: streaming red beats the
             * enabled dim, which beats the disabled ghost; the tint stays white
             * while the stream is usable; Stop vs Start by state; the click
             * passes only when the stream is enabled (a live stream stays
             * stoppable even after its toggle is disabled).
             */
            fun of(isStreaming: Boolean, isEnabled: Boolean, streamName: String): StreamShutterVisual =
                StreamShutterVisual(
                    container = when {
                        isStreaming -> StreamShutterContainer.RECORDING
                        isEnabled -> StreamShutterContainer.ENABLED
                        else -> StreamShutterContainer.DISABLED
                    },
                    tint = if (isEnabled || isStreaming) Color.White else Color.White.copy(alpha = 0.35f),
                    contentDescription = "${if (isStreaming) "Stop" else "Start"} $streamName stream",
                    clickEnabled = isEnabled,
                )
        }
    }

    // ── Thermal banner ──

    /** The thermal ladder as named tiers; the screen maps each tier onto its color. */
    enum class ThermalSeverity { MODERATE, SEVERE, CRITICAL }

    data class ThermalBanner(val severity: ThermalSeverity, val label: String)

    /** Null for NORMAL/LIGHT — no banner below moderate warming. */
    fun thermalBanner(state: ThermalState): ThermalBanner? = when (state) {
        ThermalState.MODERATE -> ThermalBanner(ThermalSeverity.MODERATE, "Thermal: Moderate")
        ThermalState.SEVERE -> ThermalBanner(ThermalSeverity.SEVERE, "Thermal: Severe")
        ThermalState.CRITICAL -> ThermalBanner(ThermalSeverity.CRITICAL, "Thermal: Critical!")
        else -> null
    }

    // ── Network quality ──

    data class QualityBadge(val color: Color, val abbreviation: String)

    fun qualityBadge(level: NetworkQualityLevel): QualityBadge = when (level) {
        NetworkQualityLevel.EXCELLENT -> QualityBadge(Color(0xFF4CAF50), "EXC")
        NetworkQualityLevel.GOOD -> QualityBadge(Color(0xFF8BC34A), "GOOD")
        NetworkQualityLevel.FAIR -> QualityBadge(Color(0xFFFFC107), "FAIR")
        NetworkQualityLevel.POOR -> QualityBadge(Color(0xFFFF9800), "POOR")
        NetworkQualityLevel.CRITICAL -> QualityBadge(Color(0xFFF44336), "CRIT")
    }

    /** The collapsed per-client line; the screen hides it while no client is connected. */
    fun clientSummary(activeClients: Int, minThroughputKbps: Int): String =
        "$activeClients client${if (activeClients != 1) "s" else ""} · ${minThroughputKbps}kbps"

    // ── Connection panel ──

    /** The indicator collapses into the corner only while a stream runs with adaptation on. */
    fun qualityIndicatorVisible(streamStatusActive: Boolean, adaptiveEnabled: Boolean): Boolean =
        streamStatusActive && adaptiveEnabled

    /** The collapsed indicator's quality/fps line under the badge. */
    fun qualitySummary(quality: Int, fps: Int): String = "${quality}q ${fps}fps"

    /** One label/value cell of the expanded panel, in render order. */
    data class ConnectionStatRow(val label: String, val value: String)

    /**
     * The expanded panel's stat rows, from Bandwidth down to Total Sent (the
     * quality badge renders its own row). Frame sizes go through
     * [formatBytes] — the panel keeps no second byte formatter.
     */
    fun connectionStatRows(estimatedBandwidthKbps: Int, stats: NetworkStatsSnapshot): List<ConnectionStatRow> =
        listOf(
            ConnectionStatRow("Bandwidth", "$estimatedBandwidthKbps kbps"),
            ConnectionStatRow("Min Throughput", "${stats.minThroughputKbps} kbps"),
            ConnectionStatRow("Avg Throughput", "${stats.avgThroughputKbps} kbps"),
            ConnectionStatRow("Latency", "${stats.worstLatencyMs} ms"),
            ConnectionStatRow("Avg Frame", formatBytes(stats.avgFrameSizeBytes.toLong())),
            ConnectionStatRow("Clients", "${stats.activeClients}"),
            ConnectionStatRow("Total Sent", formatBytes(stats.totalBytesSent)),
        )

    /** The per-client header with the id truncated to its first eight characters. */
    fun clientStatHeader(clientId: String): String = "Client ${clientId.take(8)}:"

    /** One client's stat rows inside the expanded panel; its frame size goes through [formatBytes]. */
    fun clientStatRows(detail: ClientStatsSnapshot): List<ConnectionStatRow> =
        listOf(
            ConnectionStatRow("  Frames", "${detail.framesSent}"),
            ConnectionStatRow("  Throughput", "${detail.avgThroughputKbps} kbps"),
            ConnectionStatRow("  Latency", "${detail.lastSendDurationMs} ms"),
            ConnectionStatRow("  Frame Size", formatBytes(detail.lastFrameSizeBytes.toLong())),
        )

    // ── Formats ──

    /** Whole bytes/KB/MB/GB with integer division, matching the transmitted counters. */
    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }

    /** Slider range endpoint: the float rendered whole when it is one ("12.0" → "12"). */
    fun sliderEndpoint(value: Float): String =
        "$value".let { if (it.endsWith(".0")) it.dropLast(2) else it }

    /** Slider value label: integer when whole, else one decimal — pinned to [Locale.US]. */
    fun sliderValueLabel(value: Float): String =
        if (value == value.toInt().toFloat()) "${value.toInt()}" else String.format(Locale.US, "%.1f", value)
}
