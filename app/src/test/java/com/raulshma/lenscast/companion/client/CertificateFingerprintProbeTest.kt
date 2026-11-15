package com.raulshma.lenscast.companion.client

import com.raulshma.lenscast.companion.discovery.NsdDiscoveryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TOFU probe's pure seam: the digest must land in exactly the shape
 * [PinnedTrustManager] compares (lowercase hex, colon-free —
 * [normalizeFingerprint]'s canonical form), and a QR-payload fingerprint
 * and a probed digest must normalize to byte-identical strings. No TLS
 * handshake runs here — the full connect path is a loopback-checklist item
 * (it needs a real PKI pair); these pin the normalization contract the
 * handshake path composes.
 */
class CertificateFingerprintProbeTest {

    /** The SHA-256 of the bytes "abc" (FIPS 180 test vector), canonical form. */
    private val abcDigest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `digestHex computes the canonical lowercase colon-free digest`() {
        val digest = CertificateFingerprintProbe.digestHex("abc".toByteArray(Charsets.UTF_8))
        assertEquals(abcDigest, digest)
        assertTrue("must be lowercase", digest.none { it.isUpperCase() })
        assertTrue("must be colon-free", digest.none { it == ':' })
        assertEquals("sha-256 hex is 64 chars", 64, digest.length)
    }

    @Test
    fun `digestHex matches sha256Fingerprint pushed through normalizeFingerprint`() {
        // Parity by construction: the probe's digest IS the pinning side's
        // pipeline — if either side changes shape, this fails first.
        val der = "some-cert-der-bytes".toByteArray(Charsets.UTF_8)
        assertEquals(
            normalizeFingerprint(sha256Fingerprint(der)),
            CertificateFingerprintProbe.digestHex(der),
        )
    }

    @Test
    fun `a QR fingerprint and the probed digest compare equal`() {
        // The Connect sheet's QR ships colon-separated uppercase; the probe
        // returns canonical. The pairing flow saves one into the roster and
        // the pin comparison runs against the other — they must agree.
        val qrPayload = "https://192.168.1.5:8443/#fp=" +
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:" +
            "B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD"
        val qrDigest = requireNotNull(NsdDiscoveryFilter.parsePairingPayload(qrPayload)).tlsFingerprint!!
        val probed = CertificateFingerprintProbe.digestHex("abc".toByteArray(Charsets.UTF_8))

        assertEquals(normalizeFingerprint(qrDigest), probed)
        assertTrue(
            "the pin comparison path must accept both",
            fingerprintsEqual(qrDigest, probed),
        )
    }
}
