package com.raulshma.lenscast.streaming

import android.content.Context
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import com.squareup.moshi.Types
import java.io.File

/**
 * One persisted session: the token and its expiry epoch-ms. The `t`/`e` field
 * names are the legacy org.json wire names, kept decode-compatible through the
 * App Json migration.
 */
internal class AuthStoredSession(val t: String, val e: Long)

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
    override fun loadSessions(): Map<String, Long> = file.readJsonOrDefault(
        listAdapter,
        emptyList(),
        warn = "Failed to read persisted sessions; starting clean",
    ).associate { it.t to it.e }

    @Synchronized
    override fun saveSessions(sessions: Map<String, Long>) {
        // Losing the persisted copy only means a re-login after restart.
        val wire = sessions.map { AuthStoredSession(it.key, it.value) }
        file.writeAtomicallyOrWarn(
            listAdapter.toJson(wire),
            warn = "Failed to persist sessions",
        )
    }
}
