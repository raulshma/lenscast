package com.raulshma.lenscast.companion.roster

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The production [CompanionCredentialCipher]: an AES-256-GCM key held in the
 * Android Keystore (generated on first use, never extractable), so the
 * companion credentials at rest are only decryptable on this device's TEE/
 * StrongBox — a copied credentials file is useless elsewhere. Threat model:
 * at-rest protection for paired-device credentials against file-level
 * exfiltration (backups, bug reports, a stolen image of app data); the key
 * is device-bound with no escrow, so the flip side is deliberate — wiping
 * the Keystore (clearing app data, a device migration) permanently loses
 * every stored secret, and the roster's re-pair affordance is the recovery.
 *
 * Wire shape: `IV || ciphertext` — the GCM IV the cipher generated prefixes
 * the body ([combineIvAndCiphertext]/[splitIvAndCiphertext] own the layout),
 * because AndroidKeyStore does not release the key, the caller must carry the
 * per-write nonce alongside the ciphertext.
 *
 * Failure posture is fail-loud: every [GeneralSecurityException] — a tampered
 * ciphertext's `AEADBadTagException` included — propagates to
 * [CompanionCredentialStore]'s per-entry catch. There is deliberately no
 * plaintext fallback: silently degrading to unencrypted storage would turn a
 * broken Keystore into an invisible secret leak, which is strictly worse
 * than losing the credentials.
 */
class KeystoreCompanionCredentialCipher : CompanionCredentialCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            return combineIvAndCiphertext(cipher.iv, cipher.doFinal(plaintext))
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        synchronized(lock) {
            val (iv, body) = splitIvAndCiphertext(ciphertext)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(body)
        }
    }

    /**
     * The resident Keystore key, generated on first use. `getEntry` (not
     * `getKey`) keeps the key material out of the calling process entirely —
     * a SecretKeyEntry hands back the TEE-backed handle, never the bytes.
     */
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private val lock = Any()

    companion object {
        private const val PROVIDER = "AndroidKeyStore"

        /** The Keystore entry name — namespaced so it cannot collide with the TLS identity key. */
        const val ALIAS = "lenscast_companion_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256

        /** GCM's standard 12-byte IV; the prefix length the wire shape reserves. */
        const val IV_BYTES = 12

        /** The GCM tag length [Cipher] validates the body's auth tag with. */
        private const val GCM_TAG_BITS = 128

        /**
         * The at-rest wire shape's assembly: IV first, ciphertext behind it.
         * Pure and JVM-tested — the layout is the one thing about the Keystore
         * path a unit test can pin.
         */
        internal fun combineIvAndCiphertext(iv: ByteArray, ciphertext: ByteArray): ByteArray =
            iv + ciphertext

        /**
         * The inverse of [combineIvAndCiphertext]: the leading [IV_BYTES] are
         * the IV, the rest the body. Throws when the payload is too short to
         * even carry an IV — fail-loud, same posture as the cipher itself.
         */
        internal fun splitIvAndCiphertext(data: ByteArray): Pair<ByteArray, ByteArray> {
            require(data.size > IV_BYTES) {
                "ciphertext too short for the IV prefix (${data.size} bytes)"
            }
            return data.copyOfRange(0, IV_BYTES) to data.copyOfRange(IV_BYTES, data.size)
        }
    }
}
