package com.raulshma.lenscast.companion.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The trust-on-first-use probe behind the pairing sheet's "this certificate
 * is self-signed — confirm the digest" step. One-shot HTTPS connect against
 * a URL the user is in the middle of pairing, whose ONLY job is to carry the
 * leaf certificate's SHA-256 digest back to the human.
 *
 * SECURITY BOUNDARY — READ BEFORE TOUCHING:
 * This probe NEVER authenticates anything. Its trust manager accepts every
 * certificate unconditionally (it must: the whole point is to inspect a
 * cert that nothing on the LAN vouches for, before the user decides to pin
 * it), and hostname verification is bypassed for the same reason. That
 * makes it a man-in-the-middle's best friend IF USED ANYWHERE ELSE. The
 * guards are procedural and this file refuses to soften them:
 *
 * - the only caller is the pairing flow's TOFU step, which shows the digest
 *   it returns to the user and saves it into [com.raulshma.lenscast.companion.model.CompanionDevice.tlsFingerprint]
 *   only on an explicit confirm. After that, every real request rides
 *   [buildEngine]'s [PinnedTrustManager] — trust-all code never runs again
 *   for the device.
 * - the probe never reads the response body, never sends credentials, and
 *   its client is built per-call with the short LAN budgets (3s connect /
 *   3s read) so it cannot linger.
 * - do NOT promote this class to a general "fetch over any TLS" helper; a
 *   code path that trusts everything and skips hostname checks must stay a
 *   single, loud, pairing-only seam.
 *
 * Results are the typed [Result] family — a digest is success, and the two
 * failure shapes (the host did not answer / the URL is not HTTPS) map to
 * distinct pairing-sheet messages. The digest is the leaf cert's SHA-256 in
 * [normalizeFingerprint]'s canonical form (lowercase hex, no separators) —
 * byte-for-byte the shape [PinnedTrustManager] compares, so a TOFU-confirmed
 * digest pins the exact cert the user just saw.
 */
class CertificateFingerprintProbe {

    /** The probe's outcomes, as the pairing sheet branches on them. */
    sealed interface Result {

        /** The handshake succeeded and the leaf cert hashed to [sha256Hex]. */
        data class Digest(val sha256Hex: String) : Result

        /**
         * The TLS endpoint did not answer inside the LAN budgets (connect
         * refused/timeout, read timeout, DNS failure, or the handshake was
         * rejected outright). [cause] is the original transport error.
         */
        data class Unreachable(val cause: Throwable) : Result

        /** The URL is not `https://` — there is no certificate to inspect. */
        data object TlsUnavailable : Result
    }

    /**
     * One-shot connect against [url] (`https://host:port[/path]`), handing
     * back the presented leaf's digest. Dispatchers.IO; cancellable —
     * cancelling the caller aborts the in-flight handshake.
     */
    suspend fun probe(url: String): Result = withContext(Dispatchers.IO) {
        if (!url.trimStart().startsWith("https://", ignoreCase = true)) {
            return@withContext Result.TlsUnavailable
        }
        val request = Request.Builder()
            .url(url)
            .head()
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(PROBE_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(PROBE_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(PROBE_CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .sslSocketFactory(trustAllContext().socketFactory, RecordingTrustManager())
            // The pin IS the hostname check everywhere else in this package;
            // here no pin exists yet, so the (meaningless for a self-issued
            // cert) SAN comparison is bypassed too. See the class KDoc.
            .hostnameVerifier(TrustAllHostnameVerifier)
            .build()
        try {
            client.newCall(request).await().use { response ->
                val leaf = response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                    ?: return@use Result.Unreachable(
                        SSLException("TLS handshake carried no X.509 leaf certificate"),
                    )
                Result.Digest(digestHex(leaf.encoded))
            }
        } catch (e: IOException) {
            Result.Unreachable(e)
        }
    }

    companion object {

        /**
         * The digest of one DER-encoded certificate in the canonical pinned
         * form — lowercase hex, no separators. The pure seam the tests pin:
         * it must stay byte-for-byte equal to what [PinnedTrustManager]
         * compares (it is [sha256Fingerprint] pushed through
         * [normalizeFingerprint], which is a no-op on that output — kept as
         * an explicit hand-off so a future change to either side cannot
         * silently desync the TOFU digest from the pin comparison).
         */
        fun digestHex(der: ByteArray): String =
            normalizeFingerprint(sha256Fingerprint(der))
    }
}

/**
 * The trust-all recorder. `checkServerTrusted` accepts anything but keeps
 * the presented chain — the probe reads [lastChain] after the handshake.
 * Package-private so the KDoc boundary above stays the single description
 * of the risk; do not reuse outside [CertificateFingerprintProbe].
 */
internal class RecordingTrustManager : X509TrustManager {

    @Volatile
    var lastChain: Array<X509Certificate>? = null
        private set

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        // The companion is a dialer only; it never answers a TLS handshake.
        throw CertificateException("Companion mode never serves TLS")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        lastChain = chain
        // Trust-all, DELIBERATELY: see CertificateFingerprintProbe's security
        // boundary KDoc. This manager authenticates nothing.
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Accepts every name — the pairing probe pins nothing yet. Pairing-only. */
internal object TrustAllHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String, session: javax.net.ssl.SSLSession): Boolean = true
}

/** One trust-all SSL context; cheap enough to rebuild per probe. */
private fun trustAllContext(): SSLContext =
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(RecordingTrustManager()), SecureRandom())
    }

/**
 * The LAN budgets for the TOFU probe — tighter than the client's
 * ([CONNECT_TIMEOUT_S]/[IO_TIMEOUT_S]): a probe answers the question "is
 * something speaking TLS there" and must not outlast the user's patience
 * mid-dialog. 3s connect / 3s read / 6s whole call.
 */
internal const val PROBE_CONNECT_TIMEOUT_S = 3L
internal const val PROBE_READ_TIMEOUT_S = 3L
internal const val PROBE_CALL_TIMEOUT_S = 6L

/**
 * The cancellable suspension point for one OkHttp call — the same shape as
 * [LenscastApiClient]'s private helper, kept local so the probe file stays
 * self-contained (the client's is file-private by design).
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
