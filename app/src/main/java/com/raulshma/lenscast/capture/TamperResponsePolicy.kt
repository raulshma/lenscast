package com.raulshma.lenscast.capture

/**
 * The pure tamper-response verdict: what happens the moment a power cut is
 * confirmed on a live, charging camera. Tamper detection's alert fan-out
 * (webhook/MQTT/notification/deterrence) belongs to the Detection
 * Coordinator; this policy decides the *preservation* response — flush the
 * detection-event log so the recorded event is on disk before the power
 * dies, and (when backup is armed at all) promote every pending un-uploaded
 * capture's backup to an expedited request that skips the Wi-Fi-only gate.
 *
 * The Wi-Fi bypass is deliberate, not incidental: the most likely next act
 * after a power cut is the camera leaving the network for good, so evidence
 * over whatever network exists now beats the data-saver preference. Pure
 * over its flags, so the decision ladder is JVM-tested without WorkManager.
 */
object TamperResponsePolicy {

    /** The response the tamper site executes, in order. */
    data class Verdict(
        /** Force the detection-event log's current state to disk. */
        val flushEventLog: Boolean,
        /** Expedite the pending captures' backups (cancel + re-enqueue). */
        val expediteBackup: Boolean,
        /** The expedited requests ignore the Wi-Fi-only backup setting. */
        val bypassWifiOnly: Boolean,
    )

    /**
     * [backupEnabled] is the master backup arm — without it there is no
     * target and no queued work, so the expedite collapses to nothing. The
     * log flush always runs: it is cheap, and the tamper event itself is the
     * one log entry that must survive.
     */
    fun decide(backupEnabled: Boolean): Verdict = Verdict(
        flushEventLog = true,
        expediteBackup = backupEnabled,
        bypassWifiOnly = backupEnabled,
    )
}
