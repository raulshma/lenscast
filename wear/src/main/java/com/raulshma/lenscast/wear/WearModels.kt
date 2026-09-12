package com.raulshma.lenscast.wear

/**
 * Plain value types and the request-state ladder for the wear remote.
 *
 * Every network-driven surface in the UI (status poll, snapshot pane,
 * stream toggle, photo capture) is a [RequestState] — the four-state sealed
 * ladder the whole module funnels through, so a spinner, a stale frame, or
 * an error message is always an explicit state, never a guess from nulls.
 */

/**
 * The watch-side connection settings. Persisted by [WearSettingsStore] in
 * the watch's own DataStore — nothing is shared with the phone app, and a
 * missing/blank host means "unconfigured", which lands the user in the
 * settings screen on first run.
 *
 * [authMode] picks how every request presents itself to the phone's Web
 * Auth Gate (see [AuthMode] for the server-side reality of each mode).
 */
data class WearSettings(
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val authMode: AuthMode = AuthMode.API_TOKEN,
    val username: String = "",
    val password: String = "",
    val apiToken: String = "",
    /**
     * The detection-alerts opt-in (default ON, toggled in the settings
     * screen): when true, the resumed alert loop surfaces new phone-side
     * detection events as a banner + notification + haptic. The poll loop
     * re-reads settings each lap, so a flip takes effect on the next poll.
     */
    val alertsEnabled: Boolean = true,
) {
    /** True when a host is present — the single "configured" gate. */
    val isConfigured: Boolean get() = host.isNotBlank()

    companion object {
        /** LensCast's StreamingServer default HTTP port. */
        const val DEFAULT_PORT = 8080
    }
}

/**
 * How the remote authenticates against the phone's web gate.
 *
 * [API_TOKEN] is the fully-supported programmatic path against the current
 * LensCast server: the `X-Api-Token` header authorizes GETs on every
 * protected route and POSTs on the server's TokenWritePolicy allow-list —
 * which includes all three write routes this remote uses
 * (`/api/stream/start`, `/api/stream/stop`, `/api/capture`).
 *
 * [BASIC] sends RFC 7617 `Authorization: Basic` credentials. The current
 * server build does not validate Basic on protected routes (its ladder is
 * API token or session cookie); the mode exists for deployments that front
 * LensCast with a Basic-auth proxy, and for forward compatibility. Documented
 * as-is in wear/README.md — no client-side pretending otherwise.
 */
enum class AuthMode { BASIC, API_TOKEN }

/**
 * One decoded phone status poll: exactly the fields the remote and the tile
 * render. [streamingActive] is the whole-server mirror behind the toggle;
 * [webStreamingActive] / [rtspStreamingActive] are the per-transport live
 * flags the tile's stream-state line reads (both default false — the wire
 * fields are optional on older phone builds).
 */
data class WearStatus(
    val streamingActive: Boolean,
    val clientCount: Int,
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    val cameraState: String,
    val thermalState: String,
    val webStreamingActive: Boolean = false,
    val rtspStreamingActive: Boolean = false,
)

/** The device identity the settings test button surfaces on success. */
data class WearSystemInfo(
    val deviceModel: String,
    val appVersion: String,
)

/**
 * One detection event off `GET /api/detection/events` — the newest-first
 * feed the alert loop tails. Only the fields the watch surfaces are parsed:
 * identity ([id], a phone-side UUID string, NOT an ordered counter — the
 * new-event verdict lives in [WearAlertPolicy]), the event clock, and the
 * label material ([labels] = ML/YAMNet classes, [zones] = motion zones).
 * The base64 snapshot deliberately stays on the phone — the banner's
 * thumbnail is the live `/snapshot` frame the pane already fetches.
 */
data class WearDetectionEvent(
    val id: String,
    val type: String,
    val timestampMs: Long,
    val zones: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
)

/** One decoded `/snapshot` frame plus the wall-clock instant it arrived. */
data class SnapshotFrame(
    val bitmap: android.graphics.Bitmap,
    val fetchedAtMs: Long,
)

/** The outcome of a stream/photo POST: the server's success flag + error text. */
data class CommandResult(
    val success: Boolean,
    val error: String? = null,
)

/**
 * The four-state ladder every async surface renders. [Idle] is the
 * pre-first-fetch state (nothing shown); [Loading] replaces content only on
 * user-initiated actions, never on background refreshes; [Error] carries a
 * short, user-presentable message.
 */
sealed class RequestState<out T> {
    data object Idle : RequestState<Nothing>()
    data object Loading : RequestState<Nothing>()
    data class Success<T>(val value: T) : RequestState<T>()
    data class Error(val message: String) : RequestState<Nothing>()
}
