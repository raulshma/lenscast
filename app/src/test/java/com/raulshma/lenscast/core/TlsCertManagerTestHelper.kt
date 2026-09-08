package com.raulshma.lenscast.core

import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate

/** Test-only read seam over the certificate's IP SANs. */
object TlsCertManagerTestHelper {
    fun subjectIps(certificate: X509Certificate): List<String> = try {
        certificate.subjectAlternativeNames
            ?.filter { (it[0] as Int) == 7 }
            ?.map { it[1] as String }
            ?: emptyList()
    } catch (_: CertificateParsingException) {
        emptyList()
    }
}
