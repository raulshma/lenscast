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
}
