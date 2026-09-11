package com.raulshma.lenscast.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Opt-in media-at-rest encryption: one AES-256-GCM blob per capture file,
 * keyed by a single hardware-backed key. The at-rest format is
 *
 * ```
 * "LCE1" (4 bytes, ASCII) | nonce (12 bytes) | ciphertext (GCM tag appended)
 * ```
 *
 * so a file carries its own nonce and the 4-byte magic lets every reader
 * sniff, per file, whether it is encrypted — the migration discipline: no
 * metadata says "encrypted" anywhere, encryption turns on and off freely, and
 * a mixed library (old plaintext + new ciphertext) reads correctly forever.
 * The real extension stays on the file name (`.jpg`/`.mp4`) — decryption is
 * transparent, so the name never lies.
 *
 * The pure pieces — header format, nonce generation, size math, and the
 * byte-level round-trip — live here and are JVM-tested. The Keystore is
 * hidden behind [KeyProvider]: production wires [KeystoreMediaKeyProvider],
 * JVM tests wire a fake [KeyProvider] over a plain AES key, so the crypto
 * itself (including GCM tamper/truncation detection) is testable without a
 * device. The two stream wrappers are hand-rolled instead of
 * `CipherInputStream`/`CipherOutputStream` because the platform streams
 * swallow `AEADBadTagException` — here an auth failure (tampered or
 * truncated file) surfaces on `read`/`close` exactly where the consumer can
 * react to it.
 */
object MediaCrypto {

    /** The at-rest magic: "LensCast Encrypted, format 1". */
    const val MAGIC_TEXT = "LCE1"
    val MAGIC_BYTES = MAGIC_TEXT.toByteArray(Charsets.US_ASCII)

    const val NONCE_SIZE_BYTES = 12
    const val KEY_SIZE_BITS = 256
    const val TAG_SIZE_BITS = 128

    /** magic + nonce. */
    val HEADER_SIZE_BYTES = MAGIC_BYTES.size + NONCE_SIZE_BYTES

    /** What a stored file costs over its plaintext: header + GCM tag. */
    val OVERHEAD_BYTES = HEADER_SIZE_BYTES + TAG_SIZE_BITS / 8

    const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** The one Android Keystore alias for the app's media key. */
    const val KEYSTORE_ALIAS = "LensCastMediaKey"

    /**
     * The key seam: production resolves the Keystore-backed AES-256 key,
     * JVM tests hand back a generated one. Called per open (the Keystore
     * round-trip is cheap and the key never leaves the provider).
     */
    fun interface KeyProvider {
        fun getOrCreateKey(): SecretKey
    }

    // ── Header (pure) ──

    /** The 16-byte at-rest header: magic + [nonce]. */
    fun headerFor(nonce: ByteArray): ByteArray = MAGIC_BYTES + nonce

    /**
     * The nonce out of a full or header-prefixed blob, null when the magic
     * does not match or the bytes run out early — the "not ours" verdict.
     */
    fun parseHeader(bytes: ByteArray): ByteArray? {
        if (bytes.size < HEADER_SIZE_BYTES) return null
        if (!isEncryptedHeader(bytes)) return null
        return bytes.copyOfRange(MAGIC_BYTES.size, HEADER_SIZE_BYTES)
    }

    /** Sniff on the first bytes only — true when they carry the magic. */
    fun isEncryptedHeader(firstBytes: ByteArray): Boolean =
        firstBytes.size >= MAGIC_BYTES.size &&
            firstBytes.copyOfRange(0, MAGIC_BYTES.size).contentEquals(MAGIC_BYTES)

    /** A fresh per-file nonce. */
    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(NONCE_SIZE_BYTES).also(random::nextBytes)

    /** Stored (ciphertext) length for a plaintext length: [OVERHEAD_BYTES] on top. */
    fun ciphertextSize(plaintextBytes: Long): Long = plaintextBytes + OVERHEAD_BYTES

    /** Plaintext length behind a stored (ciphertext) length; floored at 0. */
    fun plaintextSize(storedBytes: Long): Long = (storedBytes - OVERHEAD_BYTES).coerceAtLeast(0)

    // ── One-shot (pure over the injected key) ──

    /** Encrypts whole bytes, header included; [nonce] injectable for tests. */
    fun encrypt(key: SecretKey, plaintext: ByteArray, nonce: ByteArray = newNonce()): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        return headerFor(nonce) + cipher.doFinal(plaintext)
    }

    /**
     * Decrypts whole bytes produced by [encrypt]. A tampered or truncated
     * blob throws `AEADBadTagException` from the GCM auth check.
     */
    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        val nonce = parseHeader(blob)
            ?: throw IllegalArgumentException("Not a LensCast encrypted blob (bad magic/length)")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        return cipher.doFinal(blob, HEADER_SIZE_BYTES, blob.size - HEADER_SIZE_BYTES)
    }

    // ── Streams (encrypt-on-write / decrypt-on-read) ──

    /**
     * Opens the encrypting side of a write: writes the header into [out]
     * immediately, then every write is GCM-encrypted into it. Closing the
     * returned stream finishes the tag and closes [out] — so a capture
     * written through this seam is complete or absent, never half-tagged.
     */
    fun encryptTo(key: SecretKey, out: OutputStream, nonce: ByteArray = newNonce()): GcmEncryptStream {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        out.write(headerFor(nonce))
        return GcmEncryptStream(cipher, out)
    }

    /**
     * Opens the decrypting side of a read. [header] is the bytes already
     * consumed from [input] by the caller's sniff — validated here, so a
     * plaintext stream handed in by mistake fails loudly instead of
     * decrypting garbage.
     */
    fun decryptFrom(key: SecretKey, header: ByteArray, input: InputStream): GcmDecryptStream {
        val nonce = parseHeader(header)
            ?: throw IllegalArgumentException("Not a LensCast encrypted blob (bad magic/length)")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        return GcmDecryptStream(cipher, input)
    }

    /** Re-wraps whole encrypted bytes as a decrypting stream (tests, small blobs). */
    fun decryptingStream(key: SecretKey, blob: ByteArray): InputStream =
        decryptFrom(
            key,
            blob.copyOf(HEADER_SIZE_BYTES),
            ByteArrayInputStream(blob, HEADER_SIZE_BYTES, blob.size - HEADER_SIZE_BYTES),
        )
}

/**
 * The encrypting OutputStream: ciphertext out, tag appended on close. Unlike
 * the platform `CipherOutputStream`, a failed `doFinal` here propagates —
 * a close that could not finish the tag is a failed write, never silence.
 */
class GcmEncryptStream internal constructor(
    private val cipher: Cipher,
    private val out: OutputStream,
) : OutputStream() {

    /** Plaintext bytes accepted so far (the size math's input). */
    var plaintextBytes: Long = 0L
        private set

    override fun write(b: Int) = write(byteArrayOf(b.toByte()))

    override fun write(b: ByteArray) = write(b, 0, b.size)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val produced = cipher.update(b, off, len)
        if (produced != null) out.write(produced)
        plaintextBytes += len
    }

    override fun flush() = out.flush()

    override fun close() {
        try {
            out.write(cipher.doFinal())
            out.flush()
        } finally {
            out.close()
        }
    }
}

/**
 * The decrypting InputStream: sequential GCM plaintext, with the tag
 * verified at end-of-input — a tampered or truncated ciphertext surfaces
 * `AEADBadTagException` from the read that reaches the end, not as a silent
 * early EOF like the platform `CipherInputStream` produces.
 */
class GcmDecryptStream internal constructor(
    private val cipher: Cipher,
    private val input: InputStream,
) : InputStream() {

    private val chunk = ByteArray(CHUNK_BYTES)
    private var plain = ByteArray(0)
    private var pos = 0
    private var finalized = false

    /** Refills [plain]; false only after the tag has been verified and drained. */
    private fun fill(): Boolean {
        if (pos < plain.size) return true
        while (!finalized) {
            val read = input.read(chunk)
            if (read >= 0) {
                val produced = cipher.update(chunk, 0, read)
                if (produced != null && produced.isNotEmpty()) {
                    plain = produced
                    pos = 0
                    return true
                }
            } else {
                finalized = true
                // The auth verdict: throws AEADBadTagException on tamper/truncation.
                plain = cipher.doFinal() ?: ByteArray(0)
                pos = 0
            }
        }
        return pos < plain.size
    }

    override fun read(): Int = if (fill()) plain[pos++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, plain.size - pos)
        System.arraycopy(plain, pos, b, off, n)
        pos += n
        return n
    }

    /**
     * GCM decrypts sequentially, so a skip is read-and-discard — the honest
     * O(position) cost behind random-access reads (HTTP range requests,
     * ExoPlayer seeks) over encrypted media.
     */
    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        var remaining = n
        val scratch = ByteArray(SKIP_BUFFER_BYTES)
        while (remaining > 0) {
            val step = read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (step < 0) break
            remaining -= step
        }
        return n - remaining
    }

    override fun available(): Int = plain.size - pos

    override fun close() = input.close()

    companion object {
        private const val CHUNK_BYTES = 64 * 1024
        private const val SKIP_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * The production [MediaCrypto.KeyProvider]: an AES-256 key generated once in
 * the Android Keystore and reused for every capture. StrongBox stays off
 * (not universally available), digests are not set (n/a for AES), and no
 * user authentication gates the key — the threat model is media at rest
 * behind the lock screen, not per-view biometrics. The per-file nonce rides
 * the file header, never the key spec, so one key serves unlimited files.
 */
class KeystoreMediaKeyProvider : MediaCrypto.KeyProvider {

    @Synchronized
    override fun getOrCreateKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(MediaCrypto.KEYSTORE_ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                MediaCrypto.KEYSTORE_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(MediaCrypto.KEY_SIZE_BITS)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
