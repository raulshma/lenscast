package com.raulshma.lenscast.core

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter

/**
 * The Secret wire type — the write-only-credential convention owned in ONE
 * place instead of re-enforced by hand at every settings field.
 *
 * The convention, previously hand-copied across every secret field
 * (rtmpUrl, whipToken, srtUrl, mqttPassword, backupWebdavPassword,
 * telegramBotToken, apiToken):
 *
 * - **GET is blank.** A `Secret` field always serializes `""`, whatever the
 *   DTO carries in memory — the adapter ([SecretWireAdapter]) is the writer,
 *   so the handler can copy the stored value into the response and still
 *   never emit it. The stored secret never round-trips.
 * - **PUT is keep-or-overwrite.** An absent or empty incoming value keeps
 *   the stored secret; a non-empty one overwrites it ([SecretUpdate]). The
 *   dashboard edits a secret by presenting a fresh value only, never by
 *   echoing the stored one back — it cannot echo what it never received.
 *
 * The type wraps the stored value as a plain identity class riding the
 * settings DTO fields (exactly the TS mirror's unchanged `string`), so the
 * wire shape — and with it the `web/contract/settings.json` /
 * `settings-export.json` fixtures and `DtoContractFixtureTest` — does not
 * move. Decoding is transparent: the adapter reads the presented value
 * as-is, which is what makes the PUT path able to resolve it. Serialization
 * through any Moshi instance that did not register the adapter fails closed
 * (adapter-not-found), never leaks.
 *
 * An `@JvmInline value class` was evaluated first and rejected on two
 * counts. A `@JsonQualifier` annotation cannot work at all: this build's
 * KSP2 backend cannot run moshi-kotlin-codegen's qualifier path
 * (square/moshi#1874 — the generated-adapter preparation crashes reading
 * the qualifier's `@Retention`). And the inline class itself breaks the
 * codegen decode path: moshi-kotlin-codegen's generated adapter resolves
 * the DTO constructor reflectively with the declared parameter types
 * (`Secret.class`), but a value-class parameter erases to its underlying
 * `String` on the JVM — the lookup throws NoSuchMethodException on the
 * first decode of any DTO carrying a secret field. The identity class
 * keeps the constructor's JVM signature equal to its declared one, which
 * is what makes the generated adapter's decode path work unchanged.
 *
 * One layered exception, kept distinct and documented at its site: the
 * **apiToken** stores only the SHA-256 hash of the plaintext and gates its
 * enable flag on a usable credential existing after the update
 * ([SecretUpdate.willHaveSecret]).
 *
 * The auth-config route's password pair predates this type and keeps its own
 * handler-side contract (see `AuthWebHandler`).
 */
data class Secret(val value: String)

/**
 * The Moshi adapter behind [Secret]: serializes every value blank, decodes
 * transparently (the PUT path must see the presented plaintext to resolve
 * it). Registered once on App Json — the app's single Moshi instance — so
 * every `Secret` field in the codegen'd DTO graph picks it up.
 */
object SecretWireAdapter : JsonAdapter<Secret>() {

    /**
     * Always writes `""`: a credential is accepted on write and never echoed
     * on read. Moshi's null-safety wrapper means codegen never hands null
     * here; a `null` (direct use only) writes a JSON null.
     */
    override fun toJson(writer: JsonWriter, value: Secret?) {
        if (value == null) writer.nullValue() else writer.value("")
    }

    /** Reads the presented value as-is; emptiness is resolved by [SecretUpdate], not here. */
    override fun fromJson(reader: JsonReader): Secret? = Secret(reader.nextString())
}

/**
 * The write-only-secret update rule in one place: every settings write path
 * (the Web API handler's PUT loop, the import path that reuses it, the
 * in-app SettingsViewModel) decides keep-vs-overwrite through this object
 * instead of a hand-rolled `isNotEmpty` guard per field.
 *
 * Whitespace is content: only `null` or the empty string keeps the stored
 * secret. (Trimming, where a setting wants it, remains the store's save-side
 * descriptor concern — the wire-level keep/overwrite verdict must not
 * silently swallow a value.)
 */
object SecretUpdate {

    /**
     * The keep-or-overwrite rule: an absent or empty incoming value keeps
     * [stored]; a non-empty one overwrites it.
     */
    fun resolve(incoming: String?, stored: String): String =
        if (incoming.isNullOrEmpty()) stored else incoming

    /**
     * True when an update carries a new secret — the overwrite branch of
     * [resolve]. The write-side guard: only then does the caller invoke the
     * store's saver (an empty update never touches the stored credential,
     * not even to write it back unchanged).
     */
    fun overwrites(incoming: String?): Boolean = !incoming.isNullOrEmpty()

    /**
     * True when a usable (non-blank) secret would be in effect after
     * applying [resolve]: an incoming secret, or a stored one. The apiToken
     * enable gate's verdict — the one place a flag may arm only if the
     * update leaves a credential behind, so enabling cannot arm the token
     * path without one (the gate fails closed on a blank hash).
     */
    fun willHaveSecret(incoming: String?, stored: String): Boolean =
        overwrites(incoming) || stored.isNotBlank()
}
