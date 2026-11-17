package com.raulshma.lenscast.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

class BootResumePolicyTest {

    @Test
    fun `disabled setting never resumes`() {
        val states = listOf(
            StreamStateJournal.State(),
            StreamStateJournal.State(web = true),
            StreamStateJournal.State(rtsp = true),
            StreamStateJournal.State(web = true, rtsp = true),
        )
        states.forEach { state ->
            assertEquals(BootResumePolicy.Verdict.NONE, BootResumePolicy.decide(false, state))
        }
    }

    @Test
    fun `enabled setting maps the journal onto the resume verdicts`() {
        val decide = { state: StreamStateJournal.State ->
            BootResumePolicy.decide(settingEnabled = true, journal = state)
        }
        assertEquals(BootResumePolicy.Verdict.NONE, decide(StreamStateJournal.State()))
        assertEquals(BootResumePolicy.Verdict.WEB, decide(StreamStateJournal.State(web = true)))
        assertEquals(BootResumePolicy.Verdict.RTSP, decide(StreamStateJournal.State(rtsp = true)))
        assertEquals(
            BootResumePolicy.Verdict.BOTH,
            decide(StreamStateJournal.State(web = true, rtsp = true)),
        )
    }

    @Test
    fun `tile start ignores the setting and defaults to web`() {
        assertEquals(
            BootResumePolicy.Verdict.WEB,
            BootResumePolicy.tileStart(StreamStateJournal.State()),
        )
        assertEquals(
            BootResumePolicy.Verdict.WEB,
            BootResumePolicy.tileStart(StreamStateJournal.State(web = true)),
        )
        assertEquals(
            BootResumePolicy.Verdict.RTSP,
            BootResumePolicy.tileStart(StreamStateJournal.State(rtsp = true)),
        )
        assertEquals(
            BootResumePolicy.Verdict.BOTH,
            BootResumePolicy.tileStart(StreamStateJournal.State(web = true, rtsp = true)),
        )
    }

    // ── the user-stopped intent flag (onTaskRemoved's record) ──

    @Test
    fun `a user-stopped flag blocks boot resume even with the setting on and outputs recorded`() {
        // The swipe-kill + OEM-kill shape: the journal still records what was
        // live, but the user killed it — boot must not resurrect the stream.
        assertEquals(
            BootResumePolicy.Verdict.NONE,
            BootResumePolicy.decide(
                settingEnabled = true,
                journal = StreamStateJournal.State(web = true, rtsp = true, userStopped = true),
            ),
        )
    }

    @Test
    fun `tile start ignores the user-stopped flag`() {
        // A tile tap is an explicit manual request in the present — it is not
        // the automation the flag exists to gate.
        assertEquals(
            BootResumePolicy.Verdict.BOTH,
            BootResumePolicy.tileStart(StreamStateJournal.State(web = true, rtsp = true, userStopped = true)),
        )
    }
}
