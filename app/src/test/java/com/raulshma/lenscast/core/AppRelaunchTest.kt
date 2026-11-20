package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relaunch-delay ordering invariant — the alarm must never fire while
 * the dying process still holds the socket the dashboard's OK is travelling
 * on. [AppRelaunch]'s `init` check enforces the ordering at first touch of
 * the object; these pins make a violating edit a test failure at the
 * constants instead of a crash at the composition root, and keep the values
 * (the quit ladder's pacing) from drifting silently.
 */
class AppRelaunchTest {

    @Test
    fun `the relaunch delay exceeds the response flush plus the teardown grace`() {
        assertTrue(
            "RELAUNCH_DELAY_MS must exceed RESTART_RESPONSE_FLUSH_MS + QUIT_TEARDOWN_GRACE_MS",
            AppRelaunch.RELAUNCH_DELAY_MS >
                AppRelaunch.RESTART_RESPONSE_FLUSH_MS + AppRelaunch.QUIT_TEARDOWN_GRACE_MS,
        )
    }

    @Test
    fun `touching the constants runs the object's ordering check`() {
        // The object's init asserts the ordering — initialization here is
        // the check running (a violated ordering throws before this returns),
        // and the asserted values pin the measured pacing.
        assertEquals(2_000L, AppRelaunch.RELAUNCH_DELAY_MS)
        assertEquals(300L, AppRelaunch.RESTART_RESPONSE_FLUSH_MS)
        assertEquals(500L, AppRelaunch.QUIT_TEARDOWN_GRACE_MS)
    }
}
