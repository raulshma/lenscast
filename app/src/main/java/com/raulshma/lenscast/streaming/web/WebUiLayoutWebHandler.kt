package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.model.WebUiLayoutDto

private val webUiLayoutAdapter by lazy {
    AppJson.moshi.adapter(WebUiLayoutDto::class.java)
}

/**
 * GET/PUT/DELETE /api/ui/layout — the disk-backed inspector-layout document.
 *
 * Reads always go through [SettingsDataStore.webUiLayoutFromDisk], never the
 * shared StateFlow's default-first placeholder or its asynchronous collector
 * beat. PUT owns the server timestamp (through the injected clock); DELETE
 * restores the exact default.
 */
class WebUiLayoutWebHandler(
    private val settingsDataStore: SettingsDataStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** GET /api/ui/layout — one fresh, sanitized disk read. */
    suspend fun get(): String = response(settingsDataStore.webUiLayoutFromDisk())

    /**
     * PUT /api/ui/layout — decode or reject with [IllegalArgumentException],
     * stamp the server time, sanitize/save, then answer with the persisted truth.
     */
    suspend fun put(body: String): String {
        val sanitized = runCatching {
            val request = webUiLayoutAdapter.fromJson(body)
                ?: throw IllegalArgumentException("Invalid UI layout JSON")
            WebUiLayoutPolicy.sanitize(request.copy(updatedAtMs = nowMs()))
        }.getOrElse { cause ->
            // Moshi's generated collection adapters can admit null elements
            // into Kotlin non-null lists. Reject the whole hostile document
            // here rather than leaking a policy NPE or persisting a partial shape.
            throw IllegalArgumentException("Invalid UI layout JSON", cause)
        }

        settingsDataStore.saveWebUiLayout(sanitized)
        return get()
    }

    /** DELETE /api/ui/layout — persist and return the exact default document. */
    suspend fun reset(): String {
        settingsDataStore.saveWebUiLayout(WebUiLayoutPolicy.defaultLayout())
        return get()
    }

    companion object {
        /**
         * Serialization-only seam for the DTO contract fixture. Production
         * reads pass a value returned by [SettingsDataStore.webUiLayoutFromDisk];
         * this helper never substitutes an in-memory/default layout for disk.
         */
        internal fun response(layout: WebUiLayoutDto): String = webUiLayoutAdapter.toJson(layout)
    }
}
