package com.raulshma.lenscast.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

class SelfSignedCertTest {

    private fun keyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `certificate parses through the standard X509 factory and verifies`() {
        val kp = keyPair()
        val der = SelfSignedCert.generate(kp, ipAddresses = listOf("127.0.0.1", "192.168.1.50"))
        val cert = SelfSignedCert.parse(der)
        assertEquals("CN=LensCast", cert.subjectX500Principal.name)
        cert.verify(kp.public)
        assertTrue(cert.notAfter.after(cert.notBefore))
        assertEquals(listOf("127.0.0.1", "192.168.1.50"), TlsCertManagerTestHelper.subjectIps(cert))
    }

    @Test
    fun `fingerprint is a stable colon separated sha256`() {
        val der = SelfSignedCert.generate(keyPair(), ipAddresses = listOf("127.0.0.1"))
        val fingerprint = TlsCertManager.fingerprintOf(der)
        assertEquals(32, fingerprint.split(":").size)
        assertEquals(fingerprint, TlsCertManager.fingerprintOf(der))
    }

    // dnsNameDer / SAN mixing

    @Test
    fun `dnsNameDer encodes the context 2 primitive over the ASCII bytes`() {
        val der = SelfSignedCert.dnsNameDer("lenscast.local")
        assertEquals(0x82, der[0].toInt() and 0xFF)
        assertEquals(14, der[1].toInt()) // short-form length, "lenscast.local".length
        assertArrayEquals(
            "lenscast.local".toByteArray(Charsets.US_ASCII),
            der.copyOfRange(2, der.size),
        )
    }

    @Test
    fun `the SAN extension mixes DNS and IP entries and still parses`() {
        val der = SelfSignedCert.generate(
            keyPair(),
            ipAddresses = listOf("127.0.0.1", "192.168.1.50"),
            dnsNames = listOf(TlsCertManager.STABLE_HOST),
        )
        val cert = SelfSignedCert.parse(der)
        assertEquals(listOf(TlsCertManager.STABLE_HOST), TlsCertManagerTestHelper.subjectDnsNames(cert))
        assertEquals(listOf("127.0.0.1", "192.168.1.50"), TlsCertManagerTestHelper.subjectIps(cert))
    }

    @Test
    fun `a DNS-only certificate still parses and carries no IP SAN`() {
        val der = SelfSignedCert.generate(
            keyPair(),
            ipAddresses = emptyList(),
            dnsNames = listOf(TlsCertManager.STABLE_HOST),
        )
        val cert = SelfSignedCert.parse(der)
        assertEquals(listOf(TlsCertManager.STABLE_HOST), TlsCertManagerTestHelper.subjectDnsNames(cert))
        assertEquals(emptyList<String>(), TlsCertManagerTestHelper.subjectIps(cert))
    }

    @Test
    fun `certificates without dns names keep the ip-only SAN`() {
        val der = SelfSignedCert.generate(keyPair(), ipAddresses = listOf("127.0.0.1"))
        val cert = SelfSignedCert.parse(der)
        assertEquals(emptyList<String>(), TlsCertManagerTestHelper.subjectDnsNames(cert))
        assertEquals(listOf("127.0.0.1"), TlsCertManagerTestHelper.subjectIps(cert))
    }
}
