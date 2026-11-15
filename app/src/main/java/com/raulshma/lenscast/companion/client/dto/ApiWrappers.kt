package com.raulshma.lenscast.companion.client.dto

import com.squareup.moshi.JsonClass

/**
 * Request/response wrappers the companion client needs beyond [CoreDtos]:
 * the small command envelopes (`{success, error?, url?}` and friends) and the
 * JSON bodies the write routes take. Shapes transcribe the server truth
 * (streaming/web handlers + web/src/api/client.ts) exactly like CoreDtos —
 * this file exists separately only so CoreDtos.kt stays the Wave 0 frozen
 * set; Wave 1 additions land here.
 *
 * Moshi's default (serializeNulls OFF) is load-bearing for every nullable
 * request field: a `null` field is OMITTED from the wire, and the server
 * handlers decode omitted fields to their defaults — so `password = null`
 * means "keep the stored hash" (the write-only-secret contract), and a
 * nullable `totalCaptures` omitted means "use the server default".
 */

/**
 * The generic command answer: a superset of the server's
 * `SuccessResponse` (`{success}`), `StreamActionResponse`
 * (`{success, isActive, url?, error?}`) and `CaptureResponse`
 * (`{success, fileName?, error?}`) — LensCast answers every write with HTTP
 * 200 and the outcome in the payload, so one shape decodes all of them
 * (absent fields fold to null).
 */
@JsonClass(generateAdapter = true)
data class CommandResponse(
    val success: Boolean = true,
    val error: String? = null,
    /** The stream URL on POST /api/stream/web|rtsp/start; null otherwise. */
    val url: String? = null,
    /** The output's running state after the action, on stream toggles. */
    val isActive: Boolean? = null,
    /** The captured file name on POST /api/capture. */
    val fileName: String? = null,
)

/** POST /api/detection/test — which alert sinks dispatched the synthetic alert. */
@JsonClass(generateAdapter = true)
data class DetectionTestResponse(
    val success: Boolean = false,
    val dispatchedActions: List<String> = emptyList(),
)

/** POST /api/media/batch-delete — the ids the server actually removed. */
@JsonClass(generateAdapter = true)
data class BatchDeleteResponse(
    val success: Boolean = true,
    val deleted: List<String> = emptyList(),
)

/** GET /api/capture/interval/status — the WorkManager interval-capture state. */
@JsonClass(generateAdapter = true)
data class IntervalCaptureStatus(
    val isRunning: Boolean = false,
    val completedCaptures: Int = 0,
)

/**
 * GET /api/auth/config — the remote's auth config read. `username`/`password`
 * are the admin pair as the server redacted them for this session (viewer
 * sessions get an empty username; passwords are always blanked — the
 * write-only-secret contract). `viewerConfigured` is the honest "is a viewer
 * pair set" bit (viewerEnabled alone can be false because the pair was
 * cleared).
 */
@JsonClass(generateAdapter = true)
data class AuthConfigInfo(
    val enabled: Boolean = false,
    val username: String? = null,
    val password: String? = null,
    val viewerEnabled: Boolean? = null,
    val viewerUsername: String? = null,
    val viewerPassword: String? = null,
    val viewerConfigured: Boolean = false,
)

/** One live web session on GET /api/auth/sessions; [tokenPrefix] ids it for revocation. */
@JsonClass(generateAdapter = true)
data class AuthSessionEntry(
    val tokenPrefix: String,
    val expiresAtMs: Long,
    /** `"admin"` | `"viewer"`; absent in pre-role responses — treat as admin. */
    val role: String? = null,
)

/** GET /api/auth/sessions — the session roster plus its count. */
@JsonClass(generateAdapter = true)
data class AuthSessionsResponse(
    val sessions: List<AuthSessionEntry> = emptyList(),
    val count: Int = 0,
)

// ── Request bodies ──
// Every nullable field relies on Moshi's serializeNulls-off default: null is
// OMITTED from the wire so the server's per-field defaults apply.

/** POST /api/auth/login — the flat string pair the server's login reader expects. */
@JsonClass(generateAdapter = true)
data class LoginRequest(val username: String, val password: String)

/** POST /api/camera/torch. */
@JsonClass(generateAdapter = true)
data class TorchRequest(val enabled: Boolean)

/** POST /api/camera/zoom. */
@JsonClass(generateAdapter = true)
data class ZoomRequest(val zoomRatio: Double)

/** POST /api/camera/focus — normalized tap coordinates (0..1 frame space). */
@JsonClass(generateAdapter = true)
data class FocusRequest(val x: Double, val y: Double)

/** POST|PUT /api/camera/lens — the enumerated lens index to bind. */
@JsonClass(generateAdapter = true)
data class LensSelectRequest(val index: Int)

/** POST /api/deterrence/siren — the wire names the field `siren`, not `on`. */
@JsonClass(generateAdapter = true)
data class SirenRequest(val siren: Boolean)

/** POST /api/media/batch-delete. */
@JsonClass(generateAdapter = true)
data class BatchDeleteRequest(val ids: List<String>)

/**
 * POST /api/recording/start. Every field nullable: omitted fields take the
 * server's RecordingConfig defaults (duration 0 = unbounded, HIGH quality,
 * audio on) — send only what the caller chose.
 */
@JsonClass(generateAdapter = true)
data class RecordingStartRequest(
    val durationSeconds: Long? = null,
    val repeatIntervalSeconds: Long? = null,
    /** `HIGH` | `MEDIUM` | `LOW`. */
    val quality: String? = null,
    val includeAudio: Boolean? = null,
    /** Schedule the recording's start (epoch ms) instead of starting now. */
    val startTimeMs: Long? = null,
)

/**
 * POST /api/capture/interval/start. `totalCaptures`/`flashMode` nullable —
 * omitted they take the server defaults (`100`, `OFF`); [intervalSeconds] is
 * the one field the caller always means.
 */
@JsonClass(generateAdapter = true)
data class IntervalCaptureStartRequest(
    val intervalSeconds: Int,
    val totalCaptures: Int? = null,
    /** `ON` | `OFF` | `AUTO`. */
    val flashMode: String? = null,
)

/**
 * PUT /api/auth/config — the credential rotation body. Passwords are
 * write-only: a null password is omitted from the wire, which the server
 * decodes as empty = "keep the stored hash" (never send a fetched password
 * back — GET blanks it on the wire anyway).
 *
 * CAREFUL with the viewer block: the server decodes an absent
 * `viewerEnabled` as `false`, i.e. "disable the viewer pair" — a caller
 * rotating ONLY the admin credentials must pass the device's current
 * viewerEnabled/viewerUsername through, or it silently deletes the viewer
 * account server-side.
 */
@JsonClass(generateAdapter = true)
data class AuthConfigPutRequest(
    val enabled: Boolean,
    val username: String,
    val password: String? = null,
    val viewerEnabled: Boolean? = null,
    val viewerUsername: String? = null,
    val viewerPassword: String? = null,
)
