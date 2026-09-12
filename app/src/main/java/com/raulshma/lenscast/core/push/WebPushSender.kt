package com.raulshma.lenscast.core.push

import android.util.Log
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.DetectionAlert
import com.raulshma.lenscast.core.EventKind
import com.squareup.moshi.JsonClass
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executors
import java.util.Locale

/**
 * The Web Push half of the detection-event dispatch, beside
 * [com.raulshma.lenscast.core.WebhookNotifier] and the MQTT publisher: every
 * subscribed browser's push service gets one RFC 8291-encrypted JSON event
 * payload, authenticated per RFC 8292 (VAPID). The browser's service worker
 * (the dashboard's `/push-sw.js`) receives it and shows the notification even
 * with the tab closed — the phone is the publisher; the push service
 * (FCM/Mozilla autopush) is free browser infrastructure and no account.
 *
 * The webhook's contracts carry over: one daemon worker serializes dispatches
 * (never blocking the detection path), the go/no-go verdict ([willDispatch],
 * claimed by the caller into the event log) is decided at dispatch time, and
 * every failure is logged, never thrown — fail-open like every other sink. A
 * 404/410 answer means the subscription expired or the browser unsubscribed:
 * the endpoint is pruned from the store so later events stop paying for it.
 * A fresh ephemeral ECDH pair is generated per dispatch (the VAPID identity
 * key never encrypts), and a skipped or failed browser costs its own log line
 * and nothing else.
 */
class WebPushSender(
    private val configProvider: () -> Config,
    private val store: PushSubscriptionStore,
    private val vapidKeys: VapidKeys,
    private val transport: Transport = HttpsTransport(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** The live settings snapshot read per dispatch, like the webhook's config pair. */
    data class Config(val enabled: Boolean, val subject: String)

    /**
     * The one POST per subscription. Returns the status code, or -1 on any IO
     * failure; implementors must not throw. HTTP-level only — this seam is
     * what the JVM tests fake.
     */
    fun interface Transport {
        fun post(endpoint: String, headers: Map<String, String>, body: ByteArray): Int
    }

    /**
     * The default transport: HttpsURLConnection with tight timeouts, the
     * webhook notifier's failure cost (one log line), and no retry ladder —
     * push services are reliable, the next event retries for free.
     */
    class HttpsTransport(private val timeoutMs: Int = TIMEOUT_MS) : Transport {
        override fun post(endpoint: String, headers: Map<String, String>, body: ByteArray): Int = try {
            val connection = (java.net.URI(endpoint).toURL().openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                connection.outputStream.use { it.write(body) }
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Push POST to $endpoint failed: ${e.message}")
            NO_RESPONSE
        }

        companion object {
            private const val TIMEOUT_MS = 10_000
        }
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WebPushSender").apply { isDaemon = true }
    }

    /**
     * Queue a fan-out to every stored subscription. True when at least one
     * dispatch went out — the caller claims "push" in the event log at
     * dispatch time (the webhook's exact contract); false when disabled or
     * nothing is subscribed. [deepLink] is the dashboard deep link the
     * notification's click opens (the payload's `url` field; the service
     * worker reads it defensively, so null keeps the worker's own fallback).
     */
    fun notifyEvent(
        alert: DetectionAlert,
        eventId: String?,
        clipAvailable: Boolean,
        deepLink: String? = null,
    ): Boolean {
        val config = configProvider()
        if (!willDispatch(config, store.count())) return false
        val payload = buildPayload(alert, eventId, clipAvailable, deepLink)
        executor.execute { dispatchAll(payload, config) }
        return true
    }

    private fun dispatchAll(payload: PushEventWire, config: Config) {
        for (subscription in store.all()) {
            dispatchOne(subscription, payload, config)
        }
    }

    private fun dispatchOne(subscription: StoredPushSubscription, payload: PushEventWire, config: Config) {
        try {
            val receiver = PushEncryption.receiverKeyOrNull(subscription.p256dh, subscription.auth)
                ?: run {
                    Log.w(TAG, "Dropping subscription with malformed key material: ${subscription.endpoint}")
                    store.remove(subscription.endpoint)
                    return
                }
            val body = PushEncryption.encrypt(
                payload = payloadAdapter.toJson(payload).toByteArray(Charsets.UTF_8),
                receiver = receiver,
                sender = ephemeralKeyPair(),
            )
            val endpointOrigin = PushEncryption.audienceOf(subscription.endpoint)
            val headers = mapOf(
                "Content-Encoding" to PushEncryption.CONTENT_ENCODING,
                "TTL" to TTL_SECONDS.toString(),
                "Urgency" to urgencyFor(payload.type),
                "Authorization" to PushEncryption.vapidAuthorization(
                    endpoint = subscription.endpoint,
                    keyPair = vapidKeys.keyPair(),
                    subject = config.subject,
                    nowMs = nowMs(),
                ),
            )
            when (val code = transport.post(subscription.endpoint, headers, body)) {
                // 404/410: the subscription is gone (browser unsubscribe or
                // push-service expiry) — prune so later events skip it.
                GONE, NOT_FOUND -> {
                    Log.d(TAG, "Push endpoint gone ($code); pruning: $endpointOrigin")
                    store.remove(subscription.endpoint)
                }
                in 200..299 -> Unit
                else -> Log.w(TAG, "Push POST answered HTTP $code from $endpointOrigin")
            }
        } catch (e: Exception) {
            // Fail-open like every sink: one dead browser must not cost the
            // others, and no failure may reach the executor as a crash.
            Log.w(TAG, "Push dispatch failed: ${e.message}")
        }
    }

    /** A fresh ECDH P-256 pair per encrypted message (RFC 8291's sender role). */
    private fun ephemeralKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return generator.generateKeyPair()
    }

    companion object {
        private const val TAG = "WebPushSender"
        private const val GONE = 410
        private const val NOT_FOUND = 404
        private const val NO_RESPONSE = -1
        private const val TTL_SECONDS = 3600

        private val payloadAdapter by lazy { AppJson.moshi.adapter(PushEventWire::class.java) }

        /**
         * The would-this-actually-dispatch decision behind [notifyEvent]: the
         * master toggle on and at least one subscription stored. Kept pure and
         * tested so the event log never claims an action the sender no-ops.
         */
        fun willDispatch(config: Config?, subscriptionCount: Int): Boolean =
            config != null && config.enabled && subscriptionCount > 0

        /**
         * The wire payload the service worker renders: a notification title,
         * a one-line body summary, a replace-per-type tag, the event id (null
         * for the synthetic test alert), whether the event dispatched a
         * recording (the clip lands in the gallery moments later), and the
         * dashboard deep link the notification's click opens ([deepLink]).
         */
        fun buildPayload(
            alert: DetectionAlert,
            eventId: String?,
            clipAvailable: Boolean,
            deepLink: String? = null,
        ): PushEventWire {
            // The tag is the notification-replace key — stable lowercase wire
            // casing; the title/body capitalize for display only.
            val kindName = alert.kind.name.lowercase(Locale.US)
            val kindLabel = kindName.replaceFirstChar { it.titlecase(Locale.US) }
            val bodyParts = buildList {
                if (alert.zones.isNotEmpty()) add(alert.zones.joinToString(", "))
                if (alert.labels.isNotEmpty()) add(alert.labels.joinToString(", "))
                alert.batteryPercent?.let { add("battery $it%") }
            }
            return PushEventWire(
                title = "LensCast $kindLabel alert",
                body = bodyParts.joinToString(" · ").ifEmpty { "$kindLabel event detected" },
                tag = "lenscast-$kindName",
                eventId = eventId,
                type = alert.kind.wireName,
                timestampMs = alert.timestampMs,
                clipAvailable = clipAvailable,
                url = deepLink,
            )
        }

        /**
         * The RFC 8030 Urgency header: tamper (a camera losing power) and
         * motion page immediately; sound and the synthetic test ride the
         * normal tier. Pure and tested — the header is part of the wire
         * contract the tests pin.
         */
        fun urgencyFor(type: String): String = when (type) {
            EventKind.MOTION.wireName, EventKind.TAMPER.wireName -> "high"
            else -> "normal"
        }
    }
}

/** The event JSON the browser's service worker receives (phone encrypts, browser decrypts). */
@JsonClass(generateAdapter = true)
data class PushEventWire(
    val title: String,
    val body: String,
    /** Notifications with the same tag replace each other instead of stacking. */
    val tag: String,
    /** The detection event's UUID; null for the synthetic test alert. */
    val eventId: String?,
    /** The event wire name (`motion`/`sound`/`tamper`/`test`). */
    val type: String,
    val timestampMs: Long,
    /** True when the event dispatched a bounded recording (a clip follows). */
    val clipAvailable: Boolean,
    /**
     * The dashboard deep link the notification click opens (`#/events` at
     * dispatch — the clip id only exists once the recording finalizes); the
     * worker falls back to its own derivation when absent.
     */
    val url: String? = null,
)
