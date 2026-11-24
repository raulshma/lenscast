package com.raulshma.lenscast.core

import android.util.Log
import com.raulshma.lenscast.streaming.SlidingRateWindow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two signals, never mixed (issue #25):
 *
 * The DRAIN RATE — per-frame bytes over how long the frame took to drain into
 * the HTTP server's buffer (`drainRateSamples`, bytes×8/drainMs, the last
 * [THROUGHPUT_WINDOW] frames). That is a congestion signal, not a stream
 * bitrate: a 50 KB frame that drained in 1 ms reads 400 000 "kbps" even
 * though the stream sent 50 KB per frame PERIOD. The adaptation ladders
 * consume it under drain-rate names ([getMinClientDrainRateKbps],
 * [getAvgClientDrainRateKbps], [getMeasuredBandwidthKbps]), with the
 * default-aware idle view the policies own.
 *
 * The MEASURED STREAM STATS — what the dashboard displays — come from each
 * client's [SlidingRateWindow]: (timestamp, bytes) samples over
 * [StreamDefaults.STATS_WINDOW_MS], so throughput and fps are measured from
 * the actual frame-record cadence and decay to 0 as samples age out. The
 * throughput accessors keep their "throughput" names because that is what
 * they now honestly are; the drain signal is renamed out of their way.
 *
 * Only the MJPEG lane feeds the per-client half; the encoded lane keeps its
 * own aggregate drain sampling below, and the encoded-output display rate
 * lives in the EncodedStreamHub's window.
 */
class NetworkQualityMonitor(
    /** Injected clock — the stat windows and caches read time only through it. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val clientStats = ConcurrentHashMap<String, ClientStats>()

    private val _activeClients = AtomicInteger(0)
    val activeClients: Int get() = _activeClients.get()

    private var totalBytesSent = 0L

    fun registerClient(clientId: String) {
        // putIfAbsent (API 9 via ConcurrentMap), not Map.compute — the default
        // method is API 24+ and this monitor must run on API 23.
        clientStats.putIfAbsent(clientId, ClientStats(rateWindow = SlidingRateWindow(nowMs = nowMs)))
        _activeClients.set(clientStats.size)
        Log.d(TAG, "Client registered: $clientId, active: ${_activeClients.get()}")
    }

    fun unregisterClient(clientId: String) {
        clientStats.remove(clientId)
        _activeClients.set(clientStats.size)
        Log.d(TAG, "Client unregistered: $clientId, active: ${_activeClients.get()}")
    }

    fun recordFrameSent(clientId: String, frameSizeBytes: Int, sendDurationMs: Long) {
        val stats = clientStats[clientId] ?: return
        val now = nowMs()
        synchronized(stats) {
            stats.framesSent++
            stats.bytesSent += frameSizeBytes
            stats.totalSendDurationMs += sendDurationMs

            // Drain-rate sample: the congestion signal (bytes over drain time),
            // same math and same bounded window as ever — nothing here feeds a
            // display number.
            if (sendDurationMs > 0) {
                val drainRateKbps = (frameSizeBytes * 8.0) / sendDurationMs.toDouble()
                stats.drainRateSamples.add(drainRateKbps.toInt())
                if (stats.drainRateSamples.size > THROUGHPUT_WINDOW) {
                    stats.drainRateSamples.removeAt(0)
                }
            }

            // Display sample: (timestamp, bytes) into the sliding window — the
            // measured throughput/fps the dashboard shows.
            stats.rateWindow.record(now, frameSizeBytes.toLong())

            stats.lastFrameSizeBytes = frameSizeBytes
            stats.lastSendDurationMs = sendDurationMs
        }

        synchronized(this) {
            totalBytesSent += frameSizeBytes
        }
    }

    /** The client's avg drain rate (congestion signal, NOT a stream bitrate); 0 with no samples. */
    fun getClientDrainRateKbps(clientId: String): Int {
        val stats = clientStats[clientId] ?: return 0
        synchronized(stats) {
            if (stats.drainRateSamples.isEmpty()) return 0
            return stats.drainRateSamples.average().toInt()
        }
    }

    /** The client's measured stream throughput from its sliding window; 0 before frames flow or after they decay. */
    fun getClientThroughputKbps(clientId: String): Int {
        val stats = clientStats[clientId] ?: return 0
        synchronized(stats) {
            return stats.rateWindow.measuredBitrateKbps().toInt()
        }
    }

    // ── Encoded-sink throughput lane ──
    // The MJPEG lane above samples per browser client at the multipart write.
    // The encoded sinks (RTSP RTP fan-out, WS video fan-out, HLS segment
    // writes) carry their own bytes/time at their send seams, aggregated into
    // this lane — a deliberately separate sample store so the encoded-side
    // adaptation never re-weights the MJPEG per-client ladder (and vice
    // versa). Empty means "no encoded sink sent anything"; the ladder's
    // default-aware view applies.

    private val encodedThroughputSamples = ArrayDeque<Int>()

    /** One aggregate encoded-sink send: [bytes] over [durationMs] at a send seam. */
    fun recordEncodedSend(bytes: Int, durationMs: Long) {
        if (bytes <= 0 || durationMs <= 0) return
        val kbps = (bytes * 8.0 / durationMs.toDouble()).toInt()
        synchronized(encodedThroughputSamples) {
            encodedThroughputSamples.addLast(kbps)
            if (encodedThroughputSamples.size > ENCODED_SAMPLE_WINDOW) {
                encodedThroughputSamples.removeFirst()
            }
        }
    }

    /** Average encoded-sink drain rate; 0 while the lane has no samples. */
    fun getEncodedThroughputKbps(): Int = synchronized(encodedThroughputSamples) {
        if (encodedThroughputSamples.isEmpty()) 0 else encodedThroughputSamples.average().toInt()
    }

    fun hasEncodedSamples(): Boolean = synchronized(encodedThroughputSamples) { encodedThroughputSamples.isNotEmpty() }

    /**
     * The network-quality ladder over the encoded-sink lane: samples drive the
     * level; with no samples at all the ladder's default-aware view applies
     * (idle ⇒ EXCELLENT ⇒ the encoded bitrate target stays at its configured
     * value), never stalling at the bottom rung. Sample presence and
     * throughput read as one snapshot — two separate reads could disagree.
     */
    fun getEncodedQualityLevel(): NetworkQualityLevel {
        val hasSamples: Boolean
        val throughputKbps: Int
        synchronized(encodedThroughputSamples) {
            hasSamples = encodedThroughputSamples.isNotEmpty()
            throughputKbps = if (hasSamples) encodedThroughputSamples.average().toInt() else 0
        }
        return NetworkAdaptationPolicy.levelFor(
            minThroughputKbps = if (hasSamples) throughputKbps
            else NetworkAdaptationPolicy.DEFAULT_BANDWIDTH_KBPS,
            activeClients = if (hasSamples) 1 else 0,
        )
    }

    /** Slowest client's avg drain rate — the MJPEG ladder's congestion input; 0 with no drain samples. */
    fun getMinClientDrainRateKbps(): Int = minClientDrainRateSampled() ?: 0

    /**
     * The min per-client avg drain rate, null when NO client has drain
     * samples. Presence-gated, not value-gated: a stream of zero-byte frames
     * samples 0 kbps and must read 0 (CRITICAL rung), not fall back to the
     * ladder's default.
     */
    private fun minClientDrainRateSampled(): Int? {
        var minDrainRate = Int.MAX_VALUE
        var hasData = false
        for (stats in clientStats.values) {
            synchronized(stats) {
                if (stats.drainRateSamples.isNotEmpty()) {
                    hasData = true
                    val avg = stats.drainRateSamples.average().toInt()
                    if (avg < minDrainRate) minDrainRate = avg
                }
            }
        }
        return if (hasData) minDrainRate else null
    }

    /** Avg per-client drain rate across clients with drain samples; 0 with none (never a default constant). */
    fun getAvgClientDrainRateKbps(): Int {
        var total = 0L
        var count = 0
        for (stats in clientStats.values) {
            synchronized(stats) {
                if (stats.drainRateSamples.isNotEmpty()) {
                    total += stats.drainRateSamples.average().toLong()
                    count++
                }
            }
        }
        return if (count > 0) (total / count).toInt() else 0
    }

    /**
     * The measured aggregate client throughput for display: the sliding
     * windows' avg across clients with in-window frames, 0 while no client
     * has frames inside its window (idle, or connected-but-silent, or silence
     * past the window). Never invents a number — the ladder's default-aware
     * view lives in [getNetworkQualityLevel], not here.
     */
    fun getAvgClientThroughputKbps(): Int {
        var total = 0L
        var count = 0
        for (stats in clientStats.values) {
            synchronized(stats) {
                if (stats.rateWindow.sampleCount() > 0) {
                    total += stats.rateWindow.measuredBitrateKbps().toLong()
                    count++
                }
            }
        }
        return if (count > 0) (total / count).toInt() else 0
    }

    /** The measured min across clients' windows; 0 with no measured throughput anywhere. */
    fun getMinClientThroughputKbps(): Int {
        var minThroughput = Int.MAX_VALUE
        var hasData = false
        for (stats in clientStats.values) {
            synchronized(stats) {
                if (stats.rateWindow.sampleCount() > 0) {
                    hasData = true
                    val measured = stats.rateWindow.measuredBitrateKbps().toInt()
                    if (measured < minThroughput) minThroughput = measured
                }
            }
        }
        return if (hasData) minThroughput else 0
    }

    /**
     * The MJPEG ladder's congestion estimate: the drain-rate avg under the
     * 0-while-idle display rule. Consumed by the AdaptiveBitrateController's
     * published state — it estimates congestion from how long frames took to
     * drain, which is deliberately NOT the window-measured stream bitrate.
     */
    fun getMeasuredBandwidthKbps(): Int {
        return NetworkAdaptationPolicy.displayKbps(
            measuredKbps = getAvgClientDrainRateKbps(),
            hasActiveClients = _activeClients.get() > 0,
        )
    }

    fun getWorstClientLatencyMs(): Long {
        if (clientStats.isEmpty()) return 0
        return clientStats.values.maxOfOrNull { stats ->
            synchronized(stats) { stats.lastSendDurationMs }
        } ?: 0
    }

    fun getAvgFrameSizeBytes(): Int {
        if (clientStats.isEmpty()) return 0
        val sizes = clientStats.values.mapNotNull { stats ->
            synchronized(stats) {
                if (stats.lastFrameSizeBytes > 0) stats.lastFrameSizeBytes else null
            }
        }
        if (sizes.isEmpty()) return 0
        return sizes.average().toInt()
    }

    fun getTotalBytesSent(): Long = synchronized(this) { totalBytesSent }

    /** The client's measured frame rate from its window's record cadence; 0.0 with no in-window frames. */
    fun getFramesPerSecond(clientId: String): Double {
        val stats = clientStats[clientId] ?: return 0.0
        synchronized(stats) {
            return stats.rateWindow.measuredFps()
        }
    }

    /**
     * The MJPEG ladder verdict, default-aware exactly like the encoded lane's:
     * with no drain samples the ladder assumes [NetworkAdaptationPolicy.DEFAULT_BANDWIDTH_KBPS]
     * rather than stalling at the bottom rung — the default never leaks into a
     * display accessor, it only shapes this verdict.
     */
    fun getNetworkQualityLevel(): NetworkQualityLevel {
        return NetworkAdaptationPolicy.levelFor(
            minThroughputKbps = minClientDrainRateSampled()
                ?: NetworkAdaptationPolicy.DEFAULT_BANDWIDTH_KBPS,
            activeClients = _activeClients.get(),
        )
    }

    private var cachedQualityLevel: NetworkQualityLevel? = null
    private var cachedQualityLevelTime = 0L
    private val CACHE_VALIDITY_MS = 500L

    private fun getCachedQualityLevel(): NetworkQualityLevel {
        val now = nowMs()
        val cached = cachedQualityLevel
        if (cached != null && now - cachedQualityLevelTime < CACHE_VALIDITY_MS) {
            return cached
        }
        val fresh = getNetworkQualityLevel()
        cachedQualityLevel = fresh
        cachedQualityLevelTime = now
        return fresh
    }

    fun getAdaptiveQuality(baseQuality: Int, thermalAdjustedQuality: Int): Int {
        return NetworkAdaptationPolicy.qualityFor(
            level = getCachedQualityLevel(),
            thermalAdjustedQuality = thermalAdjustedQuality,
            baseQuality = baseQuality,
        )
    }

    fun getAdaptiveFrameInterval(baseIntervalMs: Long, thermalAdjustedIntervalMs: Long): Long {
        return NetworkAdaptationPolicy.intervalFor(
            level = getCachedQualityLevel(),
            baseIntervalMs = baseIntervalMs,
            thermalAdjustedIntervalMs = thermalAdjustedIntervalMs,
        )
    }

    fun resetStats() {
        clientStats.clear()
        _activeClients.set(0)
        synchronized(encodedThroughputSamples) { encodedThroughputSamples.clear() }
        synchronized(this) {
            totalBytesSent = 0L
        }
    }

    fun getStatsSnapshot(): NetworkStatsSnapshot {
        val clientDetails = clientStats.mapValues { (id, stats) ->
            synchronized(stats) {
                ClientStatsSnapshot(
                    framesSent = stats.framesSent,
                    bytesSent = stats.bytesSent,
                    // Display truth: the client's measured window throughput,
                    // 0 before frames flow or after they age out.
                    avgThroughputKbps = stats.rateWindow.measuredBitrateKbps().toInt(),
                    lastFrameSizeBytes = stats.lastFrameSizeBytes,
                    lastSendDurationMs = stats.lastSendDurationMs,
                )
            }
        }
        return NetworkStatsSnapshot(
            activeClients = _activeClients.get(),
            // The ladder's congestion estimate (drain-based, 0-while-idle) —
            // deliberately not the window-measured bitrate; see
            // getMeasuredBandwidthKbps.
            estimatedBandwidthKbps = getMeasuredBandwidthKbps(),
            totalBytesSent = getTotalBytesSent(),
            // Display fields: measured from the sliding windows, 0 whenever
            // nothing is inside them — no invented constant, and no frozen
            // last value (samples age out).
            minThroughputKbps = getMinClientThroughputKbps(),
            avgThroughputKbps = getAvgClientThroughputKbps(),
            worstLatencyMs = getWorstClientLatencyMs(),
            qualityLevel = getNetworkQualityLevel(),
            clientDetails = clientDetails,
            avgFrameSizeBytes = if (clientDetails.isNotEmpty()) clientDetails.values.map { it.lastFrameSizeBytes }.average().toInt() else 0,
        )
    }

    data class ClientStats(
        var framesSent: Long = 0,
        var bytesSent: Long = 0,
        var totalSendDurationMs: Long = 0,
        /** Per-frame drain rates (bytes×8/drainMs, kbps) — the congestion signal, NOT a stream bitrate. */
        val drainRateSamples: MutableList<Int> = mutableListOf(),
        /** The measured-display window: (timestamp, bytes) samples, [StreamDefaults.STATS_WINDOW_MS] deep. */
        val rateWindow: SlidingRateWindow,
        var lastFrameSizeBytes: Int = 0,
        var lastSendDurationMs: Long = 0,
    )

    data class ClientStatsSnapshot(
        val framesSent: Long,
        val bytesSent: Long,
        val avgThroughputKbps: Int,
        val lastFrameSizeBytes: Int,
        val lastSendDurationMs: Long,
    )

    data class NetworkStatsSnapshot(
        val activeClients: Int,
        val estimatedBandwidthKbps: Int,
        val totalBytesSent: Long,
        val minThroughputKbps: Int,
        val avgThroughputKbps: Int,
        val worstLatencyMs: Long,
        val qualityLevel: NetworkQualityLevel,
        val clientDetails: Map<String, ClientStatsSnapshot>,
        val avgFrameSizeBytes: Int,
    )

    enum class NetworkQualityLevel {
        EXCELLENT, GOOD, FAIR, POOR, CRITICAL
    }

    companion object {
        private const val TAG = "NetworkQualityMonitor"
        private const val THROUGHPUT_WINDOW = 20
        private const val ENCODED_SAMPLE_WINDOW = 20
    }
}
