package com.raulshma.lenscast.streaming

import android.content.Context
import com.raulshma.lenscast.camera.model.StreamToggle
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.readJsonOrDefault
import com.raulshma.lenscast.core.writeAtomicallyOrWarn
import java.io.File

/**
 * The boot-resume journal: an atomic-write file in app-private filesDir (via
 * [com.raulshma.lenscast.core.writeAtomically]) recording which stream
 * outputs the user last left live, so the boot receiver and the Quick
 * Settings tile can restore or match the session the user actually wants.
 * Serializes through App Json's one Moshi instance, and the [State] codec is
 * plain JVM code tested without a device (the injected [File] constructor is
 * the test seam; the Context constructor is the production entry).
 */
class StreamStateJournal(private val file: File) {

    constructor(context: Context) : this(File(File(context.filesDir, DIRECTORY), FILE_NAME))

    /** Which outputs the user last left intentionally running. */
    data class State(val web: Boolean = false, val rtsp: Boolean = false) {
        val anyLive: Boolean get() = web || rtsp
    }

    private val adapter by lazy { AppJson.moshi.adapter(State::class.java) }

    fun load(): State = file.readJsonOrDefault(
        adapter,
        State(),
        warn = "Failed to read stream-state journal; assuming nothing was live",
    )

    fun save(state: State) {
        // Losing the persisted copy only means no resume after reboot.
        file.writeAtomicallyOrWarn(
            adapter.toJson(state),
            warn = "Failed to persist stream-state journal",
        )
    }

    companion object {
        private const val DIRECTORY = "streaming"
        private const val FILE_NAME = "stream_state.json"
    }
}

/**
 * The pure resume decision. [decide] is the boot verdict: no setting, or an
 * all-off journal, means nothing to restore; otherwise the journal maps onto
 * which outputs the boot receiver starts (both through the Stream Toggle's
 * startBoth so the rollback discipline stays single-homed). [tileStart] is
 * the tile's on-tap variant — a tile tap is a manual request, so it ignores
 * the setting and defaults to the web output when the journal has never
 * recorded a live session.
 */
object BootResumePolicy {

    enum class Verdict { NONE, WEB, RTSP, BOTH }

    fun decide(settingEnabled: Boolean, journal: StreamStateJournal.State): Verdict = when {
        !settingEnabled || !journal.anyLive -> Verdict.NONE
        journal.web && journal.rtsp -> Verdict.BOTH
        journal.web -> Verdict.WEB
        else -> Verdict.RTSP
    }

    fun tileStart(journal: StreamStateJournal.State): Verdict = when {
        journal.web && journal.rtsp -> Verdict.BOTH
        journal.rtsp -> Verdict.RTSP
        else -> Verdict.WEB
    }

    /** Runs a verdict through the one Stream Toggle ladder — no caller re-rolls a start path. */
    suspend fun execute(toggle: StreamToggle, verdict: Verdict) {
        when (verdict) {
            Verdict.BOTH -> toggle.startBoth()
            Verdict.WEB -> toggle.startWeb()
            Verdict.RTSP -> toggle.startRtsp()
            Verdict.NONE -> Unit
        }
    }
}
