package com.raulshma.lenscast.streaming.whep

import java.util.LinkedHashMap

/**
 * The WHEP session registry: the pure bookkeeping behind the viewer cap and
 * the dead-session GC, JVM-tested without libwebrtc.
 *
 * Every admitted session is an [Entry] stamped with its creation time and a
 * [SessionPhase] the lifecycle marks move through the machine:
 *
 *   admit → Awaiting ──markConnected──→ Connected ──markDisconnected──→ Disconnected
 *                ↑                                   └──markConnected──→ Connected (ICE recovered;
 *                └── (a re-mark re-enters the same phase)                the mark restarts the grace clock)
 *
 * [markDisconnected] before any connect is ignored (it stays [SessionPhase.Awaiting]);
 * every [markConnected] re-enters [SessionPhase.Connected] with a fresh timestamp,
 * which is also the reconnected reading (a flapping viewer's grace clock
 * restarts at each re-mark). [reapIds] names the sessions worth tearing down,
 * by phase:
 *
 *  - [SessionPhase.Awaiting] past [connectReapMs] from admission — an offer
 *    that never completed ICE/DTLS (the viewer vanished, a firewall ate the
 *    candidates) must not hold a hardware encoder slot;
 *  - [SessionPhase.Disconnected] past [disconnectedReapMs] from the mark — a
 *    viewer that closed its tab without DELETEing the session resource (ICE
 *    notices within its own timeout; the registry gives the transient
 *    DISCONNECTED state a grace window before reaping);
 *  - [SessionPhase.Connected] never (a connected session without a
 *    disconnect mark is live by definition).
 *
 * Thread-safe: the endpoint's NanoHTTPD worker admits sessions while the
 * reap loop and libwebrtc's callback threads mark them.
 */
class WhepSessionRegistry(
    private val connectReapMs: Long = CONNECT_REAP_MS,
    private val disconnectedReapMs: Long = DISCONNECTED_REAP_MS,
) {

    /**
     * One session's lifecycle phase, carrying the timestamp that phase's
     * verdict reads — the explicit machine behind what used to be the
     * two-nullable-timestamp encoding (`connectedAtMs`/`disconnectedAtMs`),
     * where {awaiting, connected, disconnected, reconnected} was inferred
     * from which of the two was null. Immutable: transitions swap the whole
     * value under the registry's lock.
     */
    sealed class SessionPhase {
        /** Admitted but never connected; [sinceMs] is the admission time (the connect-reap clock's origin). */
        data class Awaiting(val sinceMs: Long) : SessionPhase()

        /** ICE/DTLS reached the viewer (first connect or a re-connect); [sinceMs] is that mark. */
        data class Connected(val sinceMs: Long) : SessionPhase()

        /** ICE dropped after a connect; [sinceMs] is the most recent disconnect mark (the grace clock's origin). */
        data class Disconnected(val sinceMs: Long) : SessionPhase()
    }

    class Entry internal constructor(
        val id: String,
        val createdAtMs: Long,
    ) {
        /** The lifecycle phase; mutated only by the registry's synchronized transitions. */
        @Volatile var phase: SessionPhase = SessionPhase.Awaiting(createdAtMs)
            internal set
    }

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun size(): Int = entries.size

    /** True while the registry is under [cap] live sessions. */
    @Synchronized
    fun canAdmit(cap: Int): Boolean = entries.size < cap

    /** Admits [id]; false when it already exists (a duplicate id is never re-admitted). */
    @Synchronized
    fun admit(id: String, nowMs: Long): Boolean {
        if (entries.containsKey(id)) return false
        entries[id] = Entry(id, nowMs)
        return true
    }

    @Synchronized
    fun get(id: String): Entry? = entries[id]

    @Synchronized
    fun remove(id: String): Boolean = entries.remove(id) != null

    /**
     * Marks the session connected — from any phase (the reconnect reading:
     * ICE recovered, the grace clock is forgotten, a fresh connect mark
     * starts). Before any connect the mark is a plain first connect.
     */
    @Synchronized
    fun markConnected(id: String, nowMs: Long) {
        entries[id]?.let { it.phase = SessionPhase.Connected(nowMs) }
    }

    /**
     * Marks the session disconnected. Only meaningful after a connect: from
     * [SessionPhase.Awaiting] the mark is ignored (the connect verdict still
     * applies); a repeated mark restarts the grace clock at the newest mark.
     */
    @Synchronized
    fun markDisconnected(id: String, nowMs: Long) {
        entries[id]?.let {
            if (it.phase is SessionPhase.Connected || it.phase is SessionPhase.Disconnected) {
                it.phase = SessionPhase.Disconnected(nowMs)
            }
        }
    }

    /**
     * The ids worth tearing down now, removed from the registry as a side
     * effect — the caller closes each named session's native pieces. The
     * per-phase reap rules are the class doc's: awaiting past the connect
     * window (measured from admission, [Entry.createdAtMs]), disconnected
     * past the grace window (measured from the newest mark), connected never.
     */
    @Synchronized
    fun reapIds(nowMs: Long): List<String> {
        val doomed = entries.values.filter { entry ->
            when (val phase = entry.phase) {
                is SessionPhase.Awaiting -> nowMs - entry.createdAtMs > connectReapMs
                is SessionPhase.Connected -> false
                is SessionPhase.Disconnected -> nowMs - phase.sinceMs > disconnectedReapMs
            }
        }.map { it.id }
        doomed.forEach { entries.remove(it) }
        return doomed
    }

    @Synchronized
    fun ids(): List<String> = entries.keys.toList()

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        /**
         * An offer answered but never connected (no ICE/DTLS within this
         * window) is dead: the reap must not wait on libwebrtc's much longer
         * native timeouts.
         */
        const val CONNECT_REAP_MS = 10_000L

        /** A session ICE marked DISCONNECTED for longer than this is reaped. */
        const val DISCONNECTED_REAP_MS = 30_000L
    }
}
