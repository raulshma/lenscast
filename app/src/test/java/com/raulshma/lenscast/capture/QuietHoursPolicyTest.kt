package com.raulshma.lenscast.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursPolicyTest {

    @Test
    fun `disabled quiet hours never hold an alert`() {
        assertFalse(QuietHoursPolicy.isQuiet(false, 1320, 420, 1380))
        assertFalse(QuietHoursPolicy.isQuiet(false, 1320, 420, 100))
    }

    @Test
    fun `night window wraps midnight inclusively`() {
        // 22:00 → 07:00 (defaults).
        assertTrue(QuietHoursPolicy.isQuiet(true, 1320, 420, 1320))
        assertTrue(QuietHoursPolicy.isQuiet(true, 1320, 420, 1439))
        assertTrue(QuietHoursPolicy.isQuiet(true, 1320, 420, 0))
        assertTrue(QuietHoursPolicy.isQuiet(true, 1320, 420, 420))
        assertFalse(QuietHoursPolicy.isQuiet(true, 1320, 420, 421))
        assertFalse(QuietHoursPolicy.isQuiet(true, 1320, 420, 1319))
    }

    @Test
    fun `day window holds inside its bounds only`() {
        assertTrue(QuietHoursPolicy.isQuiet(true, 600, 700, 650))
        assertFalse(QuietHoursPolicy.isQuiet(true, 600, 700, 750))
    }

    @Test
    fun `equal start and end is degenerate and never quiet`() {
        assertFalse(QuietHoursPolicy.isQuiet(true, 600, 600, 600))
        assertFalse(QuietHoursPolicy.isQuiet(true, 600, 600, 1000))
    }
}
