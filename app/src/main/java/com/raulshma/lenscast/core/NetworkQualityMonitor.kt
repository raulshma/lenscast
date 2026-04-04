package com.raulshma.lenscast.core

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class NetworkQualityMonitor {

    private val clientStats = ConcurrentHashMap<String, ClientStats>()
    private val _estimatedBandwidthKbps = AtomicInteger(DEFAULT_BANDWIDTH_KBPS)
    val estimatedBandwidthKbps: Int get() = _estimatedBandwidthKbps.get()

    private val _activeClients = AtomicInteger(0)
    val activeClients: Int get() = _activeClients.get()

    private var totalBytesSent = 0L
    private var lastBandwidthCalcTime = 0L
    private var lastBandwidthCalcBytes = 0L

    fun registerClient(clientId: String) {
        clientStats.compute(clientId) { _, existing ->
            existing ?: ClientStats()
        }
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
        synchronized(stats) {
            stats.framesSent++
            stats.bytesSent += frameSizeBytes
            stats.totalSendDurationMs += sendDurationMs

            if (sendDurationMs > 0) {
                val throughputKbps = (frameSizeBytes * 8.0) / sendDurationMs.toDouble()
                stats.throughputSamples.add(throughputKbps.toInt())
                if (stats.throughputSamples.size > THROUGHPUT_WINDOW) {
                    stats.throughputSamples.removeAt(0)
                }
            }

            stats.frameTimestamps.add(System.currentTimeMillis())
            if (stats.frameTimestamps.size > THROUGHPUT_WINDOW) {
                stats.frameTimestamps.removeAt(0)
            }

            stats.lastFrameTimeMs = System.currentTimeMillis()
            stats.lastFrameSizeBytes = frameSizeBytes
            stats.lastSendDurationMs = sendDurationMs
        }

        synchronized(this) {
            totalBytesSent += frameSizeBytes
        }
    }

    fun getClientThroughputKbps(clientId: String): Int {
        val stats = clientStats[clientId] ?: return 0
        synchronized(stats) {
            val samples = stats.throughputSamples
            if (samples.isEmpty()) return 0
            return samples.average().toInt()
        }
    }

    fun getMinClientThroughputKbps(): Int {
        if (clientStats.isEmpty()) return DEFAULT_BANDWIDTH_KBPS
        var minThroughput = Int.MAX_VALUE
        var hasData = false
        for (stats in clientStats.values) {
            synchronized(stats) {
                val samples = stats.throughputSamples
                if (samples.isNotEmpty()) {
                    hasData = true
                    val avg = samples.average().toInt()
                    if (avg < minThroughput) minThroughput = avg
                }
            }
        }
        return if (hasData) minThroughput else DEFAULT_BANDWIDTH_KBPS
    }

    fun getAvgClientThroughputKbps(): Int {
        if (clientStats.isEmpty()) return DEFAULT_BANDWIDTH_KBPS
        var total = 0L
        var count = 0
        for (stats in clientStats.values) {
            synchronized(stats) {
                val samples = stats.throughputSamples
                if (samples.isNotEmpty()) {
                    total += samples.average().toLong()
                    count++
                }
            }
        }
        return if (count > 0) (total / count).toInt() else DEFAULT_BANDWIDTH_KBPS
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

    fun getFramesPerSecond(clientId: String): Double {
        val stats = clientStats[clientId] ?: return 0.0
        synchronized(stats) {
            val timestamps = stats.frameTimestamps
            if (timestamps.size < 2) return 0.0
            val elapsed = timestamps.last() - timestamps.first()
            if (elapsed <= 0) return 0.0
            return (timestamps.size - 1) * 1000.0 / elapsed
        }
    }

    fun getNetworkQualityLevel(): NetworkQualityLevel {
        val minThroughput = getMinClientThroughputKbps()
        val activeCount = _activeClients.get()

        return when {
            minThroughput >= GOOD_BANDWIDTH_THRESHOLD_KBPS && activeCount <= 1 -> NetworkQualityLevel.EXCELLENT
            minThroughput >= GOOD_BANDWIDTH_THRESHOLD_KBPS -> NetworkQualityLevel.GOOD
            minThroughput >= FAIR_BANDWIDTH_THRESHOLD_KBPS -> NetworkQualityLevel.FAIR
            minThroughput >= POOR_BANDWIDTH_THRESHOLD_KBPS -> NetworkQualityLevel.POOR
            else -> NetworkQualityLevel.CRITICAL
        }
    }

    fun updateEstimatedBandwidth() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastBandwidthCalcTime
        if (elapsed < BANDWIDTH_CALC_INTERVAL_MS) return

        val bytesDelta = synchronized(this) {
            val delta = totalBytesSent - lastBandwidthCalcBytes
            lastBandwidthCalcBytes = totalBytesSent
            delta
        }
        lastBandwidthCalcTime = now

        if (elapsed > 0 && bytesDelta > 0) {
            val bandwidthKbps = ((bytesDelta * 8L) / elapsed).toInt().coerceAtLeast(MIN_BANDWIDTH_KBPS)
            _estimatedBandwidthKbps.set(bandwidthKbps)
        }
    }

    private var cachedQualityLevel: NetworkQualityLevel? = null
    private var cachedQualityLevelTime = 0L
    private val CACHE_VALIDITY_MS = 500L

    private fun getCachedQualityLevel(): NetworkQualityLevel {
        val now = System.currentTimeMillis()
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
        val level = getCachedQualityLevel()
        val minQuality = 15
        val maxQuality = baseQuality.coerceIn(10, 100)

        val networkFactor = when (level) {
            NetworkQualityLevel.EXCELLENT -> 1.0f
            NetworkQualityLevel.GOOD -> 0.9f
            NetworkQualityLevel.FAIR -> 0.75f
            NetworkQualityLevel.POOR -> 0.55f
            NetworkQualityLevel.CRITICAL -> 0.35f
        }

        val networkQuality = (thermalAdjustedQuality * networkFactor).toInt()
            .coerceIn(minQuality, maxQuality)

        return networkQuality.coerceAtMost(thermalAdjustedQuality)
    }

    fun getAdaptiveFrameInterval(baseIntervalMs: Long, thermalAdjustedIntervalMs: Long): Long {
        val level = getCachedQualityLevel()

        val fpsFactor = when (level) {
            NetworkQualityLevel.EXCELLENT -> 1.0f
            NetworkQualityLevel.GOOD -> 1.0f
            NetworkQualityLevel.FAIR -> 0.75f
            NetworkQualityLevel.POOR -> 0.5f
            NetworkQualityLevel.CRITICAL -> 0.3f
        }

        if (fpsFactor >= 1.0f) return thermalAdjustedIntervalMs

        val baseFps = (1000f / baseIntervalMs)
        val adaptedFps = (baseFps * fpsFactor).coerceAtLeast(MIN_FPS.toFloat())
        val adaptedInterval = (1000f / adaptedFps).toLong()

        return adaptedInterval.coerceAtLeast(thermalAdjustedIntervalMs)
    }

    fun resetStats() {
        clientStats.clear()
        _activeClients.set(0)
        synchronized(this) {
            totalBytesSent = 0L
            lastBandwidthCalcBytes = 0L
        }
        _estimatedBandwidthKbps.set(DEFAULT_BANDWIDTH_KBPS)
    }

    fun getStatsSnapshot(): NetworkStatsSnapshot {
        val clientDetails = clientStats.mapValues { (id, stats) ->
            synchronized(stats) {
                ClientStatsSnapshot(
                    framesSent = stats.framesSent,
                    bytesSent = stats.bytesSent,
                    avgThroughputKbps = if (stats.throughputSamples.isNotEmpty())
                        stats.throughputSamples.average().toInt() else 0,
                    lastFrameSizeBytes = stats.lastFrameSizeBytes,
                    lastSendDurationMs = stats.lastSendDurationMs,
                )
            }
        }
        return NetworkStatsSnapshot(
            activeClients = _activeClients.get(),
            estimatedBandwidthKbps = _estimatedBandwidthKbps.get(),
            totalBytesSent = getTotalBytesSent(),
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
        val throughputSamples: MutableList<Int> = mutableListOf(),
        val frameTimestamps: MutableList<Long> = mutableListOf(),
        var lastFrameTimeMs: Long = 0,
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
        private const val DEFAULT_BANDWIDTH_KBPS = 5000
        private const val MIN_BANDWIDTH_KBPS = 100
        private const val THROUGHPUT_WINDOW = 20
        private const val BANDWIDTH_CALC_INTERVAL_MS = 2000L
        private const val GOOD_BANDWIDTH_THRESHOLD_KBPS = 3000
        private const val FAIR_BANDWIDTH_THRESHOLD_KBPS = 1500
        private const val POOR_BANDWIDTH_THRESHOLD_KBPS = 500
        private const val MIN_FPS = 3
    }
}
