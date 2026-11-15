package com.raulshma.lenscast.companion.playback

/**
 * The MJPEG rung's error-budget retry policy, ported pure from
 * web/src/hooks/previewRetryPolicy.ts (which itself was extracted pure from
 * StreamPreview's MJPEG `<img>` onError/onLoad pair — "the sseHealth shape:
 * the browser primitives stay at the call site, and the decisions themselves
 * are vitest-pinned without one"). Same translation here: the caller owns
 * the transport and the timers, the budget owns the counts and the verdicts.
 *
 * Web contract being mirrored (StreamPreview.tsx onError):
 *  - each error bumps the count and hides the preview;
 *  - under budget, the retry backoff is `previewRetryDelayMs` — 2 s per
 *    error, capped at 4 errors' worth (8 s);
 *  - at [MAX_PREVIEW_ERRORS] the budget RESETS to zero and the caller
 *    demotes to the next rung when one exists, or keeps retrying the current
 *    rung after [NO_HLS_RETRY_MS] so the stream is never permanently stuck;
 *  - a successful load clears the count — it "must never accumulate across
 *    recovery windows" (StreamPreview onLoad).
 */
class RetryBudget(
    private val maxErrors: Int = MAX_PREVIEW_ERRORS,
    private val backoffStepMs: Long = RETRY_BACKOFF_STEP_MS,
    private val backoffCapErrors: Int = BACKOFF_CAP_ERRORS,
) {

    private var errors = 0

    /** Consecutive failures since the last success/exhaustion (test + driver introspection). */
    val errorCount: Int
        get() = errors

    /** A frame arrived: the budget clears, exactly like StreamPreview's `setPreviewErrorCount(0)` on load. */
    @Synchronized
    fun recordSuccess() {
        errors = 0
    }

    /**
     * One more failure. [RetryVerdict.RetryWithinRung] carries the
     * `previewRetryDelayMs` backoff; [RetryVerdict.Exhausted] carries the
     * count that triggered it and reports the budget already reset — the
     * web clears the count before the demote-vs-retry decision, so the next
     * rung (or the same rung when there is nothing to demote to) starts
     * fresh rather than inheriting the previous rung's errors.
     */
    @Synchronized
    fun recordError(): RetryVerdict {
        errors += 1
        if (errors < maxErrors) {
            return RetryVerdict.RetryWithinRung(retryDelayMs(errors, backoffStepMs, backoffCapErrors))
        }
        val exhaustedCount = errors
        errors = 0
        return RetryVerdict.Exhausted(errorCount = exhaustedCount)
    }

    sealed interface RetryVerdict {
        /** Stay on the rung; re-attempt after [delayMs] of backoff. */
        data class RetryWithinRung(val delayMs: Long) : RetryVerdict

        /**
         * The budget is spent: demote to the next ladder rung when one is
         * available, else keep retrying this rung after
         * [MAX_EXHAUSTED_RETRY_MS]. [errorCount] is the pre-reset count.
         */
        data class Exhausted(val errorCount: Int) : RetryVerdict
    }

    companion object {
        /** previewRetryPolicy.ts `MAX_PREVIEW_ERRORS` — five consecutive failures spend the budget. */
        const val MAX_PREVIEW_ERRORS = 5

        /**
         * previewRetryPolicy.ts `NO_HLS_RETRY_MS` — the inter-attempt delay
         * once the budget is spent and there is NO lower rung to demote to
         * ("keep the slow MJPEG auto-retry so the stream isn't permanently
         * stuck", StreamPreview.tsx).
         */
        const val MAX_EXHAUSTED_RETRY_MS = 15_000L

        /** previewRetryPolicy.ts: "2 s per error, capped at 4 errors' worth (8 s)". */
        const val RETRY_BACKOFF_STEP_MS = 2_000L
        const val BACKOFF_CAP_ERRORS = 4

        /** Mirror of `previewRetryDelayMs(errorCount)`: 2 s per error, capped at 8 s. */
        fun retryDelayMs(
            errorCount: Int,
            backoffStepMs: Long = RETRY_BACKOFF_STEP_MS,
            backoffCapErrors: Int = BACKOFF_CAP_ERRORS,
        ): Long = backoffStepMs * minOf(errorCount, backoffCapErrors)

        /**
         * Mirror of `shouldDemoteToHls(errorCount, hlsAvailable)`: "the
         * MJPEG rung is exhausted and an HLS rung exists to demote to" —
         * generalized to any lower rung on this ladder.
         */
        fun shouldDemoteToNextRung(errorCount: Int, lowerRungAvailable: Boolean): Boolean =
            errorCount >= MAX_PREVIEW_ERRORS && lowerRungAvailable
    }
}
