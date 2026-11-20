package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.camera.model.StreamToggle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ResumeController]'s ordering contract, over the fake idioms the
 * [StreamToggle] and [StreamStateJournal] tests already use: a real journal
 * on a temp file, a scriptable [StreamToggle.Transports], and the disk
 * reads/gates as recording lambdas — all sharing ONE call list so the
 * land-then-execute order is assertable across the read → land → execute
 * seam (the invariant's whole point).
 *
 * The relay's `rearmPersistedOutputs = false` routing never reaches this
 * class — MainApplication's one-line `if` is what keeps the flag=false
 * landing FSI-only, and the service side of that routing is pinned by
 * StreamingServiceTest's relay harness — so the not-resuming shapes pinned
 * here are the verdict-NONE forms: nothing persisted, and the standing
 * user-stopped guard.
 */
class ResumeControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun journal(): StreamStateJournal =
        StreamStateJournal(File(tmp.root, "streaming/stream_state.json"))

    /**
     * Scriptable fake over the one Toggle: records every call into the
     * SHARED list, and its gate flags start where the caller sets them —
     * enabled for the boot shapes (the Settings Applier already applied),
     * gated-off for the fresh-process relaunch shape (only the controller's
     * landing can open them, exactly like the manager's live flags).
     */
    private class RecordingTransports(
        private val calls: MutableList<String>,
        override var webEnabled: Boolean = false,
        override var rtspEnabled: Boolean = false,
    ) : StreamToggle.Transports {
        override val webActive = false
        override val rtspActive = false

        override fun ensureServerRunning(): Boolean {
            calls += "ensureServer"
            return true
        }

        override fun startWeb(): Boolean {
            calls += "startWeb"
            return true
        }

        override fun stopWeb() {
            calls += "stopWeb"
        }

        override fun startRtsp(): Boolean {
            calls += "startRtsp"
            return true
        }

        override fun stopRtsp() {
            calls += "stopRtsp"
        }

        override fun stopServer() {
            calls += "stopServer"
        }

        override suspend fun beginSession() {
            calls += "beginSession"
        }

        override suspend fun endSession() {
            calls += "endSession"
        }
    }

    /** The controller under test over one shared call list and fake seam set. */
    private fun controller(
        calls: MutableList<String>,
        journal: StreamStateJournal,
        transports: RecordingTransports,
        bootSetting: Boolean = false,
        webFromDisk: Boolean = false,
        rtspFromDisk: Boolean = false,
        bootSettingError: Exception? = null,
        webFromDiskError: Exception? = null,
        gateWebError: Exception? = null,
    ): ResumeController = ResumeController(
        journal = journal,
        bootSettingEnabled = {
            calls += "readBootSetting"
            bootSettingError?.let { throw it }
            bootSetting
        },
        webEnabledFromDisk = {
            calls += "readWeb"
            webFromDiskError?.let { throw it }
            webFromDisk
        },
        rtspEnabledFromDisk = {
            calls += "readRtsp"
            rtspFromDisk
        },
        setWebGate = { enabled ->
            calls += "landWeb=$enabled"
            gateWebError?.let { throw it }
            transports.webEnabled = enabled
        },
        setRtspGate = { enabled ->
            calls += "landRtsp=$enabled"
            transports.rtspEnabled = enabled
        },
        toggle = StreamToggle(transports),
    )

    // ── the boot variant: consume → decide → execute (no landing) ──

    @Test
    fun `boot resumes the journal-recorded outputs through the toggle without landing gates`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val target = controller(
                calls,
                journal().apply { save(StreamStateJournal.State(web = true, rtsp = false)) },
                // The applier's gates are already open by boot time.
                transports = RecordingTransports(calls, webEnabled = true),
                bootSetting = true,
            )

            target.resume(ResumeController.Source.Boot)

            // The web record starts; the gates were never this path's to
            // touch — no land entries at all.
            assertEquals(listOf("readBootSetting", "startWeb", "beginSession"), calls)
        }

    @Test
    fun `a standing user-stopped flag blocks the first boot only — the consumption spent it`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val target = controller(
                calls,
                journal().apply {
                    save(StreamStateJournal.State(web = true, rtsp = true))
                    markUserStopped()
                },
                transports = RecordingTransports(calls, webEnabled = true, rtspEnabled = true),
                bootSetting = true,
            )

            target.resume(ResumeController.Source.Boot)

            // The verdict judged the AS-STOOD snapshot (the flag stood in it)
            // and the persisted copy spent it in the same operation.
            assertEquals(
                "nothing resumed while the flag stood",
                listOf("readBootSetting"),
                calls,
            )
            assertEquals(false, journalFileUserStopped())
            // ... so a SECOND boot (the flag gates exactly one) resumes.
            target.resume(ResumeController.Source.Boot)
            assertEquals(
                listOf("readBootSetting", "readBootSetting", "startWeb", "beginSession", "startRtsp", "beginSession"),
                calls,
            )
        }

    @Test
    fun `boot with the setting off starts nothing`() = runBlocking {
        val calls = mutableListOf<String>()
        val target = controller(
            calls,
            journal().apply { save(StreamStateJournal.State(web = true, rtsp = true)) },
            transports = RecordingTransports(calls, webEnabled = true, rtspEnabled = true),
            bootSetting = false,
        )

        target.resume(ResumeController.Source.Boot)

        assertEquals(listOf("readBootSetting"), calls)
    }

    // ── the relaunch variant: disk reads → decideRearm → LAND → execute ──

    @Test
    fun `the relaunch re-arm lands the read enables on the gates BEFORE the verdict executes`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val target = controller(
                calls,
                // The quit ladder wiped the journal; the enables live on disk.
                journal(),
                // A fresh process: both live gates still hold the defaults.
                transports = RecordingTransports(calls, webEnabled = false, rtspEnabled = false),
                webFromDisk = true,
                rtspFromDisk = true,
            )

            target.resume(ResumeController.Source.Relaunch)

            // The invariant, pinned as the literal call sequence: the read
            // enables land on the gates FIRST, and only then does the
            // verdict execute — and the starts really happened, because the
            // Toggle judged the LANDED gates, not the stale defaults.
            assertEquals(
                listOf(
                    "readWeb",
                    "readRtsp",
                    "landWeb=true",
                    "landRtsp=true",
                    "startWeb",
                    "beginSession",
                    "startRtsp",
                    "beginSession",
                ),
                calls,
            )
            assertTrue(
                "land before execute, per leg",
                calls.indexOf("landWeb=true") < calls.indexOf("startWeb") &&
                    calls.indexOf("landRtsp=true") < calls.indexOf("startRtsp"),
            )
        }

    @Test
    fun `the relaunch re-arm with nothing persisted lands the gates off and starts nothing`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val target = controller(
                calls,
                journal(),
                transports = RecordingTransports(calls, webEnabled = true, rtspEnabled = true),
                webFromDisk = false,
                rtspFromDisk = false,
            )

            target.resume(ResumeController.Source.Relaunch)

            // Exactly today's shape: the landing is UNCONDITIONAL (the
            // applier's own application of the same values no-ops against
            // it), the verdict is NONE, and the toggle starts nothing.
            assertEquals(listOf("readWeb", "readRtsp", "landWeb=false", "landRtsp=false"), calls)
        }

    @Test
    fun `a standing user-stopped flag blocks the relaunch re-arm and is never spent by it`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val journal = journal().apply { markUserStopped() }
            val target = controller(
                calls,
                journal,
                transports = RecordingTransports(calls, webEnabled = false, rtspEnabled = false),
                webFromDisk = true,
                rtspFromDisk = true,
            )

            target.resume(ResumeController.Source.Relaunch)

            // The re-arm only HONORS the flag (the non-consuming read) —
            // the boot consumption and the next live output are its only
            // spenders.
            assertEquals(listOf("readWeb", "readRtsp", "landWeb=true", "landRtsp=true"), calls)
            assertTrue(journal.load().userStopped)
        }

    // ── failure tolerance: the guards live with the CALLERS (the relay
    // contract) — the controller propagates, and the callers' runCatching
    // logs exactly as they did when the ladders lived there ──

    @Test
    fun `a throw in the relaunch input read propagates to the caller's guard and skips the leg`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val target = controller(
                calls,
                journal(),
                transports = RecordingTransports(calls, webEnabled = false, rtspEnabled = false),
                webFromDiskError = IllegalStateException("store wedged"),
            )

            assertThrows(IllegalStateException::class.java) {
                runBlocking { target.resume(ResumeController.Source.Relaunch) }
            }

            // The read threw: no landing, no execute — the caller's guard
            // logs "Relaunch re-arm failed" and the server-only recovery the
            // promotion already landed stands.
            assertEquals(listOf("readWeb"), calls)
        }

    @Test
    fun `a throw in the boot setting read propagates after the consume already spent the flag`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val journal = journal().apply {
                save(StreamStateJournal.State(web = true, rtsp = true))
                markUserStopped()
            }
            val target = controller(
                calls,
                journal,
                transports = RecordingTransports(calls, webEnabled = true, rtspEnabled = true),
                bootSetting = true,
                bootSettingError = IllegalStateException("store wedged"),
            )

            assertThrows(IllegalStateException::class.java) {
                runBlocking { target.resume(ResumeController.Source.Boot) }
            }

            // The consume is the read-and-spend ONE operation: it completed
            // before the setting read threw — the same property the
            // receiver's guard had (the flag is spent, nothing resumed, the
            // guard logs "Boot stream resume failed").
            assertEquals(listOf("readBootSetting"), calls)
            assertEquals(false, journal.load().userStopped)
        }

    @Test
    fun `a throw landing the gates never falls through to the execute`() = runBlocking {
        val calls = mutableListOf<String>()
        val target = controller(
            calls,
            journal(),
            transports = RecordingTransports(calls, webEnabled = false, rtspEnabled = false),
            webFromDisk = true,
            rtspFromDisk = true,
            gateWebError = IllegalStateException("manager wedged"),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { target.resume(ResumeController.Source.Relaunch) }
        }

        // The landing is the invariant's seam: a throw there must leave the
        // verdict unexecuted — the caller's guard reports it instead.
        assertEquals(listOf("readWeb", "readRtsp", "landWeb=true"), calls)
    }

    /** The persisted flag state after the controller's own consumption. */
    private fun journalFileUserStopped(): Boolean =
        StreamStateJournal(File(tmp.root, "streaming/stream_state.json")).load().userStopped
}
