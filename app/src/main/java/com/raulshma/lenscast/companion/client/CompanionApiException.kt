package com.raulshma.lenscast.companion.client

/**
 * The companion client's failure taxonomy — everything [LenscastApiClient]
 * throws beyond a successful decode is one of these, so device screens can
 * `when` on the type instead of sniffing message text (the same rule the web
 * dashboard follows: the status rides the error, never the string).
 *
 * Extends [java.io.IOException] so callers that only care "did it work" can
 * catch one familiar family, and so coroutine machinery treats it as a plain
 * failure rather than a cancellation.
 */
sealed class CompanionApiException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause) {

    /**
     * The device answered a protected route with HTTP 401 — the held
     * credential (token, basic pair, or the captured session cookie) is
     * missing, stale, or was revoked remotely (a credential rotation revokes
     * every session server-side). Surfaced for ANY route, not just auth ones.
     * The UI should show the re-pair / sign-in sheet; retrying without fresh
     * credentials is pointless because the server fails closed.
     */
    class Auth : CompanionApiException("Authentication required")

    /**
     * HTTP 403: the request authenticated but the device refused it — the
     * paired session is viewer-role and the route is admin-only (the remote's
     * RoleGatePolicy), or the CSRF gate tripped. [role] is the roster's
     * snapshot of the paired role (`"admin"` | `"viewer"`), when known, so the
     * UI can degrade to the read-only banner without a second probe.
     */
    class Forbidden(val role: String?) : CompanionApiException("Forbidden")

    /**
     * Any other non-2xx answer (404 unknown route on an older device, 5xx
     * handler failure). [body] carries the raw error envelope text when the
     * device sent one (LensCast's standard `{"error":"…"}` payload) — the
     * handler's own message, forwarded verbatim like the web client does.
     */
    class Http(val code: Int, val body: String?) : CompanionApiException("HTTP $code")

    /**
     * The pinned-fingerprint verification failed: the cert the device
     * presented does not hash to [expected] (the pinned digest from the QR's
     * `#fp=` fragment). Almost always an imposter on the network — the client
     * NEVER falls through to a normal TLS error, so the UI should present a
     * hard "device identity mismatch" dialog, not a retry spinner. [expected]
     * is the normalized (colon-stripped, lowercase) pinned digest.
     */
    class PinMismatch(val expected: String) : CompanionApiException(
        "Certificate fingerprint mismatch (expected $expected)",
    )

    /**
     * The device is unreachable — TCP connect refused/timeout, DNS failure,
     * read timeout inside the LAN-radio budgets (3s connect / 8s read / 12s
     * whole-call). [cause] is the original transport error. The UI should
     * surface a connectivity state, not an auth prompt.
     */
    class Network(cause: java.io.IOException) : CompanionApiException(
        cause.message ?: "Network unreachable",
        cause,
    )
}
