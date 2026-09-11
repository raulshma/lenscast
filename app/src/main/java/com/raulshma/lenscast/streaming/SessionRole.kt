package com.raulshma.lenscast.streaming

/**
 * The role a web session was minted with. ADMIN is the classic single-credential
 * session (and the API token and auth-off — a token holder is admin, full
 * stop); VIEWER is the optional read-only pair from [com.raulshma.lenscast.data.StreamAuthSettings].
 * [wireName] is the JSON/DTO spelling ("admin" | "viewer"); [fromWireName]
 * folds unknown or legacy values back to ADMIN, so a persisted record from
 * before roles existed decodes as admin.
 */
enum class SessionRole(val wireName: String) {
    ADMIN("admin"),
    VIEWER("viewer"),
    ;

    companion object {
        /** The wire name → role; null, blank, or unknown folds to null (callers default to ADMIN). */
        fun fromWireName(value: String?): SessionRole? =
            entries.firstOrNull { it.wireName == value }
    }
}
