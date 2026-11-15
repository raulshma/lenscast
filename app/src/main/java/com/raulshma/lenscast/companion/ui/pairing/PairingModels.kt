package com.raulshma.lenscast.companion.ui.pairing

import com.raulshma.lenscast.companion.client.normalizeFingerprint
import com.raulshma.lenscast.companion.discovery.PairingPayload
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials

/**
 * One pairing probe-chain outcome — the vocabulary the add-device flow's
 * orchestrator emits between its steps and the UI state translates into
 * panes/dialogs. Pure data: no strings (the sheet maps each case to its
 * `companion_*` resource), no Android, no client — the flow test can pin
 * the whole chain against a MockWebServer with these alone.
 */
sealed interface PairingCheck {

    /**
     * The device answered and (when [role] is non-null) the probe learned
     * the sign-in's role — `"admin"` | `"viewer"` as the remote names them.
     * Null role: auth is off, or the token path paired (see
     * [com.raulshma.lenscast.companion.client.LenscastApiClient.authSession]'s
     * KDoc — the server's token path authorizes reads + a write allow-list,
     * never sessions, so no role can ride that answer).
     */
    data class Reachable(val role: String?) : PairingCheck

    /** Auth is required on the remote but the form arrived with blank fields. */
    data object AuthRequiredButNoCredentials : PairingCheck

    /** A BASIC sign-in was rejected (401, or the answer's `success=false`). */
    data object LoginFailed : PairingCheck

    /** The device never answered inside the LAN budgets (or TLS failed). */
    data object NetworkUnreachable : PairingCheck

    /**
     * TLS with no pinned fingerprint: the TOFU probe captured the presented
     * leaf's digest [fingerprint] (canonical lowercase hex, no separators) —
     * the sheet must show it and ask before anything is trusted.
     */
    data class PinRequired(val fingerprint: String) : PairingCheck

    /** The roster + credential stores accepted the pair. */
    data class Saved(val deviceId: String) : PairingCheck

    /**
     * The final reachability test failed with something the earlier steps
     * did not classify (non-2xx on `/api/status`, a mid-chain surprise).
     * Kept distinct from [NetworkUnreachable] so the sheet's message can
     * say "the device answered, but wrongly".
     */
    data object RequestFailed : PairingCheck
}

/**
 * The add-device form, as the fields hold it — port stays text (it is only
 * parsed at assemble time) and the fingerprint stays text (the QR/manual
 * digest may arrive colon-separated uppercase; normalization happens in
 * [PairingAssembler]). Plain data, zero behavior — the sheet's TextFields
 * bind straight onto it through the ViewModel's copy events.
 */
data class PairingForm(
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val useTls: Boolean = false,
    val tlsFingerprint: String = "",
    val authKind: CompanionAuthKind = CompanionAuthKind.TOKEN,
    val token: String = "",
    val username: String = "",
    val password: String = "",
) {
    /** True when the form carries everything [PairingAssembler.inputFromForm] needs. */
    val isSubmittable: Boolean
        get() = host.isNotBlank() && port.toIntOrNull() in 1..MAX_PORT

    companion object {
        const val MAX_PORT = 65535
    }
}

/**
 * The validated, trimmed pairing facts [PairingAssembler.assemble] consumes:
 * the form's text shapes folded into typed fields, the fingerprint already
 * canonical (lowercase hex, no separators — [PairingPayload.tlsFingerprint]'s
 * shape), and the mDNS display name that survives the QR/manual name being
 * blank.
 */
data class PairingInput(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val tlsFingerprint: String?,
    val authKind: CompanionAuthKind,
    val token: String?,
    val username: String?,
    val password: String?,
    /** The user-typed name; blank falls back to [discoveredName], then host:port. */
    val name: String = "",
    /** The discovered service's friendly name ([com.raulshma.lenscast.companion.discovery.NsdDiscoveryFilter.friendlyName]). */
    val discoveredName: String? = null,
)

/** The assembled pair — exactly what the two stores take at save time. */
data class AssembledPairing(
    val device: CompanionDevice,
    val credentials: StoredCredentials,
)

/**
 * The pure half of the add-device flow: form/payload facts + auth kind →
 * the [CompanionDevice] + [StoredCredentials] pair the roster and the
 * credential store persist. No network, no clock, no UUID generation of its
 * own — [assemble] takes `newId` and `nowMs` as arguments so the table
 * tests pin the shapes exactly and the ViewModel owns the injectable
 * sources. Identity rules:
 *
 * - id = the passed [PairingAssembler.assemble] `newId` (a fresh UUID per
 *   pair; re-pairing the same host:port mints a NEW roster entry — the
 *   roster has no upsert-by-address, and "two cameras, same IP, different
 *   times" is a real household shape).
 * - addedAtMs = the passed `nowMs`.
 * - name = [defaultName]: the user's name, else the discovered friendly
 *   name, else `host:port` — never blank, never just whitespace.
 * - role = `revealedRole` ONLY — null unless the login probe actually
 *   answered with one (the token path and auth-off devices stay unknown).
 * - the fingerprint is normalized through
 *   [com.raulshma.lenscast.companion.client.normalizeFingerprint] (the same
 *   canonicalization the TLS pin compares), so a colon-separated QR digest
 *   and a pasted lowercase digest assemble identically.
 * - credentials: exactly one populated secret per [CompanionAuthKind] —
 *   token for TOKEN, username/password for BASIC; blanks fold to null so
 *   the store never keeps empty strings.
 */
object PairingAssembler {

    /**
     * Folds the live form into a [PairingInput], or null when the form is
     * not submittable (blank host / port outside 1..65535) — the same
     * predicate [PairingForm.isSubmittable] shows in the UI, re-checked at
     * the boundary so the sheet can never assemble a half-address.
     */
    fun inputFromForm(form: PairingForm): PairingInput? {
        val port = form.port.trim().toIntOrNull() ?: return null
        if (form.host.isBlank() || port !in 1..PairingForm.MAX_PORT) return null
        return PairingInput(
            host = form.host.trim(),
            port = port,
            useTls = form.useTls,
            tlsFingerprint = form.tlsFingerprint.normalizeOrNull(),
            authKind = form.authKind,
            token = form.token.trim().takeIf { it.isNotEmpty() },
            username = form.username.trim().takeIf { it.isNotEmpty() },
            // The password is the one field NOT trimmed: spaces are legal
            // password characters and trimming them would corrupt the only
            // secret the user typed verbatim.
            password = form.password.takeIf { it.isNotEmpty() },
            name = form.name.trim(),
        )
    }

    /**
     * Folds a decoded QR payload into a [PairingInput] — the scan pane's
     * prefill source. The payload's fingerprint is already canonical
     * (the parser normalizes), kept as-is. [discoveredName] rides when the
     * scan pane was opened from a discovery hit (rare) so the default-name
     * ladder still has a friendly candidate.
     */
    fun inputFromPayload(payload: PairingPayload, discoveredName: String? = null): PairingInput =
        PairingInput(
            host = payload.host,
            port = payload.port,
            useTls = payload.useTls,
            tlsFingerprint = payload.tlsFingerprint?.takeIf { it.isNotEmpty() },
            authKind = CompanionAuthKind.TOKEN,
            token = null,
            username = null,
            password = null,
            discoveredName = discoveredName?.takeIf { it.isNotBlank() },
        )

    /** Folds a discovery hit into a [PairingInput] — the discover pane's prefill source. */
    fun inputFromDiscovery(
        service: com.raulshma.lenscast.companion.model.DiscoveredService,
    ): PairingInput = PairingInput(
        host = service.host,
        port = service.port,
        // mDNS TXT carries no TLS trust anchor — TLS is opt-in from here,
        // and the QR remains the channel that pins the certificate.
        useTls = false,
        tlsFingerprint = null,
        authKind = CompanionAuthKind.TOKEN,
        token = null,
        username = null,
        password = null,
        discoveredName = service.name.takeIf { it.isNotBlank() },
    )

    /** The display-name ladder: user name → discovered friendly name → `host:port`. */
    fun defaultName(input: PairingInput): String =
        input.name.ifBlank {
            input.discoveredName?.ifBlank { null }
        }?.takeIf { it.isNotBlank() } ?: "${input.host}:${input.port}"

    /**
     * Assembles the persisted pair. [revealedRole] is the role the probe
     * chain observed (`"admin"` | `"viewer"`, or null for unknown) — pass it
     * only from an answer, never a guess; the roster's role gate reads it.
     */
    fun assemble(
        input: PairingInput,
        newId: String,
        nowMs: Long,
        revealedRole: String? = null,
    ): AssembledPairing {
        val device = CompanionDevice(
            id = newId,
            name = defaultName(input),
            host = input.host,
            port = input.port,
            useTls = input.useTls,
            tlsFingerprint = input.tlsFingerprint?.normalizeOrNull(),
            auth = input.authKind,
            role = revealedRole,
            addedAtMs = nowMs,
            lastSeenMs = null,
        )
        val credentials = when (input.authKind) {
            CompanionAuthKind.TOKEN -> StoredCredentials(
                kind = CompanionAuthKind.TOKEN,
                token = input.token,
            )
            CompanionAuthKind.BASIC -> StoredCredentials(
                kind = CompanionAuthKind.BASIC,
                username = input.username,
                password = input.password,
            )
        }
        return AssembledPairing(device, credentials)
    }

    /** The canonical fingerprint or null — blank/garbage-free hand-off. */
    private fun String.normalizeOrNull(): String? =
        normalizeFingerprint(this).takeIf { it.isNotEmpty() }
}
