package com.raulshma.lenscast.streaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Keeps the [StreamStateJournal] in step with the StreamingManager's live
 * output flows, so the journal always holds the last observed stream state
 * without any start/stop call site knowing the journal exists (StreamingManager
 * itself stays untouched).
 *
 * Design choice: the manager's flows do not distinguish a user-initiated stop
 * from a crash or process death, so the journal records the last *observed*
 * live state — a crash while live also resumes at boot, which is the
 * surveillance-friendly reading. The first combined emission (a fresh
 * process's all-off snapshot) is dropped so it can never erase the state the
 * previous run recorded.
 */
class StreamStateJournalWriter(
    private val journal: StreamStateJournal,
    private val webActive: StateFlow<Boolean>,
    private val rtspActive: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {

    fun start() {
        scope.launch {
            combine(webActive, rtspActive) { web: Boolean, rtsp: Boolean ->
                StreamStateJournal.State(web = web, rtsp = rtsp)
            }
                .drop(1)
                .collect { state: StreamStateJournal.State -> journal.save(state) }
        }
    }
}
