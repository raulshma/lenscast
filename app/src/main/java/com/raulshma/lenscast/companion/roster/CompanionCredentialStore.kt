package com.raulshma.lenscast.companion.roster

import android.util.Log
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.core.Base64Codec
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File

/**
 * The symmetric seal between [StoredCredentials]' plaintext and the bytes at
 * rest. One implementation ships — [KeystoreCompanionCredentialCipher], an
 * Android Keystore AES-GCM key — but the seam keeps the store itself
 * testable (a plaintext or in-memory cipher in unit tests) and lets a future
 * wave swap the key backing without touching the persistence shape.
 */
interface CompanionCredentialCipher {
    /** Encrypts one plaintext payload; the output is cipher-specific opaque bytes. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Inverts [encrypt]; throws when the ciphertext is not authentic. */
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * The companion mode's secret store: one encrypted
 * [StoredCredentials] per paired device, keyed by device id. The whole map
 * serializes as one JSON document at `filesDir/companion/credentials.bin` —
 * `{deviceId: base64(ciphertext)}`, where each ciphertext is
 * [CompanionCredentialCipher.encrypt] over the Moshi-encoded credentials and
 * the `.bin` name advertises that the contents are not roster-style readable
 * JSON. Secrets are never logged (warnings name the cost, never the value),
 * never serialized into the roster JSON, and excluded from any future
 * export: the roster's [CompanionDevice] records only the auth KIND, so
 * leaking the roster leaks no keys, and this file's value at rest is exactly
 * one ciphertext per device.
 *
 * Load-once + cache of the *decoded* values (the device-roster discipline):
 * the first read decrypts every entry once, and [get] serves from the cache —
 * reads never touch the cipher again. An undecryptable entry (Keystore reset,
 * torn write) is dropped with a warning instead of taking the whole store
 * down. Mutations encrypt once ([put]), replace both cached views, and
 * mirror atomically (tmp file + rename) under the store monitor.
 *
 * Signatures frozen in wave 0; implemented in wave 1; new members may be
 * added.
 */
class CompanionCredentialStore(
    filesDir: File,
    private val cipher: CompanionCredentialCipher,
) {

    private val file = File(File(filesDir, "companion"), "credentials.bin")

    // A private Moshi (not AppJson): the credentials wire shape uses only
    // codegen'd adapters — no Secret redaction, no custom types — and the
    // frozen constructor deliberately takes no shared instance.
    private val moshi = Moshi.Builder().build()

    private val mapAdapter by lazy {
        moshi.adapter<Map<String, String>>(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java),
        )
    }

    private val credentialsAdapter by lazy { moshi.adapter(StoredCredentials::class.java) }

    @Volatile
    private var decodedCache: Map<String, StoredCredentials>? = null

    @Volatile
    private var diskCache: Map<String, String>? = null

    /** The stored credentials for one device, or null when none were saved. */
    @Synchronized
    fun get(deviceId: String): StoredCredentials? = decoded()[deviceId]

    /** Stores (replacing any previous) one device's credentials, encrypted at rest. */
    @Synchronized
    fun put(deviceId: String, credentials: StoredCredentials) {
        // Encrypt once here; the cached value is the decoded plaintext, so
        // later reads never touch the cipher again.
        val ciphertext = Base64Codec.encode(
            cipher.encrypt(credentialsAdapter.toJson(credentials).toByteArray(Charsets.UTF_8)),
        )
        persist(
            decoded() + (deviceId to credentials),
            onDisk() + (deviceId to ciphertext),
        )
    }

    /** Removes one device's credentials (no-op when none were saved). */
    @Synchronized
    fun remove(deviceId: String) {
        if (!decoded().containsKey(deviceId)) return
        persist(decoded() - deviceId, onDisk() - deviceId)
    }

    /**
     * The decoded in-memory view: the first call loads the file and decrypts
     * each entry once; later calls serve from the cache.
     */
    private fun decoded(): Map<String, StoredCredentials> {
        decodedCache?.let { return it }
        val decoded = mutableMapOf<String, StoredCredentials>()
        onDisk().forEach { (deviceId, encoded) ->
            val ciphertext = Base64Codec.decodeOrNull(encoded) ?: return@forEach
            try {
                credentialsAdapter.fromJson(String(cipher.decrypt(ciphertext), Charsets.UTF_8))
                    ?.let { decoded[deviceId] = it }
            } catch (e: Exception) {
                // Warn on the failure shape, never on the value: the plaintext
                // and its log line must not travel together.
                Log.w(TAG, "Undecryptable credentials for a paired device; ignoring", e)
            }
        }
        decodedCache = decoded
        return decoded
    }

    /** The ciphertext map as persisted — `{deviceId: base64(ciphertext)}`. */
    private fun onDisk(): Map<String, String> =
        diskCache ?: file.readJsonOrDefault(
            mapAdapter,
            emptyMap(),
            warn = "Failed to read companion credentials; starting clean",
        ).also { diskCache = it }

    /** The one mutation exit: refresh both cached views, then the atomic mirror. */
    private fun persist(nextDecoded: Map<String, StoredCredentials>, nextOnDisk: Map<String, String>) {
        decodedCache = nextDecoded
        diskCache = nextOnDisk
        // Losing the persisted copy only means a re-pair after restart; the
        // warning carries the cost, never the secret.
        file.writeAtomicallyOrWarn(
            mapAdapter.toJson(nextOnDisk),
            warn = "Failed to persist companion credentials",
        )
    }

    companion object {
        private const val TAG = "CompanionCredStore"
    }
}
