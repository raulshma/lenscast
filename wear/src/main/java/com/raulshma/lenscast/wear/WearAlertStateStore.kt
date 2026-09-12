package com.raulshma.lenscast.wear

import android.content.Context
import android.content.SharedPreferences

/**
 * The persisted half of the alert loop's memory: the [WearAlertPolicy.SeenState]
 * (newest timestamp + the bounded seen-id ring) survives screen-off and app
 * restarts, so a poll that lands after a gap dedups against everything the
 * watch already surfaced — never re-alerting the same event twice.
 *
 * SharedPreferences rather than the settings DataStore on purpose: this is a
 * small machine-written marker (one long + ≤10 UUIDs), written from the poll
 * loop and read synchronously by the same loop — no flow, no coroutine, no
 * reason to ride the settings stream. Implements the controller's
 * [WearAlertStateStoreApi] seam.
 */
class WearAlertStateStore(context: Context) : WearAlertStateStoreApi {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wear_alert_state", Context.MODE_PRIVATE)

    /** The persisted seen-state, or null when no event has ever been seen. */
    override fun load(): WearAlertPolicy.SeenState? {
        if (!prefs.contains(KEY_LAST_TS)) return null
        val ids = prefs.getString(KEY_SEEN_IDS, "").orEmpty()
            .split(SEPARATOR)
            .filter(String::isNotBlank)
        return WearAlertPolicy.SeenState(
            lastTimestampMs = prefs.getLong(KEY_LAST_TS, 0L),
            seenIds = ids,
        )
    }

    /** Persists one seen-state; ids are UUID strings, so a plain separator is safe. */
    override fun save(state: WearAlertPolicy.SeenState) {
        prefs.edit()
            .putLong(KEY_LAST_TS, state.lastTimestampMs)
            .putString(KEY_SEEN_IDS, state.seenIds.joinToString(SEPARATOR))
            .apply()
    }

    private companion object {
        const val KEY_LAST_TS = "last_timestamp_ms"
        const val KEY_SEEN_IDS = "seen_ids"
        const val SEPARATOR = ","
    }
}
