package com.raulshma.lenscast.streaming

/**
 * The half-duplex talkback phase: which side of the speaker/mic split the
 * phone is on right now. While a one-shot clip ([TalkbackPhase.Clip], the
 * WS talkback button) or a held PTT stream ([TalkbackPhase.Held], the
 * continuous WebSocket uplink) plays browser audio on the phone speaker,
 * the shared mic capture's fan-out must drop chunks — otherwise the
 * uplink echoes the talker's own voice back to every audio subscriber.
 * Pure verdicts, JVM-tested ([TalkbackPolicyTest]);
 * [AudioStreamingManager] keeps the AudioTracks and the single phase write.
 */
sealed interface TalkbackPhase {
    /** Nothing plays on the speaker; mic chunks fan out. */
    data object Idle : TalkbackPhase

    /** One-shot clip holds the speaker until [untilMs] (wall clock). */
    data class Clip(val untilMs: Long) : TalkbackPhase

    /** Continuous PTT holds the speaker for the duration of the hold. */
    data object Held : TalkbackPhase
}

object TalkbackPolicy {

    /**
     * The mic-drop verdict: chunks drop while the phase holds the speaker.
     * Expiry is pure time — a [TalkbackPhase.Clip] holds only strictly
     * before its `untilMs`, so `nowMs == untilMs` is NOT dropped (the
     * boundary preserves the old `now < talkUntilMs` semantics). A
     * [TalkbackPhase.Held] never expires on its own; only an explicit stop
     * returns to [TalkbackPhase.Idle].
     */
    fun shouldDropMic(phase: TalkbackPhase, nowMs: Long): Boolean = when (phase) {
        TalkbackPhase.Idle -> false
        is TalkbackPhase.Clip -> nowMs < phase.untilMs
        TalkbackPhase.Held -> true
    }
}
