package com.raulshma.lenscast.core.push

import android.content.Context
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import com.squareup.moshi.JsonClass
import java.io.File

/**
 * One persisted browser push subscription: the push service endpoint URL and
 * the client key material the RFC 8291 encryption needs. `p256dh`/`auth` are
 * base64url (the browser's own wire encoding, forwarded verbatim);
 * `createdAtMs` drives nothing today but keeps the store self-describing for
 * debugging; `labels` is optional free-form browser labeling, present for
 * forward compatibility.
 *
 * These are device-local secrets — an endpoint plus key material lets the
 * holder deliver notifications to that browser — so the store file is
 * excluded from cloud backup / device transfer in `data_extraction_rules.xml`
 * and `backup_rules.xml`, the same treatment as the auth session tokens.
 */
@JsonClass(generateAdapter = true)
data class StoredPushSubscription(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val createdAtMs: Long = 0,
    val labels: List<String> = emptyList(),
)

/**
 * File-backed store of browser push subscriptions, the [com.raulshma.lenscast.streaming.AuthSessionStore]
 * shape: `push_subscriptions.json` in app-private filesDir, written atomically
 * (tmp file + rename), decoded through the one App Json Moshi instance, a
 * corrupt or absent file starting clean. Dedupe is by endpoint — a browser
 * re-subscribing (same endpoint, rotated keys) updates in place rather than
 * multiplying rows; every mutation is serialized through one monitor because
 * the atomic write uses a fixed `.tmp` name.
 */
class PushSubscriptionStore {

    private val file: File

    private val listAdapter by lazy {
        com.squareup.moshi.Types.newParameterizedType(List::class.java, StoredPushSubscription::class.java)
            .let { AppJson.moshi.adapter<List<StoredPushSubscription>>(it) }
    }

    @Volatile
    private var cache: List<StoredPushSubscription>? = null

    /** The production home: `push_subscriptions.json` in app-private filesDir. */
    constructor(context: Context) : this(File(context.filesDir, "push_subscriptions.json"))

    /** The file seam the JVM tests inject (the AuditLog pattern). */
    constructor(file: File) {
        this.file = file
    }

    /** The current subscriptions, endpoint-deduped, oldest first. */
    @Synchronized
    fun all(): List<StoredPushSubscription> =
        cache ?: file.readJsonOrDefault(listAdapter, emptyList(), warn = "Failed to read push subscriptions; starting clean")
            .filter { it.endpoint.isNotBlank() }
            .distinctBy { it.endpoint }
            .also { cache = it }

    /**
     * Adds (or endpoint-replaces) a subscription after validating its key
     * shapes — the receiver's public point and auth secret must be exactly
     * what RFC 8291 encryption consumes, and a bad key would only be
     * discovered at the first dispatch. True when the store changed.
     */
    @Synchronized
    fun add(endpoint: String, p256dh: String, auth: String, labels: List<String>, createdAtMs: Long): Boolean {
        val trimmed = endpoint.trim()
        if (!PushSubscriptionPolicy.isSubscribable(trimmed, p256dh, auth)) return false
        val entry = StoredPushSubscription(
            endpoint = trimmed,
            p256dh = p256dh.trim(),
            auth = auth.trim(),
            createdAtMs = createdAtMs,
            labels = labels,
        )
        val next = all().filterNot { it.endpoint == entry.endpoint } + entry
        return persist(next)
    }

    /** Removes one endpoint; true when it was present. */
    @Synchronized
    fun remove(endpoint: String): Boolean {
        val trimmed = endpoint.trim()
        val next = all().filterNot { it.endpoint == trimmed }
        if (next.size == all().size) return false
        return persist(next)
    }

    /** Count of subscriptions — the handler's list response total. */
    fun count(): Int = all().size

    private fun persist(subscriptions: List<StoredPushSubscription>): Boolean {
        cache = subscriptions
        // Losing the persisted copy only means browsers re-subscribe.
        return file.writeAtomicallyOrWarn(
            listAdapter.toJson(subscriptions),
            warn = "Failed to persist push subscriptions",
        )
    }
}

/**
 * The subscription admission verdicts, pure and JVM-tested: an endpoint is an
 * https URL (push services are always TLS), the p256dh field must decode to
 * the 65-byte uncompressed P-256 point, and auth must decode to the 16-byte
 * secret RFC 8291's HKDF consumes.
 */
object PushSubscriptionPolicy {

    fun isSubscribable(endpoint: String, p256dh: String, auth: String): Boolean {
        val trimmed = endpoint.trim()
        if (!trimmed.startsWith("https://")) return false
        return PushEncryption.receiverKeyOrNull(p256dh, auth) != null
    }
}
