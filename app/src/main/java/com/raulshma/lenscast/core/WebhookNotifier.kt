package com.raulshma.lenscast.core

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fire-and-forget webhook notifier for detection events: a JSON POST to a
 * user-configured URL (ntfy, Home Assistant, Telegram gateway, anything that
 * accepts JSON). One daemon worker serializes dispatches; each dispatch gets
 * the [WebhookRetryPolicy] ladder (three attempts, linear backoff) while the
 * single-flight flag coalesces bursts into one live dispatch — an unreachable
 * webhook must never block or crash the detection path. Custom headers and
 * the event snapshot ride the same POST; both are supplied per event by the
 * caller (the detection coordinator reads them live from the settings store).
 * The newest event that arrives mid-dispatch (retry windows included) wins
 * the one-slot queue and follows the live dispatch, so a burst costs the
 * intermediate detections but never the latest one.
 */
class WebhookNotifier(
    private val configProvider: () -> Pair<Boolean, String>,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WebhookNotifier").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)
    private val pendingLock = Any()

    @Volatile
    private var pending: PendingDispatch? = null

    /** A body built (and timestamped) at notify time, waiting for the worker. */
    private class PendingDispatch(
        val url: String,
        val body: ByteArray,
        val headers: Map<String, String>,
        val queuedAtMs: Long,
    )

    data class EventPayload(
        val type: String,
        val rmsOrDelta: Double,
        val batteryPercent: Int? = null,
        val snapshotJpegBase64: String? = null,
    )

    /**
     * Queue the event for dispatch. True when a dispatch actually went out —
     * or waits in the single slot behind a live one — giving the caller (the
     * detection coordinator) its verdict for claiming "webhook" in the event
     * log at dispatch time, not minutes earlier while a snapshot encodes.
     * False when the notifier no-ops: disabled, or a non-HTTP URL.
     */
    fun notifyEvent(event: EventPayload, headers: Map<String, String> = emptyMap()): Boolean {
        val (enabled, url) = configProvider()
        if (!willDispatch(enabled, url)) return false
        dispatch(PendingDispatch(url.trim(), buildBody(event), headers, clockMs()))
        return true
    }

    private fun dispatch(queued: PendingDispatch) {
        if (!inFlight.compareAndSet(false, true)) {
            stash(queued)
            return
        }
        executor.execute {
            try {
                var attempts = 0
                while (true) {
                    attempts++
                    val code = try {
                        post(queued.url, queued.body, queued.headers)
                    } catch (e: Exception) {
                        Log.w(TAG, "Webhook dispatch to ${queued.url} failed: ${e.message}")
                        -1
                    }
                    if (WebhookRetryPolicy.isSuccessful(code)) break
                    if (!WebhookRetryPolicy.shouldRetry(attempts)) {
                        Log.w(TAG, "Webhook dispatch to ${queued.url} dropped after $attempts attempts")
                        break
                    }
                    Thread.sleep(WebhookRetryPolicy.retryDelayMs(attempts))
                }
            } finally {
                inFlight.set(false)
            }
            // Taken after the flag drops: a concurrent notifyEvent that wins
            // the CAS simply re-queues this behind its own dispatch.
            takePending()?.let { dispatch(it) }
        }
    }

    /** The one-slot queue, newest-first: a late-arriving older event never evicts the latest one. */
    private fun stash(queued: PendingDispatch) {
        synchronized(pendingLock) {
            val current = pending
            if (current == null || queued.queuedAtMs >= current.queuedAtMs) {
                pending = queued
            }
        }
    }

    private fun takePending(): PendingDispatch? = synchronized(pendingLock) {
        val next = pending
        pending = null
        next
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
                snapshotJpeg = event.snapshotJpegBase64,
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
        val snapshotJpeg: String?,
    )

    private fun post(url: String, body: ByteArray, headers: Map<String, String>): Int {
        val connection = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = StreamDefaults.WEBHOOK_TIMEOUT_MS
            readTimeout = StreamDefaults.WEBHOOK_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Title", "LensCast detection event")
            // Custom headers land last, so a user-supplied value can override
            // the built-ins (e.g. an auth header is expected; a custom Title
            // is allowed).
            headers.forEach { (name, value) ->
                if (name.isNotBlank()) setRequestProperty(name, value)
            }
            setFixedLengthStreamingMode(body.size)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (!WebhookRetryPolicy.isSuccessful(code)) {
                Log.w(TAG, "Webhook POST answered HTTP $code")
            }
            return code
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "WebhookNotifier"
        private const val SOURCE = "lenscast"

        private val headersAdapter by lazy {
            AppJson.moshi.adapter<Map<String, String>>(
                com.squareup.moshi.Types.newParameterizedType(
                    Map::class.java,
                    String::class.java,
                    String::class.java,
                ),
            )
        }

        /**
         * The would-this-actually-POST decision behind [notifyEvent] (kept
         * separate and tested): the event log records "webhook" under
         * dispatched actions only when a dispatch would really go out, so the
         * feed must not claim an action the notifier silently no-ops. The
         * claim records the attempt: a dispatch that exhausts the retry
         * ladder still counts — the device log carries the failure, while
         * the feed's `webhook` badge means a POST went out for the event.
         */
        fun willDispatch(enabled: Boolean, url: String?): Boolean {
            if (!enabled) return false
            val trimmed = url?.trim().orEmpty()
            return trimmed.startsWith("http://") || trimmed.startsWith("https://")
        }

        /**
         * The persisted headers setting is a JSON `{"Name": "value"}` map.
         * Decode is total: null/blank decodes to no headers, any malformed
         * payload degrades to no headers, and blank names are dropped — a bad
         * setting must never break the dispatch.
         */
        fun parseHeaders(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                headersAdapter.fromJson(json)
                    ?.filterKeys { it.isNotBlank() }
                    ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
