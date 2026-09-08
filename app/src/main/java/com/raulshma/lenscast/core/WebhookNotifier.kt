package com.raulshma.lenscast.core

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fire-and-forget webhook notifier for detection events: a JSON POST to a
 * user-configured URL (ntfy, Home Assistant, Telegram gateway, anything that
 * accepts JSON). One daemon worker serializes dispatches; failures log and
 * drop — an unreachable webhook must never block or crash the detection path.
 */
class WebhookNotifier(
    private val configProvider: () -> Pair<Boolean, String>,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WebhookNotifier").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    data class EventPayload(
        val type: String,
        val rmsOrDelta: Double,
        val batteryPercent: Int? = null,
    )

    fun notifyEvent(event: EventPayload) {
        val (enabled, url) = configProvider()
        if (!enabled) return
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return
        if (!inFlight.compareAndSet(false, true)) return
        executor.execute {
            try {
                post(trimmed, buildBody(event))
            } catch (e: Exception) {
                Log.w(TAG, "Webhook dispatch to $trimmed failed: ${e.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    private val bodyAdapter by lazy { AppJson.moshi.adapter(WireBody::class.java) }

    private fun buildBody(event: EventPayload): ByteArray {
        // Serialized through the one Moshi instance: locale-independent
        // number formatting and real string escaping — hand-concatenation
        // emitted invalid JSON under comma-decimal locales.
        return bodyAdapter.toJson(
            WireBody(
                type = event.type,
                value = event.rmsOrDelta,
                timestampMs = clockMs(),
                source = SOURCE,
                batteryPercent = event.batteryPercent,
            ),
        ).toByteArray(Charsets.UTF_8)
    }

    /** The wire shape: `value`/`timestampMs`/`source` naming over [EventPayload]'s detector-facing fields. */
    private data class WireBody(
        val type: String,
        val value: Double,
        val timestampMs: Long,
        val source: String,
        val batteryPercent: Int?,
    )

    private fun post(url: String, body: ByteArray) {
        val connection = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = StreamDefaults.WEBHOOK_TIMEOUT_MS
            readTimeout = StreamDefaults.WEBHOOK_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Title", "LensCast detection event")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Webhook POST answered HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "WebhookNotifier"
        private const val SOURCE = "lenscast"
    }
}
