package com.raulshma.lenscast.update

import com.raulshma.lenscast.core.StreamAuthCrypto
import com.raulshma.lenscast.core.toHexString
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The APK integrity gate between the download completing and the install
 * firing: streaming SHA-256 over the downloaded file, constant-time hex
 * compare against GitHub's release-asset `digest` (`sha256:<hex>`), plus a
 * cheap downloaded-size cross-check against the asset's `size`.
 *
 * No (or non-sha256) expected digest is [Verdict.NoDigestProvided] — the
 * install proceeds with a logged warning, because GitHub omits the field on
 * some releases and the digest travels the same channel as the APK anyway.
 * Only a computable digest that does not match the file — including a
 * wrong-length one — is [Verdict.Mismatch], which must delete the partial
 * file and block the install.
 *
 * The hashing and the verdict are pure over [InputStream]/strings and
 * JVM-tested; [verify] is the thin File adapter over them.
 */
object UpdateIntegrity {

    /** The install gate's answer for one downloaded APK. */
    sealed interface Verdict {
        data object Verified : Verdict
        data object NoDigestProvided : Verdict
        data object Mismatch : Verdict
    }

    private const val DIGEST_SCHEME = "sha256:"
    private const val BUFFER_SIZE_BYTES = 8 * 1024

    /**
     * The verdict for a file whose SHA-256 hex is [actualHex] against the
     * expected release-asset digest. Only `sha256:`-prefixed digests are
     * verifiable; anything absent, blank, or under another scheme degrades
     * to [Verdict.NoDigestProvided]. The hex compare is constant-time
     * ([StreamAuthCrypto]) and case-insensitive.
     */
    fun verdictFor(actualHex: String, expectedDigest: String?): Verdict {
        val expected = expectedDigest
            ?.takeIf { it.startsWith(DIGEST_SCHEME) }
            ?.removePrefix(DIGEST_SCHEME)
            ?.takeIf { it.isNotBlank() }
            ?: return Verdict.NoDigestProvided
        return if (StreamAuthCrypto.constantTimeEquals(actualHex.lowercase(), expected.lowercase())) {
            Verdict.Verified
        } else {
            Verdict.Mismatch
        }
    }

    /** Hashes the downloaded APK and rules on it. */
    fun verify(apkFile: File, expectedDigest: String?): Verdict =
        verdictFor(sha256Hex(apkFile.inputStream()), expectedDigest)

    /** Streaming SHA-256 hex of [input]; the stream is consumed and closed. */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        // Locale-independent: hex digits via the shared core encoding.
        return digest.digest().toHexString()
    }

    /**
     * The size cross-check: the downloaded byte count against the release
     * asset's `size`. A non-positive expectation (unknown size) never fails.
     */
    fun sizeMatches(actualBytes: Long, expectedBytes: Long): Boolean =
        expectedBytes <= 0L || actualBytes == expectedBytes
}
