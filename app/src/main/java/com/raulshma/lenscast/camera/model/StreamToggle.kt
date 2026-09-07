package com.raulshma.lenscast.camera.model

/**
 * The one stream start/stop seam. The gate → start → session begin →
 * rollback ladder lives here exactly once: an output is started only after
 * its settings gate passes, and if the session choreography
 * ([Transports.beginSession] — foreground service, wake lock, camera
 * keep-alive) throws after a successful start, the just-started output is
 * rolled back so no orphaned live stream survives a failed session.
 * Previously the web handler owned that ladder while the camera screen's
 * ViewModel fired the session begin fire-and-forget — a failed foreground
 * start there left the stream running with no session at all.
 *
 * Pure orchestration over the narrow [Transports] interface: no Android —
 * the camera ViewModel and the web handler each adapt their collaborators
 * (StreamingManager + StreamingSession) onto it, so the ladder is
 * JVM-testable with fakes.
 */
class StreamToggle(
    private val transports: Transports,
    /** Invoked after the gate passes, before the start call — the pre-start hook (e.g. the mic warn-and-degrade check). */
    private val onBeforeStart: (StreamKind) -> Unit = {},
) {

    /**
     * The transport operations the ladder needs, adapted from the
     * StreamingManager and StreamingSession by each caller.
     */
    interface Transports {
        val webEnabled: Boolean
        val rtspEnabled: Boolean
        val webActive: Boolean
        val rtspActive: Boolean

        fun startWeb(): Boolean
        fun stopWeb()
        fun startRtsp(): Boolean
        fun stopRtsp()
        /** Stops the whole server (web + RTSP outputs); the session end follows. */
        fun stopServer()

        /** Session attach; throws when the foreground-service choreography fails. */
        suspend fun beginSession()

        /** Session teardown; safe to call when no stream is live. */
        suspend fun endSession()
    }

    /** Toggles the web M-JPEG output: stop (with session end) when active, else the start ladder. */
    suspend fun toggleWeb(): StreamStartOutcome =
        if (transports.webActive) stopWeb() else startWeb()

    /** Toggles the RTSP output: stop (with session end) when active, else the start ladder. */
    suspend fun toggleRtsp(): StreamStartOutcome =
        if (transports.rtspActive) stopRtsp() else startRtsp()

    suspend fun startWeb(): StreamStartOutcome = start(StreamKind.WEB)

    suspend fun startRtsp(): StreamStartOutcome = start(StreamKind.RTSP)

    suspend fun stopWeb(): StreamStartOutcome {
        transports.stopWeb()
        transports.endSession()
        return StreamStartOutcome.Stopped
    }

    suspend fun stopRtsp(): StreamStartOutcome {
        transports.stopRtsp()
        transports.endSession()
        return StreamStartOutcome.Stopped
    }

    /** Stops the whole server and ends the session — the server toggle's stop path. */
    suspend fun stopServer(): StreamStartOutcome {
        transports.stopServer()
        transports.endSession()
        return StreamStartOutcome.Stopped
    }

    /** The gate → hook → start → begin → rollback ladder, shared by every consumer. */
    suspend fun start(kind: StreamKind): StreamStartOutcome {
        val enabled = when (kind) {
            StreamKind.WEB -> transports.webEnabled
            StreamKind.RTSP -> transports.rtspEnabled
        }
        if (!enabled) {
            return StreamStartOutcome.Disabled(kind)
        }
        onBeforeStart(kind)
        val started = when (kind) {
            StreamKind.WEB -> transports.startWeb()
            StreamKind.RTSP -> transports.startRtsp()
        }
        if (!started) {
            return StreamStartOutcome.StartFailed(kind)
        }
        return try {
            transports.beginSession()
            StreamStartOutcome.Started
        } catch (e: Exception) {
            // Roll back the just-started output — never leave a live stream
            // without its session.
            when (kind) {
                StreamKind.WEB -> transports.stopWeb()
                StreamKind.RTSP -> transports.stopRtsp()
            }
            StreamStartOutcome.BeginFailedRolledBack(kind, e)
        }
    }
}

/** Which live output a start/stop refers to, with its display words. */
enum class StreamKind(val displayName: String, val slug: String) {
    WEB("Web", "web"),
    RTSP("RTSP", "RTSP"),
}

/**
 * The typed result of a toggle/start/stop. Carries the structured kind —
 * each surface (camera-screen toasts, web API error payloads) renders its
 * own message from it, so the per-kind wording stays single-homed.
 */
sealed class StreamStartOutcome {
    /** Output started and session attached. */
    data object Started : StreamStartOutcome()

    /** Output (and session) stopped — the toggle's off path. */
    data object Stopped : StreamStartOutcome()

    /** The settings gate rejected the start; nothing was started. */
    data class Disabled(val kind: StreamKind) : StreamStartOutcome()

    /** The start call itself failed; nothing was started. */
    data class StartFailed(val kind: StreamKind) : StreamStartOutcome()

    /**
     * The stream started but the session begin threw; the stream was rolled
     * back. Surfaces the failure instead of the old silent fire-and-forget.
     */
    data class BeginFailedRolledBack(val kind: StreamKind, val cause: Exception) : StreamStartOutcome()

    companion object {
        /** The camera-screen toast for a gated output. */
        fun disabledMessage(kind: StreamKind): String =
            "${kind.displayName} streaming is disabled in settings."

        /** The camera-screen toast for a failed (or rolled-back) start. */
        fun startFailedMessage(kind: StreamKind): String =
            "Failed to start ${kind.slug} streaming."
    }
}
