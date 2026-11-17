package com.raulshma.lenscast.companion.client

import com.raulshma.lenscast.companion.model.CompanionDevice
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS budgets for one LAN-radio hop, mirrored from the wear client (the
 * other remote-device lane): a dead device must surface as an error in
 * seconds, never a hung spinner — 3s to connect, 8s for a read or write, 12s
 * for the whole call.
 */
internal const val CONNECT_TIMEOUT_S = 3L
internal const val IO_TIMEOUT_S = 8L
internal const val CALL_TIMEOUT_S = 12L

/**
 * The per-device OkHttp engine factory — the trust seam of the companion
 * mode. LensCast devices serve TLS with a self-signed cert (there is no CA
 * on a LAN to vouch for `192.168.x.x`), so the only meaningful identity
 * check is the digest the camera itself publishes in its QR payload
 * (`https://host:port#fp=<sha256>`, see camera/ConnectSheet.kt and
 * core/TlsCertManager.fingerprintOf — colon-separated uppercase hex of the
 * DER cert). When [CompanionDevice.tlsFingerprint] is set, the engine trusts
 * EXACTLY that one cert:
 *
 * - trust-on-first-use as a trust anchor: the scanned digest plays the role
 *   a CA chain would play against the public web, so a man-in-the-middle
 *   that answers the connect with its own cert hashes to a different digest
 *   and is rejected — a pin mismatch raises [CompanionApiException.PinMismatch]
 *   (via the [PinVerificationException] marker the client walks the cause
 *   chain for), never a silent downgrade to plaintext or an "accept anyway".
 * - no hostname verification beyond pin equality: the default verifier
 *   would compare the URL's host against the cert's SANs, which is
 *   meaningless for a self-issued cert whose subject is the device itself —
 *   when the pin matches, the peer IS the paired device; when it doesn't,
 *   the handshake fails before any request is sent. X509TrustManager
 *   extensions (hostname/chain policy hooks) are not needed for the same
 *   reason: plain manager, one rule.
 *
 * Without a fingerprint the engine is the system-trust default (the TOFU
 * confirm — the pairing flow's "show the digest, user approves" step — fills
 * the field afterwards; re-pairing rebuilds the client around the new
 * roster snapshot). Plain-HTTP devices are equally first-class: no TLS
 * requirement anywhere in this factory.
 *
 * One engine per device client: the shared connection pool and TLS session
 * cache are what keep the poll/SSE/media lanes on warm connections.
 */
fun CompanionDevice.buildEngine(connectTimeoutMs: Long = CONNECT_TIMEOUT_S * 1000L): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(IO_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(IO_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
    val pin = tlsFingerprint?.let(::normalizeFingerprint)?.takeIf { it.isNotEmpty() }
    if (useTls && pin != null) {
        val trustManager = PinnedTrustManager(pin)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), null)
        builder.sslSocketFactory(context.socketFactory, trustManager)
            // The pin IS the hostname check (see the class KDoc): accept any
            // name — the trust manager below has already failed the handshake
            // for every cert but the pinned one.
            .hostnameVerifier { _, _ -> true }
    }
    return builder.build()
}

/**
 * A trust manager with exactly one trusted cert, named by its SHA-256
 * digest. Every chain whose leaf does not hash to [expectedSha256Hex] fails
 * the handshake with [PinVerificationException]; nothing else about the cert
 * (expiry chain policy, hostname) is consulted — the digest is the anchor.
 */
internal class PinnedTrustManager(private val expectedSha256Hex: String) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        // The companion is a dialer only; it never answers a TLS handshake.
        throw CertificateException("Companion mode never serves TLS")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull()
            ?: throw PinVerificationException(expectedSha256Hex, actualSha256Hex = null)
        val actual = sha256Fingerprint(leaf)
        if (!fingerprintsEqual(expectedSha256Hex, actual)) {
            throw PinVerificationException(expectedSha256Hex, actualSha256Hex = actual)
        }
    }

    /** No CA anchors to advertise: trust is exactly one pinned leaf. */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * The pin-mismatch marker thrown inside the handshake. JSSE wraps it in an
 * SSLHandshakeException and keeps it as the cause; [LenscastApiClient] walks
 * the cause chain (see [hasPinMismatchCause]) to translate the transport
 * error into [CompanionApiException.PinMismatch] instead of a generic
 * [CompanionApiException.Network] — the two call for completely different UI.
 */
internal class PinVerificationException(
    val expectedSha256Hex: String,
    val actualSha256Hex: String?,
) : CertificateException(
    if (actualSha256Hex == null) {
        "Server presented no certificate (expected $expectedSha256Hex)"
    } else {
        "Certificate fingerprint mismatch (expected $expectedSha256Hex, got $actualSha256Hex)"
    },
)

/** True anywhere in this throwable's cause chain a pin verification failed. */
internal fun Throwable.hasPinMismatchCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is PinVerificationException) return true
        current = current.cause
    }
    return false
}

/** SHA-256 over the DER encoding, lowercase hex without separators. */
internal fun sha256Fingerprint(cert: X509Certificate): String = sha256Fingerprint(cert.encoded)

/** SHA-256 over raw bytes, lowercase hex without separators. */
internal fun sha256Fingerprint(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der)
        .joinToString("") { String.format("%02x", it) }

/**
 * The canonical pinned-digest form: the QR fragment ships colon-separated
 * uppercase pairs (`AA:BB:…`, the Connect-sheet display form), comparisons
 * run on colon-stripped lowercase — normalizing both sides means a
 * copy-pasted digest with any of the display shapes still matches.
 */
internal fun normalizeFingerprint(raw: String): String =
    raw.trim().replace(":", "").replace(" ", "").lowercase()

/**
 * Digest equality, constant-time ([MessageDigest.isEqual] runs the compare
 * over the whole input regardless of where a difference sits) so a MITM
 * probing the pinned digest byte-by-byte through timing gains nothing.
 */
internal fun fingerprintsEqual(expected: String, actual: String): Boolean =
    MessageDigest.isEqual(
        normalizeFingerprint(expected).toByteArray(Charsets.UTF_8),
        normalizeFingerprint(actual).toByteArray(Charsets.UTF_8),
    )
