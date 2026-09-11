package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.push.PushSubscriptionStore
import com.raulshma.lenscast.core.push.StoredPushSubscription
import com.raulshma.lenscast.core.push.VapidKeys
import com.raulshma.lenscast.streaming.model.PushSubscriptionDto
import com.raulshma.lenscast.streaming.model.PushSubscriptionsResponseDto
import com.raulshma.lenscast.streaming.model.SuccessResponse
import com.raulshma.lenscast.streaming.model.VapidPublicKeyDto
import com.squareup.moshi.JsonClass

/**
 * The `/api/push/` route family — Web Push subscription management:
 *
 * - `GET  /api/push/vapid-public`  → the VAPID public key (the browser
 *   subscription's `applicationServerKey`, base64url, 65-byte uncompressed
 *   point).
 * - `GET  /api/push/subscriptions` → the stored endpoints, key material
 *   redacted (an endpoint plus keys is a deliver-to address; the list is for
 *   the dashboard's count/readout only).
 * - `POST /api/push/subscriptions` → subscribe; the body is the browser's own
 *   PushSubscription JSON flattened to `endpoint` + `p256dh` + `auth`.
 * - `DELETE /api/push/subscriptions?endpoint=…` → unsubscribe.
 *
 * Auth contract: these routes are session-only by design and deliberately
 * absent from [TokenWritePolicy]'s token allow-list — a subscription is
 * browser-session state (it arrives from the very browser whose service
 * worker must show the notification), not an automation API. The key
 * material is validated at admission ([com.raulshma.lenscast.core.push.PushSubscriptionStore.add]
 * rejects wrong shapes), so the store only ever holds usable receivers.
 */
class PushWebHandler(
    private val subscriptions: PushSubscriptionStore,
    private val vapidKeys: VapidKeys,
    /** The subscription timestamp source. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val subscribeAdapter by lazy { AppJson.moshi.adapter(SubscribeRequest::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(SuccessResponse::class.java) }
    private val publicKeyAdapter by lazy { AppJson.moshi.adapter(VapidPublicKeyDto::class.java) }
    private val listAdapter by lazy { AppJson.moshi.adapter(PushSubscriptionsResponseDto::class.java) }

    /** GET /api/push/vapid-public */
    fun vapidPublicKey(): String = publicKeyAdapter.toJson(
        VapidPublicKeyDto(publicKey = vapidKeys.publicKeyBase64Url()),
    )

    /** GET /api/push/subscriptions — endpoints and timestamps only, keys redacted. */
    fun list(): String = listAdapter.toJson(
        PushSubscriptionsResponseDto(
            subscriptions = subscriptions.all().map { it.redacted() },
            count = subscriptions.count(),
        ),
    )
    /** POST /api/push/subscriptions — the subscribing browser's own key material. */
    fun subscribe(body: String): String {
        val request = runCatching { subscribeAdapter.fromJson(body) }.getOrNull()
            ?: return successAdapter.toJson(SuccessResponse(success = false))
        val added = subscriptions.add(
            endpoint = request.endpoint.orEmpty(),
            p256dh = request.p256dh.orEmpty(),
            auth = request.auth.orEmpty(),
            labels = request.labels.orEmpty(),
            createdAtMs = nowMs(),
        )
        return successAdapter.toJson(SuccessResponse(success = added))
    }

    /** DELETE /api/push/subscriptions?endpoint=… */
    fun unsubscribe(endpoint: String?): String {
        val removed = endpoint?.let { subscriptions.remove(it) } == true
        return successAdapter.toJson(SuccessResponse(success = removed))
    }

    private fun StoredPushSubscription.redacted() = PushSubscriptionDto(
        endpoint = endpoint,
        createdAtMs = createdAtMs,
    )

    @JsonClass(generateAdapter = true)
    data class SubscribeRequest(
        val endpoint: String? = null,
        val p256dh: String? = null,
        val auth: String? = null,
        val labels: List<String>? = null,
    )
}
