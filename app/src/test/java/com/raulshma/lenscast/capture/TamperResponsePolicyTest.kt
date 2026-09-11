package com.raulshma.lenscast.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure tamper-response ladder: the event-log flush always fires, and the
 * backup expedite (with its Wi-Fi-only bypass) rides the backup arm — no
 * backup armed means no queued work to expedite.
 */
class TamperResponsePolicyTest {

    @Test
    fun `backup armed flushes the log and expedites with the wifi bypass`() {
        val verdict = TamperResponsePolicy.decide(backupEnabled = true)
        assertTrue(verdict.flushEventLog)
        assertTrue(verdict.expediteBackup)
        assertTrue(verdict.bypassWifiOnly)
    }

    @Test
    fun `backup disarmed still flushes the log, expedites nothing`() {
        val verdict = TamperResponsePolicy.decide(backupEnabled = false)
        assertTrue(verdict.flushEventLog)
        assertFalse(verdict.expediteBackup)
        assertFalse(verdict.bypassWifiOnly)
    }
}
