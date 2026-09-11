package com.raulshma.lenscast.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * The VAPID identity's JVM contract: persistence round-trip (load-once across
 * process lifetimes — a rotated key silently orphans subscriptions),
 * regeneration from a corrupt store file, the 65-byte uncompressed-point wire
 * form, and the padded-free base64url encoding the browser's
 * `applicationServerKey` and the RFC 8292 `k` parameter consume.
 */
class VapidKeysTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun load(file: File): VapidKeys = VapidKeys.loadOrCreate(file)

    @Test
    fun `loadOrCreate persists the pair across loads`() {
        val file = File(tmp.root, "push/vapid.key")
        val first = load(file)
        val second = load(file)

        assertTrue(file.exists())
        assertNotEquals(first, second) // distinct wrapper objects…
        assertEquals(first.publicKeyUncompressed().toList(), second.publicKeyUncompressed().toList())
        assertEquals(first.publicKeyBase64Url(), second.publicKeyBase64Url())
    }

    @Test
    fun `a corrupt store file regenerates rather than crashing`() {
        val file = File(tmp.root, "push/vapid.key")
        val original = load(file)
        file.writeText("not a key container")

        val regenerated = load(file)

        assertNotEquals(
            original.publicKeyUncompressed().toList(),
            regenerated.publicKeyUncompressed().toList(),
        )
    }

    @Test
    fun `an absent parent directory is created`() {
        val file = File(File(File(tmp.root, "a"), "b"), "vapid.key")
        load(file)
        assertTrue(file.exists())
    }

    @Test
    fun `the public key is the 65-byte uncompressed point`() {
        val keys = load(File(tmp.root, "vapid.key"))
        val point = keys.publicKeyUncompressed()

        assertEquals(65, point.size)
        assertEquals(0x04, point[0].toInt() and 0xFF)

        // The point decodes back to the key's own EC coordinates.
        val public = keys.publicKey() as ECPublicKey
        val x = point.copyOfRange(1, 33)
        val y = point.copyOfRange(33, 65)
        assertEquals(public.w.affineX, java.math.BigInteger(1, x))
        assertEquals(public.w.affineY, java.math.BigInteger(1, y))
    }

    @Test
    fun `the base64url form is padding-free and matches the raw point`() {
        val keys = load(File(tmp.root, "vapid.key"))
        val encoded = keys.publicKeyBase64Url()

        assertFalse(encoded.contains('='))
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
        assertEquals(
            keys.publicKeyUncompressed().toList(),
            Base64.getUrlDecoder().decode(encoded).toList(),
        )
    }

    @Test
    fun `the private key signs under ES256 and the public key verifies`() {
        val keys = load(File(tmp.root, "vapid.key"))
        val input = "header.claims"

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keys.privateKey())
        signer.update(input.toByteArray())
        val derSignature = signer.sign()

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(keys.publicKey())
        verifier.update(input.toByteArray())
        assertTrue(verifier.verify(derSignature))
    }
}
