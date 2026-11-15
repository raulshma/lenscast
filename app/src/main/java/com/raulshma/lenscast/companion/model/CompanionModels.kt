package com.raulshma.lenscast.companion.model

import com.squareup.moshi.JsonClass

/**
 * The auth mode a paired device was configured with, learned at pairing time
 * from the login probe against the remote's /api/auth routes. Rides the
 * roster JSON so later sessions know which credential header to build
 * ([StoredCredentials] carries the matching secret — token for [TOKEN],
 * username/password for [BASIC]).
 */
enum class CompanionAuthKind { TOKEN, BASIC }

/**
 * One device's secrets, as the pairing flow captured them. These are the
 * only credentials the companion mode ever keeps: the object is persisted
 * ONLY through [com.raulshma.lenscast.companion.roster.CompanionCredentialStore]
 * (AES-GCM at rest, wave 1) and never serialized into the roster JSON —
 * the roster's [CompanionDevice] records which *kind* of auth a device
 * speaks, never the secret itself. Exactly one of [token] (for
 * [CompanionAuthKind.TOKEN]) or [username]/[password] (for
 * [CompanionAuthKind.BASIC]) is populated.
 */
@JsonClass(generateAdapter = true)
data class StoredCredentials(
    val kind: CompanionAuthKind,
    val username: String? = null,
    val password: String? = null,
    val token: String? = null,
)

/**
 * One paired LensCast device in the companion roster — the addressable
 * identity the client stack dials, NOT a secret holder: credentials never
 * ride in this object (they live in the encrypted credential store).
 *
 * Pairing payload: the camera app's Connect sheet QR encodes
 * `https://host:port#fp=<sha256>` — the [host]/[port] come from the URL,
 * [useTls] from the scheme, and [tlsFingerprint] from the `#fp=` fragment
 * (the SHA-256 digest of the device's self-signed cert, used for pinning
 * since the TLS identity is self-issued). Manual or mDNS pairing without a
 * QR leaves the fingerprint null; the first HTTPS connect then goes through
 * a TOFU confirm (trust-on-first-use — the user approves the shown digest)
 * and fills it. [auth] and [role] come from the pairing-time login probe:
 * [role] is `"admin"` | `"viewer"` as the remote answered, and gates which
 * controls the UI offers (viewer devices get the read-only surfaces).
 */
@JsonClass(generateAdapter = true)
data class CompanionDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val useTls: Boolean = false,
    val tlsFingerprint: String? = null,
    val auth: CompanionAuthKind,
    val role: String? = null,
    val addedAtMs: Long,
    val lastSeenMs: Long? = null,
)

/**
 * One LensCast instance seen on the LAN by mDNS (`LensCast._http._tcp`):
 * a discovery *candidate* the pairing flow shows before anything is trusted
 * or persisted. Plain data — never written to the roster; pairing promotes
 * a [DiscoveredService] into a [CompanionDevice] after the probe/TOFU step.
 * [txt] carries the mDNS TXT record (the device name and role hints the
 * discovery client filters on).
 */
data class DiscoveredService(
    val name: String,
    val host: String,
    val port: Int,
    val txt: Map<String, String> = emptyMap(),
)
