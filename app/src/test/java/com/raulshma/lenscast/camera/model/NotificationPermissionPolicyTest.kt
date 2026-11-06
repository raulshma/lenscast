package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the POST_NOTIFICATIONS ask-once decision behind the camera
 * screen's first-launch permission pass: the matrix over
 * asked-before/granted/API level, the API-33 boundary, and the
 * mark-before-verdict ordering that makes the ask a one-shot even when it is
 * skipped. These pin the original composition guard's behavior, not an
 * idealized prompt.
 */
class NotificationPermissionPolicyTest {

    @Test
    fun `pins the real permission name and api gate`() {
        assertEquals("android.permission.POST_NOTIFICATIONS", NotificationPermissionPolicy.PERMISSION)
        assertEquals(33, NotificationPermissionPolicy.REQUIRED_API_LEVEL)
    }

    @Test
    fun `first evaluation ungranted on api 33 plus asks`() {
        assertTrue(NotificationPermissionPolicy.shouldAsk(granted = false, askedBefore = false, apiLevel = 33))
        assertTrue(NotificationPermissionPolicy.shouldAsk(granted = false, askedBefore = false, apiLevel = 34))
        assertTrue(NotificationPermissionPolicy.shouldAsk(granted = false, askedBefore = false, apiLevel = 36))
    }

    @Test
    fun `already granted never asks`() {
        assertFalse(NotificationPermissionPolicy.shouldAsk(granted = true, askedBefore = false, apiLevel = 33))
        assertFalse(NotificationPermissionPolicy.shouldAsk(granted = true, askedBefore = false, apiLevel = 34))
    }

    @Test
    fun `asked before never asks again, denial included`() {
        // Pin of the original guard: after the one-shot (including a denial)
        // the screen never re-prompts on later fresh compositions (each
        // navigation back re-ran the effect and only the asked-flag stopped
        // it).
        assertFalse(NotificationPermissionPolicy.shouldAsk(granted = false, askedBefore = true, apiLevel = 33))
        assertFalse(NotificationPermissionPolicy.shouldAsk(granted = true, askedBefore = true, apiLevel = 33))
        assertFalse(NotificationPermissionPolicy.shouldAsk(granted = false, askedBefore = true, apiLevel = 36))
    }

    @Test
    fun `below api 33 never asks`() {
        assertFalse(NotificationPermissionPolicy.shouldAsk(granted = false, askedBefore = false, apiLevel = 23))
        assertFalse(NotificationPermissionPolicy.shouldAsk(granted = false, askedBefore = false, apiLevel = 32))
    }

    @Test
    fun `api 33 is the exact required boundary`() {
        assertFalse(NotificationPermissionPolicy.isRequired(32))
        assertTrue(NotificationPermissionPolicy.isRequired(33))
        assertFalse(NotificationPermissionPolicy.shouldAsk(granted = false, askedBefore = false, apiLevel = 32))
        assertTrue(NotificationPermissionPolicy.shouldAsk(granted = false, askedBefore = false, apiLevel = 33))
    }

    @Test
    fun `budget is spent on the first evaluation regardless of the ask verdict`() {
        // Pin of the original ladder's ordering: the flag flipped BEFORE the
        // API/granted checks, so the one-shot is consumed even when no dialog
        // can show — a later revocation in system settings never re-prompts.
        assertTrue(NotificationPermissionPolicy.shouldMarkAsked(askedBefore = false))
        // ...paired with the skip verdicts that make it a no-dialog spend:
        assertFalse(NotificationPermissionPolicy.shouldAsk(granted = true, askedBefore = false, apiLevel = 33))
        assertFalse(NotificationPermissionPolicy.shouldAsk(granted = false, askedBefore = false, apiLevel = 32))
    }

    @Test
    fun `spent budget is never re-marked`() {
        assertFalse(NotificationPermissionPolicy.shouldMarkAsked(askedBefore = true))
    }
}
