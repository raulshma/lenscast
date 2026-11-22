package com.raulshma.lenscast.capture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The detection-event log's one seam — the exact surface DetectionEventsScreen
 * consumes (the event list, the clear verdict, clear, refresh), as a plain
 * interface. One interface, two backends (the
 * [com.raulshma.lenscast.camera.CameraConsole] pattern): the event screen
 * renders against either
 *
 * - the **local** backend — [LocalEventsSource], this phone's file-backed
 *   [DetectionEventStore]; or
 * - the **remote** backend — [com.raulshma.lenscast.companion.console.RemoteEventsSource],
 *   the paired device's SSE/poll feed ([com.raulshma.lenscast.companion.session.DeviceSession.detectionEvents])
 *   plus its REST clear route.
 *
 * ORDER CONTRACT: [events] is NEWEST-FIRST for both backends. The local
 * store appends newest-first and reads through
 * [com.raulshma.lenscast.capture.DetectionEventLogPolicy.readNewestFirst];
 * the remote session's buffer merges deduped pages newest-first. The remote
 * mapper additionally re-sorts defensively at the seam (see
 * [com.raulshma.lenscast.companion.console.RemoteEventsSource]) so the two
 * backends' lists can never disagree by arrival order.
 *
 * Mutation gate: [canClear] — clearing the log is a device-state write (the
 * local `store.clear()`; remote `DELETE /api/detection/events`), so a
 * viewer-role pairing hides the Clear affordance instead of handing the
 * device a write it would 403 ([com.raulshma.lenscast.streaming.web.RoleGatePolicy]'s
 * viewer-write DENY, applied at the seam). [clearNeedsConfirm] carries the
 * blast-radius difference: the local log is app-private and its Clear has
 * always been one undeeded tap, while the remote wipe empties the DEVICE's
 * persisted log permanently — so the remote backend requires the confirm
 * dialog (the companion events tab's copy, single-homed there) and the
 * local stays tap-through. Data-driven divergence owned by the source; the
 * screen carries no backend branch.
 *
 * The screen's type-filter chips stay client-side on [events] (`type` wire
 * names survive the remote mapping verbatim), and snapshot thumbnails ride
 * [DetectionEvent.snapshotJpegBase64], which both backends fill — the local
 * store's bytes and the remote SSE frame's base64 decode through the same
 * path.
 */
interface EventsSource {

    /** The event log, NEWEST-FIRST (see the order contract above). */
    val events: StateFlow<List<DetectionEvent>>

    /** Whether Clear renders at all (the role's deletion verdict). */
    val canClear: Boolean

    /** Whether Clear needs its confirm dialog before executing (remote wipe: yes; local log: no). */
    val clearNeedsConfirm: Boolean

    /**
     * A manual pull of the device's truth. Local: re-reads the store (the
     * live flow tail already keeps the list fresh). Remote: kicks the
     * session's on-demand poll seam ([com.raulshma.lenscast.companion.session.DeviceSession.manualDetectionPoll]),
     * merging a fresh `/api/detection/events` page through the buffer's
     * dedupe.
     */
    fun refresh()

    /** Clears the log. Caller-gated by [canClear]; remote clears only after the device confirms. */
    fun clear()
}

/**
 * The detection-event log's LOCAL backend: [EventsSource] over this phone's
 * [DetectionEventStore] — the screen's old inline `store.events()` +
 * `eventsFlow` tail, lifted verbatim into a source. Clear persists the
 * emptied log off-main (disk I/O, as the screen always did) and re-reads
 * into the list; the confirm dialog is deliberately absent
 * ([clearNeedsConfirm] = false) to keep today's one-tap behavior unchanged.
 */
class LocalEventsSource(
    private val store: DetectionEventStore,
    /** The scope the live store tail and the clear's off-main I/O run on. */
    private val scope: CoroutineScope,
) : EventsSource {

    private val _events = MutableStateFlow(store.events())
    override val events: StateFlow<List<DetectionEvent>> = _events.asStateFlow()

    /**
     * Pairs every store read with its publish, and wraps the clear's delete +
     * re-read in the same critical section. The collector, [refresh], and
     * [clear] run on different threads, and the flow's last-write-wins would
     * otherwise let a snapshot read before a clear publish after the emptied
     * one — resurrecting the wiped list.
     */
    private val publishLock = Any()

    override val canClear: Boolean = true
    override val clearNeedsConfirm: Boolean = false

    init {
        // The live tail: every record or clip-link update re-reads the store
        // (the flow carries the event, but the list is the render truth).
        // clear() emits nothing on the flow, so it re-reads itself too.
        scope.launch {
            store.eventsFlow.collect {
                synchronized(publishLock) {
                    _events.value = store.events()
                }
            }
        }
    }

    override fun refresh() {
        synchronized(publishLock) {
            _events.value = store.events()
        }
    }

    override fun clear() {
        scope.launch(Dispatchers.IO) {
            synchronized(publishLock) {
                store.clear()
                _events.value = store.events()
            }
        }
    }
}
