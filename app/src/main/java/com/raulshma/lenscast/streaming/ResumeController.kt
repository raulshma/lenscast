package com.raulshma.lenscast.streaming

import android.util.Log
import com.raulshma.lenscast.camera.model.StreamToggle

/**
 * The one resume owner: both unattended resume variants — the device boot
 * ([Source.Boot], via [BootCompletedReceiver]) and the post-death relaunch
 * re-arm ([Source.Relaunch], via
 * [com.raulshma.lenscast.core.ReliabilityRelay]'s implementation in
 * MainApplication) — run their whole read → land-on-gates → execute
 * ordering through [resume], so the ordering invariant is this class's
 * documented contract and tested once, with the source as the only varying
 * input. The decision stays in [BootResumePolicy] (the pure verdict
 * module); the execution stays in the one Stream Toggle ladder
 * ([StreamToggle] — the same startBoth discipline the camera screen and the
 * Web API use); what this class owns is the SEQUENCE — the seam across
 * which drift already shipped once.
 *
 * **The invariant: land before execute.** The Toggle's settings gates judge
 * the manager's LIVE enable flags at execution time. The relaunch reads its
 * enables FROM DISK (a fresh process's shared flows still hold the
 * descriptor defaults — the same race [BootCompletedReceiver]'s setting
 * read dodges, and the Settings Applier's collectors may not have landed
 * yet), so the read values must land on the manager's gates BEFORE
 * [BootResumePolicy.execute] runs, or the Toggle gates off exactly the
 * outputs the re-arm verdict just chose. The ordering is structural here:
 * the shared [landThenExecute] tail lands first, logs the verdict, then
 * executes, and no caller can re-roll the sequence by hand. The shipped
 * instance of the drift: the soft-restart ladder's re-arm once restarted
 * only the server shell and left the persisted-enabled outputs down after
 * every ladder run (the re-arm executed against gates the read enables
 * never reached — [StreamToggle.startServerWithOutputs] is that bug's
 * fix). The boot variant lands nothing: its gates were applied by the
 * Settings Applier long before the broadcast arrives.
 *
 * **The sources.** The ONLY varying part is the input read; everything
 * after the verdict is the shared tail.
 * - [Source.Boot]: consume the journal's user-stopped flag
 *   ([StreamStateJournal.consumeUserStoppedForBoot] — the read-and-spend
 *   as one operation, so the verdict honors a standing swipe-kill while
 *   the flag still gates exactly one boot), read the `resumeStreamsOnBoot`
 *   setting from disk (a suspend read — the shared StateFlow's first value
 *   is the descriptor default, and BOOT_COMPLETED can arrive tens of
 *   milliseconds after Application.onCreate), decide
 *   ([BootResumePolicy.decide]), execute.
 * - [Source.Relaunch]: read the persisted web/RTSP enables from disk (the
 *   same default-first race), read the journal — whose recorded liveness
 *   the quit ladder deliberately wiped when the restart tore the server
 *   down, so the verdict keys on the enables and the journal contributes
 *   only its user-stopped guard ([StreamStateJournal.load], the
 *   non-consuming read: the re-arm only honors the flag, it never spends
 *   it) — decide ([BootResumePolicy.decideRearm]), LAND the enables on the
 *   gates, execute.
 *
 * **Failure tolerance lives with the callers, not here.** [resume] lets
 * throws propagate: the relay contract says implementations are
 * failure-tolerant — MainApplication wraps the relaunch call in its own
 * runCatching and logs `Relaunch re-arm failed`, exactly as it did when the
 * ladder lived there, and the receiver wraps the boot call and logs
 * `Boot stream resume failed` before finishing its `goAsync`. The guards
 * stay byte-identical where they are today; this class takes over the
 * ordering only.
 */
class ResumeController(
    private val journal: StreamStateJournal,
    /** The boot variant's `resumeStreamsOnBoot` read — the suspend disk read, not the default-first shared flow. */
    private val bootSettingEnabled: suspend () -> Boolean,
    /** The relaunch variant's persisted web enable — the suspend disk read (the post-death re-arm must see the persisted flags). */
    private val webEnabledFromDisk: suspend () -> Boolean,
    /** The relaunch variant's persisted RTSP enable — the re-arm's other persisted gate. */
    private val rtspEnabledFromDisk: suspend () -> Boolean,
    /** The manager's live web gate (`StreamingManager.setWebStreamingEnabled`) — the landing target the Toggle's gates judge. */
    private val setWebGate: (Boolean) -> Unit,
    /** The manager's live RTSP gate (`StreamingManager.setRtspEnabled`). */
    private val setRtspGate: (Boolean) -> Unit,
    /** The one Stream Toggle ladder every verdict executes through. */
    private val toggle: StreamToggle,
) {

    /**
     * Which resume variant is running. The variants share the whole
     * land-then-execute tail; the source decides only the input read and
     * which [BootResumePolicy] entry judges it.
     */
    enum class Source { Boot, Relaunch }

    /**
     * The ordering contract (see the class KDoc): read the source's input,
     * decide, land, execute. Suspends on the disk reads — the callers
     * launch it off their own dispatch (the receiver's `goAsync` scope,
     * MainApplication's app scope) and guard it themselves.
     */
    suspend fun resume(source: Source) {
        when (source) {
            Source.Boot -> resumeBoot()
            Source.Relaunch -> resumeRelaunch()
        }
    }

    /**
     * The boot variant: journal consume → setting read → decide → execute —
     * exactly the receiver's former body. Nothing lands on the gates (the
     * applier already applied them); a standing user-stopped flag in the
     * consumed snapshot decides NONE on its own, and its spending means it
     * gates exactly this one boot.
     */
    private suspend fun resumeBoot() {
        val snapshot = journal.consumeUserStoppedForBoot()
        if (snapshot.userStopped) {
            // The user swiped the app away before shutdown and an OEM kill
            // followed: boot must not resurrect a stream the user explicitly
            // killed.
            Log.d(TAG, "User stopped the stream before shutdown; skipping boot resume (flag consumed)")
        }
        val verdict = BootResumePolicy.decide(
            settingEnabled = bootSettingEnabled(),
            journal = snapshot,
        )
        landThenExecute(verdict, verdictLog = "Boot stream resume verdict: $verdict")
    }

    /**
     * The relaunch variant: disk-read enables → journal guard → decideRearm
     * → land → execute — exactly the relay implementation's former body.
     * The landing is the subtle precondition: [BootResumePolicy.decideRearm]
     * judges the disk values directly, but [BootResumePolicy.execute]'s
     * Toggle judges the LIVE gates, so the read values must be on the gates
     * before it runs (idempotent — the applier's own application of the
     * same values no-ops).
     */
    private suspend fun resumeRelaunch() {
        val webEnabled = webEnabledFromDisk()
        val rtspEnabled = rtspEnabledFromDisk()
        val verdict = BootResumePolicy.decideRearm(
            journal = journal.load(),
            webEnabled = webEnabled,
            rtspEnabled = rtspEnabled,
        )
        landThenExecute(
            verdict,
            verdictLog = "Relaunch re-arm verdict: $verdict",
            webEnabled = webEnabled,
            rtspEnabled = rtspEnabled,
        )
    }

    /**
     * The shared tail — the land-then-execute ordering, ONE implementation
     * for both variants: land any read enables on the manager's gates
     * (null for the sources that read no enables — the boot variant lands
     * nothing), log the verdict, run it through the one Toggle ladder. No
     * caller-facing shape so the sequence cannot be re-rolled piecemeal.
     */
    private suspend fun landThenExecute(
        verdict: BootResumePolicy.Verdict,
        verdictLog: String,
        webEnabled: Boolean? = null,
        rtspEnabled: Boolean? = null,
    ) {
        if (webEnabled != null) setWebGate(webEnabled)
        if (rtspEnabled != null) setRtspGate(rtspEnabled)
        Log.d(TAG, verdictLog)
        BootResumePolicy.execute(toggle = toggle, verdict = verdict)
    }

    companion object {
        private const val TAG = "ResumeController"
    }
}
