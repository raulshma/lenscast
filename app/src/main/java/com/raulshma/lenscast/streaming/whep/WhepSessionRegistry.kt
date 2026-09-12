package com.raulshma.lenscast.streaming.whep

import java.util.LinkedHashMap

/**
 * The WHEP session registry: the pure bookkeeping behind the viewer cap and
 * the dead-session GC, JVM-tested without libwebrtc.
 *
 * Every admitted session is an [Entry] stamped with its creation time; the
 * lifecycle marks move it through connected (ICE/DTLS reached the viewer)
 * and possibly disconnected again. [reapIds] names the sessions worth
 * tearing down:
 *
 *  - never connected past [connectReapMs] — an offer that never completed
 *    ICE/DTLS (the viewer vanished, a firewall ate the candidates) must not
 *    hold a hardware encoder slot;
 *  - disconnected past [disconnectedReapMs] — a viewer that closed its tab
 *    without DELETEing the session resource (ICE notices within its own
 *    timeout; the registry gives the transient DISCONNECTED state a grace
 *    window before reaping).
 *
 * Thread-safe: the endpoint's NanoHTTPD worker admits sessions while the
 * reap loop and libwebrtc's callback threads mark them.
 */
class WhepSessionRegistry(
    private val connectReapMs: Long = CONNECT_REAP_MS,
    private val disconnectedReapMs: Long = DISCONNECTED_REAP_MS,
) {

    class Entry internal constructor(
        val id: String,
        val createdAtMs: Long,
    ) {
        /** When the session reached CONNECTED/COMPLETED; null while it never did. */
        @Volatile var connectedAtMs: Long? = null
            internal set

        /** When the session's ICE most recently went DISCONNECTED; null while connected/never-connected. */
        @Volatile var disconnectedAtMs: Long? = null
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

    /** Marks the session connected; clears any earlier disconnected mark. */
    @Synchronized
    fun markConnected(id: String, nowMs: Long) {
        entries[id]?.let {
            it.connectedAtMs = nowMs
            it.disconnectedAtMs = null
        }
    }

    /** Marks the session disconnected (only meaningful after a connect). */
    @Synchronized
    fun markDisconnected(id: String, nowMs: Long) {
        entries[id]?.let { if (it.connectedAtMs != null) it.disconnectedAtMs = nowMs }
    }

    /**
     * The ids worth tearing down now, removed from the registry as a side
     * effect — the caller closes each named session's native pieces.
     */
    @Synchronized
    fun reapIds(nowMs: Long): List<String> {
        val doomed = entries.values.filter { entry ->
            val connectedAt = entry.connectedAtMs
            when {
                connectedAt == null -> nowMs - entry.createdAtMs > connectReapMs
                else -> {
                    val disconnectedAt = entry.disconnectedAtMs
                    disconnectedAt != null && nowMs - disconnectedAt > disconnectedReapMs
                }
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
