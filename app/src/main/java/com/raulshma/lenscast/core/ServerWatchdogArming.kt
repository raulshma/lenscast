package com.raulshma.lenscast.core

import com.raulshma.lenscast.streaming.ServerForegroundController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Maps the web server's runtime state onto the watchdog's server-liveness
 * arming (blueprint P0 item 2, resolved tension 3): the server lifecycle —
 * not the streaming session — is what the process-wide baseline keys on,
 * because the server-only state is precisely what every recovery tier's
 * relaunch lands in. The flow is the same one
 * [ServerForegroundController] bridges ([StreamingManager.isServerRunning]),
 * so every server start/stop path (StreamToggle, boot resume, watchdog
 * recovery, port/TLS restarts) arms and disarms through one observation.
 *
 * The stop leg is grace-windowed with the fire-time re-check, and the
 * mechanics live in the one shared [GraceWindowedArming] bridge (the
 * HeartbeatController leg shares it — the grace-window race handling is
 * written once, not once per consumer). This class keeps only the mapping:
 * server up → [StreamWatchdog.startServerLivenessWatch], a stop that
 * survives the grace and the re-check → [StreamWatchdog.stopServerLivenessWatch].
 *
 * Deliberately separate from [StreamWatchdog] itself: the watchdog owns the
 * loop; this owns only the lifecycle bridge (the same split
 * ServerForegroundController draws against the session).
 */
class ServerWatchdogArming(
    private val watchdog: StreamWatchdog,
    isServerRunning: StateFlow<Boolean>,
    /** The down-leg grace; defaults to the same window ServerForegroundController measured restarts inside. */
    stopGraceMs: Long = ServerForegroundController.SERVER_STOP_GRACE_MS,
) {

    // The shared grace-windowed bridge; the arming semantics are the two
    // lambdas below, everything else (the grace, the pending-stop cancel,
    // the fire-time re-check) is the bridge's one implementation.
    private val bridge = GraceWindowedArming(
        isServerRunning = isServerRunning,
        stopGraceMs = stopGraceMs,
        arm = watchdog::startServerLivenessWatch,
        disarm = watchdog::stopServerLivenessWatch,
    )

    fun start(scope: CoroutineScope) {
        bridge.start(scope)
    }
}
