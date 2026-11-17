package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The FSI camera-resume prompt's verdict ladder (blueprint P1 item 11 — the
 * T6 tier, restart-reliability §3.2 / §5 option B): every gate and both
 * notification kinds pinned pure-JVM, no Android types.
 */
class FsiResumePolicyTest {

    private fun decide(
        settingEnabled: Boolean = true,
        journalSaysCameraBearing: Boolean = true,
        userStopped: Boolean = false,
        cameraAccessHeld: Boolean = false,
        alreadyPrompted: Boolean = false,
        canUseFullScreenIntent: Boolean = true,
    ) = FsiResumePolicy.decide(
        settingEnabled,
        journalSaysCameraBearing,
        userStopped,
        cameraAccessHeld,
        alreadyPrompted,
        canUseFullScreenIntent,
    )

    // ── the two POST kinds ──

    @Test
    fun `every gate passed with the full-screen grant posts the full-screen kind`() {
        val result = decide()
        assertEquals(FsiResumePolicy.Verdict.POST_FULL_SCREEN, result)
    }

    @Test
    fun `every gate passed without the full-screen grant posts the tap fallback kind`() {
        // Android 14+ Play installs have the FSI permission revoked at
        // install (fsi-limits): the prompt degrades to the high-priority,
        // content-tap-only notification — it must never SKIP, or the store
        // flavor would have no camera bring-up at all.
        val result = decide(canUseFullScreenIntent = false)
        assertEquals(FsiResumePolicy.Verdict.POST_TAP_FALLBACK, result)
    }

    // ── branch order: off > not-live > user-stopped > camera-held > prompted ──

    @Test
    fun `the setting off suppresses before every other gate`() {
        val result = decide(
            settingEnabled = false,
            journalSaysCameraBearing = false,
            userStopped = true,
            cameraAccessHeld = true,
            alreadyPrompted = true,
        )
        assertEquals(FsiResumePolicy.Verdict.SUPPRESS_OFF, result)
    }

    @Test
    fun `an all-off journal suppresses`() {
        val result = decide(journalSaysCameraBearing = false)
        assertEquals(FsiResumePolicy.Verdict.SUPPRESS_NOT_LIVE, result)
    }

    @Test
    fun `a web-only pre-death record is camera-bearing today and still prompts`() {
        // The journal's camera-bearing witness (fix C, "distinguish but keep
        // firing"): both StreamToggle kinds bind the camera through the
        // session's begin, so the journal writes `web || rtsp` into the
        // witness and a WEB-ONLY record reaches this policy as true — the
        // prompt fires for it (a web-only tap restores the camera with it).
        // The witness only drops when a future non-camera output kind clears
        // the persisted field, and then this gate suppresses.
        assertEquals(FsiResumePolicy.Verdict.POST_FULL_SCREEN, decide(journalSaysCameraBearing = true))
    }

    @Test
    fun `a recorded user-stopped intent suppresses`() {
        // The swipe-kill flag stands even though the journal still holds the
        // outputs (recordLiveOutputs preserves them) — nothing may nag about
        // a stream the user deliberately killed.
        val result = decide(userStopped = true, alreadyPrompted = true)
        assertEquals(FsiResumePolicy.Verdict.SUPPRESS_USER_STOPPED, result)
    }

    @Test
    fun `held camera access suppresses`() {
        // A live output owns the camera (boot resume already landed, or the
        // session survived): there is nothing to bring up — and the
        // locked-mount denial case belongs to LockBypassController's wake,
        // not this prompt.
        val result = decide(cameraAccessHeld = true, alreadyPrompted = true)
        assertEquals(FsiResumePolicy.Verdict.SUPPRESS_CAMERA_HELD, result)
    }

    @Test
    fun `an already-prompted death event suppresses`() {
        val result = decide(alreadyPrompted = true)
        assertEquals(FsiResumePolicy.Verdict.SUPPRESS_PROMPTED, result)
    }

    @Test
    fun `camera-held wins over already-prompted`() {
        val result = decide(cameraAccessHeld = true, alreadyPrompted = true)
        assertEquals(FsiResumePolicy.Verdict.SUPPRESS_CAMERA_HELD, result)
    }

    @Test
    fun `user-stopped wins over already-prompted`() {
        val result = decide(userStopped = true, alreadyPrompted = true)
        assertEquals(FsiResumePolicy.Verdict.SUPPRESS_USER_STOPPED, result)
    }

    @Test
    fun `not-live wins over user-stopped`() {
        val result = decide(journalSaysCameraBearing = false, userStopped = true)
        assertEquals(FsiResumePolicy.Verdict.SUPPRESS_NOT_LIVE, result)
    }
}
