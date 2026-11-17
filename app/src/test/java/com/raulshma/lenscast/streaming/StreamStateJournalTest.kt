package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StreamStateJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun journal(): StreamStateJournal =
        StreamStateJournal(File(tmp.root, "streaming/stream_state.json"))

    @Test
    fun `missing file loads all-off state`() {
        assertEquals(StreamStateJournal.State(), journal().load())
    }

    @Test
    fun `save and load round-trips both outputs`() {
        val state = StreamStateJournal.State(web = true, rtsp = true)
        val target = journal()
        target.save(state)
        assertEquals(state, target.load())
    }

    @Test
    fun `save overwrites the previous state`() {
        val target = journal()
        target.save(StreamStateJournal.State(web = true, rtsp = true))
        target.save(StreamStateJournal.State(web = false, rtsp = true))
        assertEquals(StreamStateJournal.State(web = false, rtsp = true), target.load())
    }

    @Test
    fun `corrupt file falls back to all-off state`() {
        val file = File(tmp.root, "streaming/stream_state.json")
        file.parentFile.mkdirs()
        file.writeText("{not json")
        assertEquals(StreamStateJournal.State(), journal().load())
    }

    @Test
    fun `anyLive tracks whether an output was recorded`() {
        assertEquals(false, StreamStateJournal.State().anyLive)
        assertEquals(true, StreamStateJournal.State(web = true).anyLive)
        assertEquals(true, StreamStateJournal.State(rtsp = true).anyLive)
    }

    // ── the user-stopped intent flag (onTaskRemoved's record) ──

    @Test
    fun `legacy json without the flag loads userStopped false`() {
        val file = File(tmp.root, "streaming/stream_state.json")
        file.parentFile.mkdirs()
        file.writeText("""{"web":true,"rtsp":false}""")
        assertEquals(false, journal().load().userStopped)
    }

    @Test
    fun `markUserStopped persists the flag beside the recorded outputs`() {
        val target = journal()
        target.save(StreamStateJournal.State(web = true, rtsp = true))
        target.markUserStopped()
        assertEquals(StreamStateJournal.State(web = true, rtsp = true, userStopped = true), target.load())
    }

    @Test
    fun `clearUserStopped spends the flag and keeps the outputs`() {
        val target = journal()
        target.markUserStopped()
        target.clearUserStopped()
        assertEquals(StreamStateJournal.State(userStopped = false), target.load())
    }

    @Test
    fun `recordLiveOutputs clears the flag when an output goes live again`() {
        // The next explicit start supersedes the swipe-kill intent.
        val target = journal()
        target.markUserStopped()
        target.recordLiveOutputs(web = true, rtsp = false)
        assertEquals(
            StreamStateJournal.State(web = true, rtsp = false, userStopped = false, cameraBearing = true),
            target.load(),
        )
    }

    @Test
    fun `recordLiveOutputs preserves a fresh flag when nothing goes live`() {
        // The swipe-kill's own flow transition (outputs dying with the OEM
        // kill) must not erase the flag it just wrote.
        val target = journal()
        target.markUserStopped()
        target.recordLiveOutputs(web = false, rtsp = false)
        assertEquals(StreamStateJournal.State(userStopped = true), target.load())
    }

    @Test
    fun `recordLiveOutputs never resurrects a spent flag on a plain stop`() {
        val target = journal()
        target.recordLiveOutputs(web = true, rtsp = true)
        target.recordLiveOutputs(web = false, rtsp = false)
        assertEquals(StreamStateJournal.State(userStopped = false), target.load())
    }

    // ── the FSI-resume prompted marker (blueprint P1 item 11) ──

    @Test
    fun `legacy json without the marker loads fsiResumePrompted false`() {
        val file = File(tmp.root, "streaming/stream_state.json")
        file.parentFile.mkdirs()
        file.writeText("""{"web":true,"rtsp":false}""")
        assertEquals(false, journal().load().fsiResumePrompted)
    }

    @Test
    fun `markFsiResumePrompted persists the marker beside the recorded outputs`() {
        val target = journal()
        target.save(StreamStateJournal.State(web = true, rtsp = false))
        target.markFsiResumePrompted()
        assertEquals(
            StreamStateJournal.State(web = true, rtsp = false, fsiResumePrompted = true),
            target.load(),
        )
    }

    @Test
    fun `recordLiveOutputs spends the marker when an output goes live again`() {
        // A live output means the camera promotion succeeded — the death
        // event the prompt announced is over, and the NEXT death may prompt
        // again (the once-per-death-event debounce).
        val target = journal()
        target.markFsiResumePrompted()
        target.recordLiveOutputs(web = true, rtsp = false)
        assertEquals(
            StreamStateJournal.State(web = true, rtsp = false, fsiResumePrompted = false, cameraBearing = true),
            target.load(),
        )
    }

    @Test
    fun `recordLiveOutputs preserves a fresh marker when nothing goes live`() {
        // The prompt fired but the recovery never came: the marker must
        // stand, or the heartbeat cadence would re-prompt every 15 minutes.
        val target = journal()
        target.markFsiResumePrompted()
        target.recordLiveOutputs(web = false, rtsp = false)
        assertEquals(StreamStateJournal.State(fsiResumePrompted = true), target.load())
    }

    // ── the camera-bearing witness (fix C: distinguish, keep firing) ──

    @Test
    fun `recordLiveOutputs writes the camera-bearing witness as the liveness equivalence`() {
        // Today EVERY live output binds the camera (both StreamToggle kinds
        // run the session's camera bind), so the witness mirrors `web ||
        // rtsp` — a web-only record stays camera-bearing and the FSI prompt
        // still fires for it. The field exists so a future non-camera output
        // kind can clear it without another shape change.
        val target = journal()
        target.recordLiveOutputs(web = true, rtsp = false)
        assertEquals(true, target.load().cameraBearing)
        target.recordLiveOutputs(web = false, rtsp = true)
        assertEquals(true, target.load().cameraBearing)
        target.recordLiveOutputs(web = true, rtsp = true)
        assertEquals(true, target.load().cameraBearing)
        target.recordLiveOutputs(web = false, rtsp = false)
        assertEquals(false, target.load().cameraBearing)
    }

    @Test
    fun `legacy json without the witness loads cameraBearing false`() {
        val file = File(tmp.root, "streaming/stream_state.json")
        file.parentFile.mkdirs()
        file.writeText("""{"web":true,"rtsp":false}""")
        assertEquals(false, journal().load().cameraBearing)
    }

    @Test
    fun `the flag markers preserve the camera-bearing witness`() {
        val target = journal()
        target.recordLiveOutputs(web = false, rtsp = true)
        target.markUserStopped()
        assertEquals(true, target.load().cameraBearing)
        target.markFsiResumePrompted()
        assertEquals(true, target.load().cameraBearing)
        target.clearUserStopped()
        assertEquals(true, target.load().cameraBearing)
    }
}
