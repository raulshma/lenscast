package com.raulshma.lenscast.streaming.rtsp

import com.raulshma.lenscast.core.Base64Codec
import com.raulshma.lenscast.core.StreamAuthCrypto
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns RTSP request authorization: the Digest/Basic decision ladder, the
 * Digest nonce store (SecureRandom minting, TTL eviction, size cap, nc
 * monotonicity), and the 401 challenge header. Android-free — the pure
 * Base64Codec is byte- and strictness-identical to java.util.Base64 (an
 * API 26+ class, and one whose android.util counterpart was lenient before
 * that) — so the security behavior is JVM-tested.
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
    private val mintSeq = AtomicLong()

    private data class DigestNonceState(
        val expiresAtMs: Long,
        val mintSeq: Long,
        val ncTrack: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
    )

    /** Whether [method] must be authorized at all: auth on, and OPTIONS is always free. */
    fun requiresAuthentication(method: String): Boolean {
        if (specProvider() == null) return false
        return method != "OPTIONS"
    }

    /**
     * The auth verdict. [Unauthorized.stale] is true only when a correct
     * Digest response used a nonce this server minted but has since expired —
     * the standard signal telling the client to retry with the fresh nonce
     * instead of re-prompting the user.
     */
    sealed interface AuthVerdict {
        data object Allowed : AuthVerdict
        data class Unauthorized(val stale: Boolean = false) : AuthVerdict
    }

    /**
     * The WWW-Authenticate value for a 401. Digest is advertised when a
     * Digest HA1 is configured and the Basic compatibility mode is off;
     * otherwise Basic. Basic verification stays available either way, so a
     * client that chooses Basic is never rejected just because Digest was
     * advertised.
     */
    fun challengeHeader(stale: Boolean = false): String {
        val spec = specProvider()
        val digestChallenge = if (spec?.digestHa1?.isNotBlank() == true && !spec.basicAuthCompat) {
            val nonce = createDigestNonce()
            val staleParam = if (stale) ", stale=TRUE" else ""
            "Digest realm=\"$AUTH_REALM\", nonce=\"$nonce\", opaque=\"$AUTH_OPAQUE\", " +
                "algorithm=MD5, qop=\"auth\"$staleParam"
        } else null

        return digestChallenge ?: "Basic realm=\"$AUTH_REALM\""
    }

    /**
     * Authorizes one request against the current auth spec. [authHeaderValue]
     * is the raw Authorization header; null or an unrecognized scheme rejects.
     * Digest takes priority over Basic.
     */
    fun authorize(method: String, uri: String, authHeaderValue: String?): Boolean =
        authorizeVerdict(method, uri, authHeaderValue) == AuthVerdict.Allowed

    /** The full verdict, including the stale-nonce signal the transport uses. */
    fun authorizeVerdict(method: String, uri: String, authHeaderValue: String?): AuthVerdict {
        val auth = specProvider() ?: return AuthVerdict.Allowed
        val authHeader = authHeaderValue ?: return AuthVerdict.Unauthorized()

        if (authHeader.startsWith("Digest ", ignoreCase = true)) {
            if (auth.digestHa1.isBlank()) return AuthVerdict.Unauthorized()
            return isAuthorizedDigest(authHeader, auth.username, auth.digestHa1, method, uri)
        }
        if (!authHeader.startsWith("Basic ", ignoreCase = true)) return AuthVerdict.Unauthorized()

        val payload = authHeader.substringAfter(' ', "").trim()
        if (payload.isEmpty()) return AuthVerdict.Unauthorized()

        // Strict RFC 4648 decode — Base64Codec rejects non-alphabet characters
        // and malformed padding instead of skipping them, so a tampered or
        // truncated header can never decode into credentials.
        val decodedBytes = Base64Codec.decodeOrNull(payload) ?: return AuthVerdict.Unauthorized()
        val decoded = String(decodedBytes, Charsets.UTF_8)

        val separator = decoded.indexOf(':')
        if (separator <= 0) return AuthVerdict.Unauthorized()

        val providedUser = decoded.substring(0, separator)
        val providedPassword = decoded.substring(separator + 1)

        if (!StreamAuthCrypto.constantTimeEquals(providedUser, auth.username)) {
            return AuthVerdict.Unauthorized()
        }
        return if (StreamAuthCrypto.verifyPassword(providedPassword, auth.passwordHash)) {
            AuthVerdict.Allowed
        } else {
            AuthVerdict.Unauthorized()
        }
    }

    private fun isAuthorizedDigest(
        authHeader: String,
        expectedUser: String,
        digestHa1: String,
        method: String,
        requestUri: String,
    ): AuthVerdict {
        val params = parseDigestParams(authHeader.substringAfter(' ', ""))

        val username = params["username"] ?: return AuthVerdict.Unauthorized()
        val realm = params["realm"] ?: return AuthVerdict.Unauthorized()
        val nonce = params["nonce"] ?: return AuthVerdict.Unauthorized()
        val uri = params["uri"] ?: return AuthVerdict.Unauthorized()
        val response = params["response"]?.lowercase(Locale.US) ?: return AuthVerdict.Unauthorized()
        val qop = params["qop"]?.lowercase(Locale.US)
        val nc = params["nc"] ?: ""
        val cnonce = params["cnonce"] ?: ""
        val opaque = params["opaque"] ?: ""

        if (!StreamAuthCrypto.constantTimeEquals(username, expectedUser)) return AuthVerdict.Unauthorized()
        if (!StreamAuthCrypto.constantTimeEquals(realm, AUTH_REALM)) return AuthVerdict.Unauthorized()
        // RFC 2617 says the client should echo opaque, but several NVR/client
        // stacks omit it. A present-but-wrong value is still rejected.
        if (opaque.isNotEmpty() && !StreamAuthCrypto.constantTimeEquals(opaque, AUTH_OPAQUE)) {
            return AuthVerdict.Unauthorized()
        }
        if (!isDigestUriMatch(uri, requestUri)) return AuthVerdict.Unauthorized()

        val ha2 = StreamAuthCrypto.md5Hex("$method:$uri")
        val expectedResponse = if (!qop.isNullOrBlank()) {
            if (cnonce.isBlank() || nc.isBlank()) return AuthVerdict.Unauthorized()
            StreamAuthCrypto.md5Hex("$digestHa1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            StreamAuthCrypto.md5Hex("$digestHa1:$nonce:$ha2")
        }

        // Verify the response before touching the nonce's replay counter, so
        // a guessed request cannot consume the legitimate client's next nc.
        if (!StreamAuthCrypto.constantTimeEquals(response, expectedResponse)) {
            return AuthVerdict.Unauthorized()
        }
        return when (validateDigestNonce(nonce, username, cnonce, nc, qop)) {
            NonceVerdict.OK -> AuthVerdict.Allowed
            NonceVerdict.STALE -> AuthVerdict.Unauthorized(stale = true)
            NonceVerdict.UNKNOWN -> AuthVerdict.Unauthorized()
        }
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

    private enum class NonceVerdict { OK, STALE, UNKNOWN }

    private fun validateDigestNonce(
        nonce: String,
        username: String,
        cnonce: String,
        ncHex: String,
        qop: String?,
    ): NonceVerdict {
        // Look the presented nonce up before the sweep: the sweep drops
        // expired entries, and an entry still present here is what lets a
        // correct-but-late response get the stale=TRUE signal.
        val presented = digestNonces[nonce]
        if (presented != null && clock() > presented.expiresAtMs) {
            digestNonces.remove(nonce)
            cleanExpiredDigestNonces()
            return NonceVerdict.STALE
        }
        cleanExpiredDigestNonces()
        val state = digestNonces[nonce] ?: return NonceVerdict.UNKNOWN

        if (qop.isNullOrBlank()) return NonceVerdict.OK
        val nc = ncHex.toLongOrNull(16) ?: return NonceVerdict.UNKNOWN
        if (cnonce.isBlank()) return NonceVerdict.UNKNOWN
        val key = "$username|$cnonce"
        val previous = state.ncTrack[key] ?: 0L
        if (nc <= previous) return NonceVerdict.UNKNOWN
        state.ncTrack[key] = nc
        return NonceVerdict.OK
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
        val nonce = Base64Codec.encodeUrlSafe(bytes)
        digestNonces[nonce] = DigestNonceState(clock() + DIGEST_NONCE_TTL_MS, mintSeq.getAndIncrement())
        return nonce
    }

    private fun cleanExpiredDigestNonces() {
        val now = clock()
        // Iterator removal, not Collection.removeIf — that Map default method
        // is API 24+ and this class must run on API 23.
        val iterator = digestNonces.entries.iterator()
        while (iterator.hasNext()) {
            if (now > iterator.next().value.expiresAtMs) iterator.remove()
        }
        if (digestNonces.size > MAX_DIGEST_NONCES) {
            val overflow = digestNonces.size - MAX_DIGEST_NONCES
            // Evict by earliest expiry, ties by mint order. The tie-break is
            // load-bearing: sortedBy is stable and ConcurrentHashMap order is
            // hash-based, so equal expiries (frozen clock, same-ms bursts)
            // would otherwise evict an arbitrary nonce.
            val keysToRemove = digestNonces.entries
                .sortedWith(compareBy({ it.value.expiresAtMs }, { it.value.mintSeq }))
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
