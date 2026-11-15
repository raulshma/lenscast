package com.raulshma.lenscast.companion.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.raulshma.lenscast.companion.playback.RetryBudget.RetryVerdict
import org.junit.Test

/** Pins the port of web/src/hooks/previewRetryPolicy.ts (verbatim constants and verdicts). */
class RetryBudgetTest {

    private val budget = RetryBudget()

    @Test
    fun `under budget the backoff escalates two seconds per error capped at eight`() {
        assertEquals(2_000L, RetryBudget.retryDelayMs(1))
        assertEquals(4_000L, RetryBudget.retryDelayMs(2))
        assertEquals(6_000L, RetryBudget.retryDelayMs(3))
        assertEquals(8_000L, RetryBudget.retryDelayMs(4))
        assertEquals("capped at 4 errors' worth", 8_000L, RetryBudget.retryDelayMs(5))
    }

    @Test
    fun `recordError returns escalating in-rung retries under budget`() {
        val first = budget.recordError()
        assertTrue(first is RetryVerdict.RetryWithinRung)
        assertEquals(2_000L, (first as RetryVerdict.RetryWithinRung).delayMs)

        val second = budget.recordError()
        assertEquals(4_000L, (second as RetryVerdict.RetryWithinRung).delayMs)
        assertEquals(2, budget.errorCount)
    }

    @Test
    fun `the fifth error exhausts the budget and resets it for the next rung`() {
        repeat(4) { budget.recordError() }
        val fifth = budget.recordError()
        assertTrue(fifth is RetryVerdict.Exhausted)
        assertEquals(5, (fifth as RetryVerdict.Exhausted).errorCount)
        assertEquals("the web clears the count before the demote decision", 0, budget.errorCount)
    }

    @Test
    fun `exhaustion repeats every five errors not once ever`() {
        repeat(5) { budget.recordError() }
        repeat(4) { assertTrue(budget.recordError() is RetryVerdict.RetryWithinRung) }
        assertTrue(budget.recordError() is RetryVerdict.Exhausted)
    }

    @Test
    fun `a success clears the count so errors never accumulate across recovery`() {
        budget.recordError()
        budget.recordError()
        budget.recordSuccess()
        assertEquals(0, budget.errorCount)
        val next = budget.recordError()
        assertEquals(2_000L, (next as RetryVerdict.RetryWithinRung).delayMs)
    }

    @Test
    fun `demotion verdict mirrors shouldDemoteToHls`() {
        assertTrue(RetryBudget.shouldDemoteToNextRung(errorCount = 5, lowerRungAvailable = true))
        assertFalse(RetryBudget.shouldDemoteToNextRung(errorCount = 5, lowerRungAvailable = false))
        assertFalse(RetryBudget.shouldDemoteToNextRung(errorCount = 4, lowerRungAvailable = true))
    }

    @Test
    fun `custom budget sizing keeps the same escalation shape`() {
        val tight = RetryBudget(maxErrors = 2, backoffStepMs = 100L, backoffCapErrors = 1)
        val first = tight.recordError()
        assertTrue(first is RetryVerdict.RetryWithinRung)
        assertEquals(100L, (first as RetryVerdict.RetryWithinRung).delayMs)
        assertTrue(tight.recordError() is RetryVerdict.Exhausted)
        assertEquals(0, tight.errorCount)
    }
}
