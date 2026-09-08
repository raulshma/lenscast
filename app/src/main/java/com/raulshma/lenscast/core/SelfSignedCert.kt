package com.raulshma.lenscast.core

import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Calendar

/**
 * Minimal self-signed X.509v3 certificate builder over hand-rolled DER —
 * no BouncyCastle dependency (a multi-MB APK cost for one certificate).
 * The profile is fixed: RSA (the caller's [KeyPair]), SHA256withRSA, CN=
 * LensCast, serverAuth EKU, digitalSignature|keyEncipherment key usage,
 * CA:false, and a subjectAltName carrying the device's IPv4 addresses
 * (browsers match https URLs against SAN entries, not CN).
 *
 * JVM-tested: the produced bytes parse through the standard
 * [CertificateFactory] and the signature verifies against the key pair.
 */
object SelfSignedCert {

    /** DER-encode a self-signed certificate for [keyPair]. */
    fun generate(
        keyPair: KeyPair,
        subjectCn: String = "LensCast",
        ipAddresses: List<String>,
        validityDays: Int = 3650,
        notBefore: java.util.Date = java.util.Date(),
    ): ByteArray {
        val calendar = Calendar.getInstance().apply {
            time = notBefore
            add(Calendar.DAY_OF_YEAR, validityDays)
        }
        val notAfter = calendar.time
        val serial = BigInteger(63, java.security.SecureRandom()).abs().add(BigInteger.ONE)

        val tbs = encodeTbs(keyPair, subjectCn, ipAddresses, serial, notBefore, notAfter)
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbs)
        val signature = signer.sign()

        val sigAlg = sequence(oid(1, 2, 840, 113549, 1, 1, 11) + asn1Null())
        val sigValue = bitString(signature)
        return sequence(tbs + sigAlg + sigValue)
    }

    /** Parse-and-verify helper used by tests and by [TlsCertManager] on load. */
    fun parse(der: ByteArray): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun encodeTbs(
        keyPair: KeyPair,
        cn: String,
        ips: List<String>,
        serial: BigInteger,
        notBefore: java.util.Date,
        notAfter: java.util.Date,
    ): ByteArray {
        val version = contextTag(0xA0, integer(2))
        val serialDer = integer(serial)
        val sigAlg = sequence(oid(1, 2, 840, 113549, 1, 1, 11) + asn1Null())
        val name = nameDer(cn)
        val validity = sequence(utcTime(notBefore) + utcTime(notAfter))
        val spki = keyPair.public.encoded // already X.509 SubjectPublicKeyInfo DER
        val extensions = contextTag(
            0xA3,
            sequence(
                // basicConstraints: CA=false
                sequence(oid(2, 5, 29, 19) + octetString(sequence(ByteArray(0)))) +
                    // keyUsage: digitalSignature (0) | keyEncipherment (2)
                    sequence(
                        oid(2, 5, 29, 15) + octetString(
                            bitStringRaw(byteArrayOf(0xA0.toByte()), unusedBits = 5),
                        )
                    ) +
                    // extKeyUsage: serverAuth
                    sequence(
                        oid(2, 5, 29, 37) + octetString(
                            sequence(oid(1, 3, 6, 1, 5, 5, 7, 3, 1)),
                        )
                    ) +
                    // subjectAltName: IP addresses as [7] OCTET STRING entries
                    sequence(
                        oid(2, 5, 29, 17) + octetString(
                            sequence(ips.mapNotNull { ipToDer(it) }.toTypedArray().fold(ByteArray(0), ByteArray::plus)),
                        )
                    ),
            ),
        )
        return sequence(
            version + serialDer + sigAlg + name + validity + name + spki + extensions,
        )
    }

    private fun nameDer(cn: String): ByteArray = sequence(
        set(
            sequence(oid(2, 5, 4, 3) + utf8String(cn)),
        ),
    )

    private fun set(content: ByteArray): ByteArray = tagged(0x31, content)

    private fun ipToDer(ip: String): ByteArray? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        val bytes = parts.map { it.toIntOrNull() ?: return null }.map { it.toByte() }.toByteArray()
        return contextTag(0x87, bytes) // [7] IMPLICIT OCTET STRING (iPAddress)
    }

    // ── DER primitives ──

    private fun sequence(vararg children: ByteArray): ByteArray = sequence(children.fold(ByteArray(0), ByteArray::plus))

    private fun sequence(content: ByteArray): ByteArray = tagged(0x30, content)

    private fun tagged(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + encodeLength(content.size) + content

    private fun contextTag(tag: Int, content: ByteArray): ByteArray = tagged(tag, content)

    private fun integer(value: Long): ByteArray {
        val bytes = ByteArray(8) { i -> (value shr ((7 - i) * 8)).toByte() }
        return integer(bytes)
    }

    private fun integer(value: BigInteger): ByteArray = integer(value.toByteArray())

    private fun integer(magnitude: ByteArray): ByteArray {
        // Strip redundant leading zero bytes (keep one when required by sign bit).
        var start = 0
        while (start < magnitude.size - 1 && magnitude[start] == 0.toByte() && magnitude[start + 1].toInt() and 0x80 == 0) start++
        val content = magnitude.copyOfRange(start, magnitude.size)
        return tagged(0x02, content)
    }

    private fun oid(vararg arcs: Int): ByteArray {
        val body = mutableListOf<Byte>()
        body.add((arcs[0] * 40 + arcs[1]).toByte())
        for (i in 2 until arcs.size) {
            var value = arcs[i].toLong()
            val temp = mutableListOf<Byte>()
            temp.add((value and 0x7F).toByte())
            value = value shr 7
            while (value > 0) {
                temp.add(((value and 0x7F) or 0x80).toByte())
                value = value shr 7
            }
            temp.reverse()
            body.addAll(temp)
        }
        return tagged(0x06, body.toByteArray())
    }

    private fun utf8String(value: String): ByteArray = tagged(0x0C, value.toByteArray(Charsets.UTF_8))

    private fun utcTime(date: java.util.Date): ByteArray {
        val format = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return tagged(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun octetString(content: ByteArray): ByteArray = tagged(0x04, content)

    private fun bitString(content: ByteArray): ByteArray = tagged(0x03, byteArrayOf(0) + content)

    private fun bitStringRaw(content: ByteArray, unusedBits: Int): ByteArray = tagged(0x03, byteArrayOf(unusedBits.toByte()) + content)

    private fun asn1Null(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun encodeLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
        length <= 0xFFFF -> byteArrayOf(
            0x82.toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        )
        else -> byteArrayOf(
            0x83.toByte(),
            (length shr 16).toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        )
    }
}
