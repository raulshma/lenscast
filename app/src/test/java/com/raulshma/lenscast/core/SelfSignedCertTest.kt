package com.raulshma.lenscast.core

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
}
