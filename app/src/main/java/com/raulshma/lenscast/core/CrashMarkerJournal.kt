package com.raulshma.lenscast.core

import android.content.Context
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import com.squareup.moshi.JsonClass
import java.io.File

/**
 * The crash marker: an atomic-write file in app-private filesDir (the
 * [com.raulshma.lenscast.streaming.StreamStateJournal] shape — the same
 * App Json adapter + atomic rename + corrupt-file-falls-back-to-default
 * tolerance) recording the consecutive-crash counter the crash handler's
 * loop guard ([CrashRelaunchPolicy]) reads. It exists because every
 * in-memory way back dies with the crashing process: the counter is the one
 * fact that must outlive the death, or the "crash loop" it measures would
 * restart from zero on every relaunch and never give up.
 *
 * Written by exactly two sites: the uncaught-exception handler
 * ([recordCrash] — once per process, during the crash itself, BEFORE the
 * chained handler kills the process) and the app-start clean-uptime reset
 * ([reset] — once per process, after the clean window survives). A
 * synchronized lock serializes the read-modify-write pair against the one
 * race that exists (a crash landing as the reset's write is in flight).
 *
 * Losing a write is tolerated the same way every journal here is: a lost
 * counter understates the loop by one crash — one extra deterministic
 * relaunch before give-up — never a lost recovery. [load] is the telemetry
 * read (blueprint item 8, wave 3): [State.consecutiveCrashes] plus the
 * derived [State.gaveUp] are the give-up/crash-count facts `GET /api/system`
 * publishes.
 */
class CrashMarkerJournal(private val file: File) {

    constructor(context: Context) : this(File(File(context.filesDir, DIRECTORY), FILE_NAME))

    private val lock = Any()

    /**
     * The persisted crash-loop state. New fields default so older payloads
     * load unchanged, exactly like the stream-state journal's evolution.
     */
    @JsonClass(generateAdapter = true)
    data class State(
        /** Consecutive crashes whose uptime stayed under the clean-uptime window. */
        val consecutiveCrashes: Int = 0,
        /** Wall clock of the last recorded crash (telemetry only — never a decision input). */
        val lastCrashAtMs: Long = 0L,
    ) {
        /**
         * The give-up observation, derived — not persisted — so there is
         * exactly one source of truth for the verdict
         * ([CrashRelaunchPolicy.decide] over the counter).
         */
        val gaveUp: Boolean
            get() = consecutiveCrashes > CrashRelaunchPolicy.MAX_CONSECUTIVE_CRASHES
    }

    private val adapter by lazy { AppJson.moshi.adapter(State::class.java) }

    fun load(): State = file.readJsonOrDefault(
        adapter,
        State(),
        warn = "Failed to read crash marker; assuming no crash loop",
    )

    /**
     * Records the crash the handler just counted ([crashCount] from
     * [CrashRelaunchPolicy.nextCrashCount]). Runs inside the crash handler —
     * it must persist BEFORE the chained platform handler ends the process,
     * which is the ACRA-shaped ordering the handler owns.
     */
    fun recordCrash(crashCount: Int, atMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            file.writeAtomicallyOrWarn(
                adapter.toJson(State(consecutiveCrashes = crashCount, lastCrashAtMs = atMs)),
                warn = "Failed to persist crash marker; the loop guard may relaunch once extra",
            )
        }
    }

    /**
     * Zeroes the counter — the app-start clean-uptime reset (a process that
     * survived [CrashRelaunchPolicy.CLEAN_UPTIME_WINDOW_MS] proved itself
     * clean) and the manual reset path (the telemetry surface, wave 3).
     */
    fun reset() {
        synchronized(lock) {
            file.writeAtomicallyOrWarn(
                adapter.toJson(State()),
                warn = "Failed to persist crash-marker reset",
            )
        }
    }

    companion object {
        private const val DIRECTORY = "crash"
        private const val FILE_NAME = "crash_marker.json"
    }
}
