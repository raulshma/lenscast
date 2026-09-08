package com.raulshma.lenscast.streaming

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-backed [WebAuthGate.SessionPersistence]: the session map survives
 * server recreations and process death, so a port/TLS change or an app
 * restart no longer logs every dashboard out. Tokens are secrets — the store
 * lives in app-private filesDir and is written atomically (tmp file +
 * rename), so a crash mid-write never corrupts the map.
 */
class AuthSessionStore(context: Context) : WebAuthGate.SessionPersistence {

    private val file: File = File(File(context.filesDir, "auth"), "sessions.json")

    override fun loadSessions(): Map<String, Long> = try {
        if (!file.exists()) {
            emptyMap()
        } else {
            val array = JSONArray(file.readText())
            buildMap {
                for (i in 0 until array.length()) {
                    val entry = array.getJSONObject(i)
                    put(entry.getString(KEY_TOKEN), entry.getLong(KEY_EXPIRY))
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read persisted sessions; starting clean", e)
        emptyMap()
    }

    override fun saveSessions(sessions: Map<String, Long>) {
        try {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            sessions.forEach { (token, expiry) ->
                array.put(JSONObject().put(KEY_TOKEN, token).put(KEY_EXPIRY, expiry))
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // Losing the persisted copy only means a re-login after restart.
            Log.w(TAG, "Failed to persist sessions: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AuthSessionStore"
        private const val KEY_TOKEN = "t"
        private const val KEY_EXPIRY = "e"
    }
}
