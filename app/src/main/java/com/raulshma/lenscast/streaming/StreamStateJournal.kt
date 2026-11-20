package com.raulshma.lenscast.streaming

import com.squareup.moshi.JsonClass

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
 *
 * The journal is also the single source of *intent* for the recovery tiers:
 * [State.userStopped] records the user's swipe-kill (`onTaskRemoved` —
 * record only; stock Android keeps the service through a swipe, but an OEM
 * kill follows, and nothing may resurrect a stream the user explicitly
 * killed). The flag's lifetime is the journal's own, expressed as four
 * explicit methods — call sites never hand-roll the convention over the raw
 * field:
 *
 * - **mark** — [markUserStopped] (the service's `onTaskRemoved` record, via
 *   the [com.raulshma.lenscast.core.ReliabilityRelay]).
 * - **consume-once-for-boot** — [consumeUserStoppedForBoot] (the boot
 *   receiver): reads the flag, spends it, and hands back the snapshot the
 *   boot verdict judges ([BootResumePolicy.decide] returns NONE for a
 *   standing flag in that snapshot). The flag blocks exactly one boot — it
 *   can never block resume forever.
 * - **clear-on-live-output** — [recordLiveOutputs] (the journal writer): the
 *   next explicit start supersedes the kill intent. It never gates a manual
 *   request (the tile ignores it).
 * - **peek** — [peekUserStopped] (the heartbeat's fire-time guard): the
 *   non-consuming read; the flag's spending belongs to the boot path and
 *   the next live output only.
 *
 * [State.fsiResumePrompted] is the same family of intent flag for the FSI
 * camera-resume prompt (blueprint P1 item 11): [core.FsiResumeController]
 * sets it once per death event the prompt fired for, so the heartbeat's
 * server-only landings cannot re-prompt every 15 minutes, and
 * [recordLiveOutputs] clears it the moment an output goes live again (the
 * camera promotion succeeded — the death event is over and the NEXT death
 * may prompt again). Persisted rather than in-memory on purpose: the prompt
 * must survive the process living long enough to see repeated evaluations,
 * but never outlive the recovery it announced.
 */
class StreamStateJournal(private val file: File) {

    constructor(context: Context) : this(File(File(context.filesDir, DIRECTORY), FILE_NAME))

    /**
     * Which outputs the user last left intentionally running, plus the
     * user-stopped intent flag, the FSI-resume prompted marker, and the
     * camera-bearing witness. New fields default so older payloads (written
     * before the flag existed) load unchanged.
     */
    @JsonClass(generateAdapter = true)
    data class State(
        val web: Boolean = false,
        val rtsp: Boolean = false,
        val userStopped: Boolean = false,
        val fsiResumePrompted: Boolean = false,
        /**
         * Whether the pre-death record held a CAMERA-BEARING live output —
         * the FSI resume prompt's trigger input ([FsiResumePolicy][com.raulshma.lenscast.core.FsiResumePolicy]
         * keys on it, because the prompt is the tier ladder's CAMERA row and
         * must not fire for a record with nothing camera-bearing to
         * restore). TODAY every live output is camera-bearing — both
         * StreamToggle kinds run the session's camera bind (StreamToggle
         * `beginSession`), so [recordLiveOutputs] writes `web || rtsp`,
         * identical to [anyLive]; the explicit persisted field exists so a
         * future non-camera output kind can record the distinction without
         * another shape change, and the trigger keeps firing for today's
         * web-only records (a web-only tap restores the camera with them).
         * A legacy payload without the key loads false and self-heals on
         * the next `recordLiveOutputs` observation.
         */
        val cameraBearing: Boolean = false,
    ) {
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

    /**
     * Records the user's swipe-kill intent (`StreamingService.onTaskRemoved`)
     * beside whatever outputs the journal already holds — the outputs stay
     * recorded so the flag, not a wiped journal, is what the boot path reads.
     */
    fun markUserStopped() {
        save(load().copy(userStopped = true))
    }

    /**
     * Spends the user-stopped flag; the recorded outputs are untouched.
     * The boot receiver's path is [consumeUserStoppedForBoot], which reads
     * and spends as one operation so the verdict can still honor the kill —
     * call sites should not pair this with a prior load by hand.
     */
    fun clearUserStopped() {
        save(load().copy(userStopped = false))
    }

    /**
     * The boot consumption — the flag's read-and-spend as ONE operation
     * (BootCompletedReceiver). Returns the journal AS THE BOOT SHOULD JUDGE
     * it: a flag that stood at read time stays standing in the returned
     * snapshot, so the boot verdict honors the swipe-kill — while the
     * persisted copy spends it, because the flag gates exactly this one
     * boot and must never block resume forever (blueprint P0 item 6). A
     * flag that was not standing changes nothing.
     */
    fun consumeUserStoppedForBoot(): State {
        val asStood = load()
        if (asStood.userStopped) clearUserStopped()
        return asStood
    }

    /**
     * The non-consuming read (the heartbeat's fire-time guard via
     * [com.raulshma.lenscast.MainApplication]'s relay seam): whether the
     * swipe-kill flag stands right now, the persisted copy untouched —
     * spending belongs to [consumeUserStoppedForBoot] and the
     * clear-on-live-output path only.
     */
    fun peekUserStopped(): Boolean = load().userStopped

    /**
     * Records that the FSI camera-resume prompt fired for this death event
     * ([core.FsiResumeController] posts); beside whatever outputs the
     * journal already holds — the outputs stay recorded so the prompt's
     * inputs (was-live, user-stopped) are not disturbed by the marker.
     */
    fun markFsiResumePrompted() {
        save(load().copy(fsiResumePrompted = true))
    }

    /**
     * The journal writer's save path: persist the observed outputs, clearing
     * the user-stopped flag and the FSI-resume prompted marker when any
     * output is live (an explicit start supersedes the kill intent, and a
     * live camera means the death event the prompt announced is over) and
     * preserving both otherwise — a stop transition must neither resurrect a
     * spent flag nor erase a fresh one (the OEM kill's own outputs-dying
     * transition races this write). The camera-bearing witness rides the
     * liveness equivalence (`web || rtsp`): both output kinds bind the
     * camera through the session today — see [State.cameraBearing].
     */
    fun recordLiveOutputs(web: Boolean, rtsp: Boolean) {
        val previous = load()
        save(
            State(
                web = web,
                rtsp = rtsp,
                userStopped = previous.userStopped && !(web || rtsp),
                fsiResumePrompted = previous.fsiResumePrompted && !(web || rtsp),
                cameraBearing = web || rtsp,
            )
        )
    }

    companion object {
        private const val DIRECTORY = "streaming"
        private const val FILE_NAME = "stream_state.json"
    }
}

/**
 * The pure resume decision. [decide] is the boot verdict: no setting, an
 * all-off journal, or a recorded user-stopped intent (the swipe-kill flag)
 * means nothing to restore; otherwise the journal maps onto which outputs
 * the boot receiver starts (both through the Stream Toggle's startBoth so
 * the rollback discipline stays single-homed). [tileStart] is the tile's
 * on-tap variant — a tile tap is a manual request, so it ignores both the
 * setting and the user-stopped flag and defaults to the web output when the
 * journal has never recorded a live session.
 */
object BootResumePolicy {

    enum class Verdict { NONE, WEB, RTSP, BOTH }

    fun decide(settingEnabled: Boolean, journal: StreamStateJournal.State): Verdict = when {
        journal.userStopped -> Verdict.NONE
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

    /**
     * The post-death relaunch verdict (the dashboard's App-Restart promise:
     * "streams re-arm from their persisted settings"): the persisted output
     * enables decide which outputs come back — NOT the journal, whose
     * recorded liveness the quit ladder deliberately wipes when the restart
     * tears the server down (a journal read here would restore nothing).
     * The journal still contributes its one guard: a standing [StreamStateJournal.State.userStopped]
     * flag (the swipe-kill an OEM kill followed) means nothing re-arms — a
     * stream the user explicitly killed is not resurrected by the sticky
     * restart that can land in the same handler. Deliberately NOT gated on
     * `resumeStreamsOnBoot`: that setting scopes BOOT resume; a restart is
     * a user-initiated recovery whose promise is the persisted enables.
     */
    fun decideRearm(
        journal: StreamStateJournal.State,
        webEnabled: Boolean,
        rtspEnabled: Boolean,
    ): Verdict = when {
        journal.userStopped -> Verdict.NONE
        webEnabled && rtspEnabled -> Verdict.BOTH
        webEnabled -> Verdict.WEB
        rtspEnabled -> Verdict.RTSP
        else -> Verdict.NONE
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
