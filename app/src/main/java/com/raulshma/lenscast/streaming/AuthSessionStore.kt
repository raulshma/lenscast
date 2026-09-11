package com.raulshma.lenscast.streaming
import com.squareup.moshi.JsonClass

import android.content.Context
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import com.squareup.moshi.Types
import java.io.File

/**
 * One persisted session: the token, its expiry epoch-ms, and the role it
 * speaks for (`"admin"` | `"viewer"`). The `t`/`e` field names are the legacy
 * org.json wire names, kept decode-compatible through the App Json migration;
 * `r` postdates them, so its default folds pre-role records (and any malformed
 * value) back to [SessionRole.ADMIN].
 */
@JsonClass(generateAdapter = true)
internal class AuthStoredSession(
    val t: String,
    val e: Long,
    val r: String = SessionRole.ADMIN.wireName,
)

/**
 * File-backed [WebAuthGate.SessionPersistence]: the session map survives
 * server recreations and process death, so a port/TLS change or an app
 * restart no longer logs every dashboard out. Tokens are secrets — the store
 * lives in app-private filesDir and is written atomically (tmp file +
 * rename), so a crash mid-write never corrupts the map. Serialization goes
 * through the one App Json Moshi instance.
 */
class AuthSessionStore(context: Context) : WebAuthGate.SessionPersistence {

    private val file: File = File(File(context.filesDir, "auth"), "sessions.json")

    private val listAdapter by lazy {
        AppJson.moshi.adapter<List<AuthStoredSession>>(
            Types.newParameterizedType(List::class.java, AuthStoredSession::class.java),
        )
    }

    // Session mutations arrive from Ktor server threads with no shared lock,
    // and the atomic write uses a fixed `.tmp` name — concurrent saves could
    // interleave and corrupt the file. One monitor serializes them.
    @Synchronized
    override fun loadSessions(): Map<String, WebAuthGate.StoredSession> = file.readJsonOrDefault(
        listAdapter,
        emptyList(),
        warn = "Failed to read persisted sessions; starting clean",
    ).associate {
        it.t to WebAuthGate.StoredSession(
            expiresAtMs = it.e,
            // Pre-role records carry no `r` (Moshi fills the "admin" default);
            // an unparseable role folds to admin rather than dropping the session.
            role = SessionRole.fromWireName(it.r) ?: SessionRole.ADMIN,
        )
    }

    @Synchronized
    override fun saveSessions(sessions: Map<String, WebAuthGate.StoredSession>) {
        // Losing the persisted copy only means a re-login after restart.
        val wire = sessions.map { AuthStoredSession(it.key, it.value.expiresAtMs, it.value.role.wireName) }
        file.writeAtomicallyOrWarn(
            listAdapter.toJson(wire),
            warn = "Failed to persist sessions",
        )
    }
}
