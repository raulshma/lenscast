package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.core.StreamAuthCrypto
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns RTSP request authorization: the Digest/Basic decision ladder, the
 * Digest nonce store (SecureRandom minting, TTL eviction, size cap, nc
 * monotonicity), and the 401 challenge header. Android-free — java.util.Base64
 * replaces android.util.Base64 with byte-identical encodings — so the
 * security behavior is JVM-tested.
 *
 * The auth spec arrives as a provider: [RtspServer] reads its config
 * dynamically per request (auth changes land via restart), while the nonce
 * store survives stop/start on the same server instance.
 */
class RtspSessionAuthorizer(
    private val specProvider: () -> RtspAuthSpec?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val secureRandom = SecureRandom()
    private val digestNonces = ConcurrentHashMap<String, DigestNonceState>()

    private data class DigestNonceState(
        val expiresAtMs: Long,
        val ncTrack: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
    )

    /** Whether [method] must be authorized at all: auth on, and OPTIONS is always free. */
    fun requiresAuthentication(method: String): Boolean {
        if (specProvider() == null) return false
        return method != "OPTIONS"
    }

    /**
     * The WWW-Authenticate value for a 401: Digest when a Digest HA1 is
     * configured, Basic otherwise.
     */
    fun challengeHeader(): String {
        val digestChallenge = if (specProvider()?.digestHa1?.isNotBlank() == true) {
            val nonce = createDigestNonce()
            "Digest realm=\"$AUTH_REALM\", nonce=\"$nonce\", opaque=\"$AUTH_OPAQUE\", algorithm=MD5, qop=\"auth\""
        } else null

        return digestChallenge ?: "Basic realm=\"$AUTH_REALM\""
    }

    /**
     * Authorizes one request against the current auth spec. [authHeaderValue]
     * is the raw Authorization header; null or an unrecognized scheme rejects.
     * Digest takes priority over Basic.
     */
    fun authorize(method: String, uri: String, authHeaderValue: String?): Boolean {
        val auth = specProvider() ?: return true
        val authHeader = authHeaderValue ?: return false

        if (authHeader.startsWith("Digest ", ignoreCase = true)) {
            if (auth.digestHa1.isBlank()) return false
            return isAuthorizedDigest(authHeader, auth.username, auth.digestHa1, method, uri)
        }
        if (!authHeader.startsWith("Basic ", ignoreCase = true)) return false

        val payload = authHeader.substringAfter(' ', "").trim()
        if (payload.isEmpty()) return false

        val decoded = runCatching {
            // Strict RFC 4648 decode — android.util.Base64.DEFAULT (the pre-port
        // decoder) also rejected non-alphabet characters instead of skipping them.
        val bytes = Base64.getDecoder().decode(payload)
            String(bytes, Charsets.UTF_8)
        }.getOrNull() ?: return false

        val separator = decoded.indexOf(':')
        if (separator <= 0) return false

        val providedUser = decoded.substring(0, separator)
        val providedPassword = decoded.substring(separator + 1)

        if (!StreamAuthCrypto.constantTimeEquals(providedUser, auth.username)) return false
        return StreamAuthCrypto.verifyPassword(providedPassword, auth.passwordHash)
    }

    private fun isAuthorizedDigest(
        authHeader: String,
        expectedUser: String,
        digestHa1: String,
        method: String,
        requestUri: String,
    ): Boolean {
        val params = parseDigestParams(authHeader.substringAfter(' ', ""))

        val username = params["username"] ?: return false
        val realm = params["realm"] ?: return false
        val nonce = params["nonce"] ?: return false
        val uri = params["uri"] ?: return false
        val response = params["response"]?.lowercase(Locale.US) ?: return false
        val qop = params["qop"]?.lowercase(Locale.US)
        val nc = params["nc"] ?: ""
        val cnonce = params["cnonce"] ?: ""
        val opaque = params["opaque"] ?: ""

        if (!StreamAuthCrypto.constantTimeEquals(username, expectedUser)) return false
        if (!StreamAuthCrypto.constantTimeEquals(realm, AUTH_REALM)) return false
        if (!StreamAuthCrypto.constantTimeEquals(opaque, AUTH_OPAQUE)) return false
        if (!isDigestUriMatch(uri, requestUri)) return false
        if (!validateDigestNonce(nonce, username, cnonce, nc, qop)) return false

        val ha2 = StreamAuthCrypto.md5Hex("$method:$uri")
        val expectedResponse = if (!qop.isNullOrBlank()) {
            if (cnonce.isBlank() || nc.isBlank()) return false
            StreamAuthCrypto.md5Hex("$digestHa1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            StreamAuthCrypto.md5Hex("$digestHa1:$nonce:$ha2")
        }

        return StreamAuthCrypto.constantTimeEquals(response, expectedResponse)
    }

    /** Parses comma-separated Digest parameters; quoted values may contain escaped quotes. */
    internal fun parseDigestParams(value: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < value.length) {
            while (i < value.length && (value[i] == ' ' || value[i] == ',')) i++
            if (i >= value.length) break

            val keyStart = i
            while (i < value.length && value[i] != '=') i++
            if (i >= value.length) break
            val key = value.substring(keyStart, i).trim().lowercase(Locale.US)
            i++

            val parsedValue = if (i < value.length && value[i] == '"') {
                i++
                val sb = StringBuilder()
                var escaped = false
                while (i < value.length) {
                    val ch = value[i]
                    if (escaped) {
                        sb.append(ch)
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        i++
                        break
                    } else {
                        sb.append(ch)
                    }
                    i++
                }
                sb.toString()
            } else {
                val start = i
                while (i < value.length && value[i] != ',') i++
                value.substring(start, i).trim()
            }

            result[key] = parsedValue
            while (i < value.length && value[i] != ',') i++
            if (i < value.length && value[i] == ',') i++
        }
        return result
    }

    private fun validateDigestNonce(
        nonce: String,
        username: String,
        cnonce: String,
        ncHex: String,
        qop: String?,
    ): Boolean {
        cleanExpiredDigestNonces()
        val state = digestNonces[nonce] ?: return false
        if (clock() > state.expiresAtMs) {
            digestNonces.remove(nonce)
            return false
        }

        if (qop.isNullOrBlank()) return true
        val nc = ncHex.toLongOrNull(16) ?: return false
        if (cnonce.isBlank()) return false
        val key = "$username|$cnonce"
        val previous = state.ncTrack[key] ?: 0L
        if (nc <= previous) return false
        state.ncTrack[key] = nc
        return true
    }

    /** The Digest uri parameter must normalize to the same path as the request URI. */
    private fun isDigestUriMatch(digestUri: String, requestUri: String): Boolean {
        val digestPath = RtspUriPolicy.normalizedPath(digestUri)
        val requestPath = RtspUriPolicy.normalizedPath(requestUri)
        return StreamAuthCrypto.constantTimeEquals(digestPath, requestPath)
    }

    private fun createDigestNonce(): String {
        cleanExpiredDigestNonces()
        val bytes = ByteArray(DIGEST_NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        val nonce = Base64.getUrlEncoder().encodeToString(bytes)
        digestNonces[nonce] = DigestNonceState(clock() + DIGEST_NONCE_TTL_MS)
        return nonce
    }

    private fun cleanExpiredDigestNonces() {
        val now = clock()
        digestNonces.entries.removeIf { now > it.value.expiresAtMs }
        if (digestNonces.size > MAX_DIGEST_NONCES) {
            val overflow = digestNonces.size - MAX_DIGEST_NONCES
            val keysToRemove = digestNonces.entries
                .sortedBy { it.value.expiresAtMs }
                .take(overflow)
                .map { it.key }
            keysToRemove.forEach { digestNonces.remove(it) }
        }
    }

    private companion object {
        val AUTH_REALM = StreamAuthCrypto.RTSP_DIGEST_REALM
        const val AUTH_OPAQUE = "lenscast-rtsp"
        const val DIGEST_NONCE_BYTES = 16
        const val DIGEST_NONCE_TTL_MS = 5 * 60 * 1000L
        const val MAX_DIGEST_NONCES = 512
    }
}
