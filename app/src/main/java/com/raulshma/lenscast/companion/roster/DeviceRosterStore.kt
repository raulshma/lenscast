package com.raulshma.lenscast.companion.roster

import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import com.squareup.moshi.Types
import java.io.File

/**
 * The companion mode's device roster: the persisted list of paired LensCast
 * devices the app can view and control. The whole list serializes as one
 * Moshi JSON document at `filesDir/companion/roster.json`, written atomically
 * (tmp file + rename) — the [com.raulshma.lenscast.streaming.AuthSessionStore]
 * file-backed-store pattern: losing the persisted copy only costs the user a
 * re-pair, so a failed save warns and moves on rather than surfacing.
 *
 * Deliberately credential-free: a [CompanionDevice] carries the auth KIND it
 * was paired with, never the secret itself — passwords/tokens live in
 * [CompanionCredentialStore], encrypted. The store therefore cannot leak
 * keys by copying the roster file around (backups, bug reports).
 *
 * Load-once + cache, single-writer: every public member synchronizes on the
 * store, the first read hydrates the in-memory list from disk, and mutations
 * mutate the cached list before mirroring it atomically — so callers that
 * hold the store across screens see their own upserts without a re-read,
 * and interleaved callers queue behind the one monitor instead of racing
 * the fixed `.tmp` name (the AuthSessionStore discipline).
 */
class DeviceRosterStore(
    filesDir: File,
    appJson: AppJson,
) {

    private val file = File(File(filesDir, "companion"), "roster.json")

    private val listAdapter by lazy {
        appJson.moshi.adapter<List<CompanionDevice>>(
            Types.newParameterizedType(List::class.java, CompanionDevice::class.java),
        )
    }

    @Volatile
    private var cache: List<CompanionDevice>? = null

    /** The full roster, roster order (most recently upserted last). */
    @Synchronized
    fun all(): List<CompanionDevice> =
        cache ?: file.readJsonOrDefault(
            listAdapter,
            emptyList(),
            warn = "Failed to read companion roster; starting clean",
        ).also { cache = it }

    /** One roster entry by id, or null when the id is unknown. */
    @Synchronized
    fun find(id: String): CompanionDevice? = all().firstOrNull { it.id == id }

    /** Inserts or replaces one device (matched on [CompanionDevice.id]) and persists. */
    @Synchronized
    fun upsert(device: CompanionDevice) {
        val next = all().filterNot { it.id == device.id } + device
        persist(next)
    }

    /** Drops one device (no-op when the id is unknown) and persists. */
    @Synchronized
    fun remove(id: String) {
        val current = all()
        if (current.none { it.id == id }) return
        persist(current.filterNot { it.id == id })
    }

    /** The one mutation exit: cache first, then the atomic mirror to disk. */
    private fun persist(next: List<CompanionDevice>) {
        cache = next
        // Losing the persisted copy only means a re-pair after restart.
        file.writeAtomicallyOrWarn(
            listAdapter.toJson(next),
            warn = "Failed to persist companion roster",
        )
    }
}
