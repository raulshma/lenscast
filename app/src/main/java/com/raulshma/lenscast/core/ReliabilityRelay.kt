package com.raulshma.lenscast.core

/**
 * The reliability runtime's narrow service-entry relay: the collaborator
 * entries [com.raulshma.lenscast.streaming.StreamingService] needs, in ONE
 * small interface implemented by the composition root
 * ([com.raulshma.lenscast.MainApplication]). The service casts to this —
 * never to the full [com.raulshma.lenscast.AppGraph] — so its five former
 * graph pulls (heartbeat evaluation, death-recovery aftermath, relaunch
 * re-arm, denied-promotion wake, swipe-away record) became four typed
 * deliveries, and the DECISIONS — which controller runs, which policy
 * decides, what the re-arm ladder reads — stay at the composition root,
 * off the service's concerns and out of its cold-start reach. The narrow
 * cast is also what makes the branches harness-implementable: a test
 * application implements these four members and drives a branch the old
 * `as? AppGraph` guard used to silently no-op (the heartbeat's evaluation
 * half was unreachable under the fat cast).
 *
 * Contract: implementations are failure-tolerant — every method wraps its
 * collaborators' throws and logs them; the service dispatches bare.
 */
interface ReliabilityRelay {

    /**
     * The alarm heartbeat's fire landed in the service
     * (`ACTION_HEARTBEAT`, after the service's own promote-unless-streaming
     * guard): run the liveness evaluation + escalation choreography. Routes
     * to [HeartbeatController.onHeartbeatFired] — the verdict comes from
     * [WatchdogAlarmPolicy], launched off the main thread.
     */
    fun onHeartbeatDelivered()

    /**
     * A death-and-relaunch recovery landed the server-only promotion (the
     * relaunch alarm or a sticky restart, the heartbeat's promotion, or the
     * dashboard's server start): run the whole startServerForeground
     * aftermath — the post-death camera bring-up prompt's evaluation, and,
     * when [rearmPersistedOutputs] marks the landing as the restart
     * recovery (the quit ladder's own ACTION_RELAUNCH / null-intent paths —
     * the App-Restart promise), the persisted-output re-arm on top. The
     * flag is the service's routing fact (which intent branch landed); what
     * a re-arm reads and starts stays here. Routes to
     * [FsiResumeController.onServerOnlyLanded] and the re-arm ladder.
     */
    fun onDeathRecoveryLanded(rearmPersistedOutputs: Boolean)

    /**
     * The streaming (CAMERA/MICROPHONE) promotion was denied — the
     * locked-mount start: the server-only fallback promotion is already
     * held, and whether the full-screen-intent wake should fire is the
     * lock-bypass shim's decision. Routes to
     * [LockBypassController.requestWake].
     */
    fun onStreamingPromotionDenied()

    /**
     * The user swiped the task away (`onTaskRemoved`): record the swipe-kill
     * intent on the boot-resume journal
     * ([com.raulshma.lenscast.streaming.StreamStateJournal.markUserStopped]).
     * Record-only; the flag's lifetime rule (gates exactly one boot, spent
     * by the boot consumption, cleared by the next live output) is the
     * journal's.
     */
    fun onUserSwipedAway()
}
