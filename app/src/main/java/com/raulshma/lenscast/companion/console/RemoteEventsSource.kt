package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.capture.DetectionEvent
import com.raulshma.lenscast.capture.EventsSource
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.RemoteDetectionEvent
import com.raulshma.lenscast.companion.session.DeviceSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The detection-event log's REMOTE backend: [EventsSource] over the paired
 * device's live link — the session's deduped, capped feed
 * ([DeviceSession.detectionEvents]) as the display source (the events tab's
 * rule: never fork a second list), the session's on-demand poll seam for
 * manual pulls, and `DELETE /api/detection/events` + the buffer-clear seam
 * for the wipe. Built by [com.raulshma.lenscast.companion.di.CompanionGraph.eventsSource],
 * cached per device like the gallery source.
 *
 * Lifecycle/visibility contract: the source never starts or stops the
 * session — the console screen owns that lifecycle (setActive on enter,
 * freeze+stop on dispose). It is self-sufficient for a DEEP-LINKED visit
 * with no live link: construction fires one [DeviceSession.manualDetectionPoll]
 * (a plain authenticated REST page-merge that works on an IDLE session —
 * it needs the roster + credentials, not the lanes), so the screen opens on
 * the device's newest page; with a live console beside it, the SSE lane
 * keeps the same buffer live and the poll is a no-op refresh. The feed
 * survives a console leave (session memory), and a later visit sees the
 * latest buffer plus its own construction poll.
 *
 * Ordering: the session buffer is NEWEST-FIRST by contract; [events]
 * re-sorts defensively after the mapping so the list can never disagree
 * with the local backend's order contract by arrival quirks (a backlog
 * replay interleaving with live frames). One sort at the seam, documented,
 * cheap at the 200-event cap.
 *
 * Clear: [DeviceSession.clearDetectionFeed] runs only after the device
 * confirmed `DELETE /api/detection/events` (the tab's clear-after-server-
 * delete shape — the UI never lies about a wipe that failed), and the call
 * is single-flight. A follow-up [DeviceSession.reconcileDetectionFeedAfterClear]
 * replaces the buffer with the device's post-wipe truth, so pre-delete SSE
 * frames still in flight across the wipe cannot resurrect cleared events.
 * A viewer-role pairing ([canClear] = false) hides Clear
 * and the source no-ops it, mirroring the device's own
 * [com.raulshma.lenscast.streaming.web.RoleGatePolicy] verdict.
 */
class RemoteEventsSource(
    private val session: DeviceSession,
    /**
     * The device's JSON client (the roster snapshot + live credentials, the
     * tab factories' idiom); null when the roster lost the device — the feed
     * still renders whatever the session holds, and clear becomes a no-op.
     */
    private val api: LenscastApiClient?,
    /** The roster role's clear verdict (admin pairings only). */
    override val canClear: Boolean,
    /** The scope the mapped feed, the seed poll, and the clear run on. */
    private val scope: CoroutineScope,
) : EventsSource {

    // The remote wipe empties the DEVICE's persisted log permanently — the
    // confirm dialog (the events tab's single-homed copy) guards it.
    override val clearNeedsConfirm: Boolean = true

    override val events: StateFlow<List<DetectionEvent>> =
        session.detectionEvents
            .map { feed -> feed.map { it.toDetectionEvent() }.newestFirst() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val clearInFlight = AtomicBoolean(false)

    init {
        // Deep-link seed: one page merge so an events visit without a live
        // session still opens on the device's newest events. With the SSE
        // lane already flowing this merely re-reads the log through the
        // buffer's dedupe.
        scope.launch { session.manualDetectionPoll() }
    }

    override fun refresh() {
        scope.launch { session.manualDetectionPoll() }
    }

    override fun clear() {
        val client = api ?: return
        if (!canClear) return
        // Single-flight: a second tap while the round trip runs is dropped,
        // not queued behind a wipe that already reflects the first.
        if (!clearInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val answer = client.clearDetectionEvents()
                if (answer.success) {
                    // The device log is emptied — mirror it in the session's
                    // feed right away instead of waiting for the lane's next
                    // poll refresh to reconcile (the wave-4 buffer-clear seam).
                    session.clearDetectionFeed()
                    // Then close the clear boundary: one post-clear GET
                    // replaces the buffer with the device's emptied truth,
                    // scrubbing any pre-delete frame the SSE lane was still
                    // delivering across the wipe (see
                    // DeviceSession.reconcileDetectionFeedAfterClear).
                    session.reconcileDetectionFeedAfterClear()
                }
            } catch (_: Exception) {
                // A failed wipe leaves the feed as-is (the device's truth);
                // the local screen's surface has no error snackbar to fill.
            } finally {
                clearInFlight.set(false)
            }
        }
    }
}

/**
 * The pure wire→domain projection: one device event onto the capture
 * package's log model, so the screen consumes [DetectionEvent] against both
 * backends. JVM-pure (both ends are Moshi data classes). The dashboard
 * deep-link `url` field is dropped — it points at the device's web
 * dashboard, which this screen replaces.
 */
internal fun RemoteDetectionEvent.toDetectionEvent(): DetectionEvent = DetectionEvent(
    id = id,
    type = type,
    source = source,
    timestampMs = timestampMs,
    snapshotJpegBase64 = snapshotJpegBase64,
    dispatchedActions = dispatchedActions,
    zones = zones,
    labels = labels,
    clipMediaId = clipMediaId,
    clipFileName = clipFileName,
)

/**
 * The [EventsSource] order contract's remote half: NEWEST-FIRST, enforced
 * at the seam. Pure and JVM-tested.
 */
internal fun List<DetectionEvent>.newestFirst(): List<DetectionEvent> =
    sortedByDescending { it.timestampMs }
