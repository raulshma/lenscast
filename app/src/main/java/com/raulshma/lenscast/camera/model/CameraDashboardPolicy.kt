package com.raulshma.lenscast.camera.model

import androidx.compose.ui.graphics.Color
import com.raulshma.lenscast.R
import com.raulshma.lenscast.core.NetworkQualityMonitor.ClientStatsSnapshot
import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkQualityLevel
import com.raulshma.lenscast.core.NetworkQualityMonitor.NetworkStatsSnapshot
import com.raulshma.lenscast.core.ThermalState
import java.util.Locale

/**
 * The pure camera-dashboard verdicts and display formats: the wifi banner
 * predicate + variant, the server-status tier and status-line ladders, the
 * stream-shutter button's shared web/RTSP ladder, the thermal ladder, the
 * network-quality badge color ladder, the connection panel's visibility
 * verdict and stat rows, byte formatting, and the slider endpoint/value
 * labels. The screen only maps policy data onto colors, string resources,
 * and composables — every branch here is JVM-testable, and no user-facing
 * prose lives here (l10n strings are the screen's [R.string] lookups).
 * Ladder colors that depend on the theme (or the theme-adjacent overlay
 * palette) are expressed as named tiers; fixed colors are plain [Color]
 * values, which the JVM can evaluate.
 */
object CameraDashboardPolicy {

    // ── Wifi banner ──

    /** The banner shows only when the server runs but the device left wifi. */
    fun shouldShowWifiBanner(wifiConnected: Boolean, isServerRunning: Boolean): Boolean =
        !wifiConnected && isServerRunning

    /** Shorter banner while a stream is live — reachability still matters less than the live session. */
    enum class WifiBannerVariant { STREAM_ACTIVE, STREAM_IDLE }

    fun wifiBannerVariant(streamActive: Boolean): WifiBannerVariant =
        if (streamActive) WifiBannerVariant.STREAM_ACTIVE else WifiBannerVariant.STREAM_IDLE

    // ── Server status ──

    /** The icon-tint ladder as named tiers; the screen maps each tier onto its color. */
    enum class ServerStatusTier { LIVE, READY, OFFLINE }

    fun serverStatusTier(isActive: Boolean, isServerRunning: Boolean): ServerStatusTier = when {
        isActive -> ServerStatusTier.LIVE
        isServerRunning -> ServerStatusTier.READY
        else -> ServerStatusTier.OFFLINE
    }

    /** The status line's data: the tier picks the text ladder, connected viewers win over plain live. */
    data class ServerStatusLine(val tier: ServerStatusTier, val viewerCount: Int)

    fun serverStatusLine(clientCount: Int, isActive: Boolean, isServerRunning: Boolean): ServerStatusLine = ServerStatusLine(
        tier = serverStatusTier(isActive, isServerRunning),
        viewerCount = clientCount,
    )

    // ── Stream shutter button ──

    /** The stream-shutter button's background as named tiers; the screen maps each tier onto its color. */
    enum class StreamShutterContainer { RECORDING, ENABLED, DISABLED }

    /** The stream-shutter buttons' stream identity, for the screen's localized label lookups. */
    enum class StreamKind { WEB, RTSP }

    /** The shared web/RTSP shutter button's state: container tier, icon tint, stream kind, and click gate. */
    data class StreamShutterVisual(
        val container: StreamShutterContainer,
        val tint: Color,
        val kind: StreamKind,
        val clickEnabled: Boolean,
    ) {
        companion object {

            /**
             * The web/RTSP shutter buttons' one ladder: streaming red beats the
             * enabled dim, which beats the disabled ghost; the tint stays white
             * while the stream is usable; Stop vs Start by state (the screen
             * reads it off the RECORDING tier); the click passes only when the
             * stream is enabled (a live stream stays stoppable even after its
             * toggle is disabled).
             */
            fun of(isStreaming: Boolean, isEnabled: Boolean, kind: StreamKind): StreamShutterVisual =
                StreamShutterVisual(
                    container = when {
                        isStreaming -> StreamShutterContainer.RECORDING
                        isEnabled -> StreamShutterContainer.ENABLED
                        else -> StreamShutterContainer.DISABLED
                    },
                    tint = if (isEnabled || isStreaming) Color.White else Color.White.copy(alpha = 0.35f),
                    kind = kind,
                    clickEnabled = isEnabled,
                )
        }
    }

    // ── Thermal banner ──

    /** The thermal ladder as named tiers; the screen maps each tier onto its color and label. */
    enum class ThermalSeverity { MODERATE, SEVERE, CRITICAL }

    /** Null for NORMAL/LIGHT — no banner below moderate warming. */
    fun thermalBanner(state: ThermalState): ThermalSeverity? = when (state) {
        ThermalState.MODERATE -> ThermalSeverity.MODERATE
        ThermalState.SEVERE -> ThermalSeverity.SEVERE
        ThermalState.CRITICAL -> ThermalSeverity.CRITICAL
        else -> null
    }

    // ── Network quality ──

    /** The collapsed badge's color ladder; the screen reads the label off [NetworkQualityLevel] itself. */
    data class QualityBadge(val color: Color)

    fun qualityBadge(level: NetworkQualityLevel): QualityBadge = when (level) {
        NetworkQualityLevel.EXCELLENT -> QualityBadge(Color(0xFF4CAF50))
        NetworkQualityLevel.GOOD -> QualityBadge(Color(0xFF8BC34A))
        NetworkQualityLevel.FAIR -> QualityBadge(Color(0xFFFFC107))
        NetworkQualityLevel.POOR -> QualityBadge(Color(0xFFFF9800))
        NetworkQualityLevel.CRITICAL -> QualityBadge(Color(0xFFF44336))
    }

    // ── Connection panel ──

    /** The indicator collapses into the corner only while a stream runs with adaptation on. */
    fun qualityIndicatorVisible(streamStatusActive: Boolean, adaptiveEnabled: Boolean): Boolean =
        streamStatusActive && adaptiveEnabled

    /** The collapsed indicator's quality/fps line under the badge. */
    fun qualitySummary(quality: Int, fps: Int): String = "${quality}q ${fps}fps"

    /** One label/value cell of the expanded panel, in render order; the screen resolves [labelRes]. */
    data class ConnectionStatRow(val labelRes: Int, val value: String)

    /**
     * The expanded panel's stat rows, from Bandwidth down to Total Sent (the
     * quality badge renders its own row). Frame sizes go through
     * [formatBytes] — the panel keeps no second byte formatter.
     */
    fun connectionStatRows(estimatedBandwidthKbps: Int, stats: NetworkStatsSnapshot): List<ConnectionStatRow> =
        listOf(
            ConnectionStatRow(R.string.camera_stat_bandwidth, "$estimatedBandwidthKbps kbps"),
            ConnectionStatRow(R.string.camera_stat_min_throughput, "${stats.minThroughputKbps} kbps"),
            ConnectionStatRow(R.string.camera_stat_avg_throughput, "${stats.avgThroughputKbps} kbps"),
            ConnectionStatRow(R.string.camera_stat_latency, "${stats.worstLatencyMs} ms"),
            ConnectionStatRow(R.string.camera_stat_avg_frame, formatBytes(stats.avgFrameSizeBytes.toLong())),
            ConnectionStatRow(R.string.camera_stat_clients, "${stats.activeClients}"),
            ConnectionStatRow(R.string.camera_stat_total_sent, formatBytes(stats.totalBytesSent)),
        )

    /** The per-client header's id, truncated to its first eight characters. */
    fun clientIdPrefix(clientId: String): String = clientId.take(8)

    /** One client's stat rows inside the expanded panel; its frame size goes through [formatBytes]. */
    fun clientStatRows(detail: ClientStatsSnapshot): List<ConnectionStatRow> =
        listOf(
            ConnectionStatRow(R.string.camera_stat_frames, "${detail.framesSent}"),
            ConnectionStatRow(R.string.camera_stat_throughput, "${detail.avgThroughputKbps} kbps"),
            ConnectionStatRow(R.string.camera_stat_latency, "${detail.lastSendDurationMs} ms"),
            ConnectionStatRow(R.string.camera_stat_frame_size, formatBytes(detail.lastFrameSizeBytes.toLong())),
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
