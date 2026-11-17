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

    // ── the post-death relaunch re-arm (decideRearm) ──

    @Test
    fun `the relaunch re-arm maps the persisted enables onto the verdicts`() {
        // The journal is NOT the input here — the quit ladder wipes the
        // recorded liveness when the restart tears the server down, so the
        // dashboard's restart promise keys on the persisted enable flags.
        assertEquals(
            BootResumePolicy.Verdict.NONE,
            BootResumePolicy.decideRearm(StreamStateJournal.State(), webEnabled = false, rtspEnabled = false),
        )
        assertEquals(
            BootResumePolicy.Verdict.WEB,
            BootResumePolicy.decideRearm(StreamStateJournal.State(), webEnabled = true, rtspEnabled = false),
        )
        assertEquals(
            BootResumePolicy.Verdict.RTSP,
            BootResumePolicy.decideRearm(StreamStateJournal.State(), webEnabled = false, rtspEnabled = true),
        )
        assertEquals(
            BootResumePolicy.Verdict.BOTH,
            BootResumePolicy.decideRearm(StreamStateJournal.State(), webEnabled = true, rtspEnabled = true),
        )
    }

    @Test
    fun `a user-stopped flag blocks the relaunch re-arm even with enables on`() {
        // The swipe-kill + OEM-kill + sticky-restart shape: nothing may
        // resurrect the stream the user explicitly killed.
        assertEquals(
            BootResumePolicy.Verdict.NONE,
            BootResumePolicy.decideRearm(
                StreamStateJournal.State(userStopped = true),
                webEnabled = true,
                rtspEnabled = true,
            ),
        )
    }
}
