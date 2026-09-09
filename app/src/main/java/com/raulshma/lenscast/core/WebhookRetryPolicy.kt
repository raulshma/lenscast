package com.raulshma.lenscast.core

/**
 * The pure webhook dispatch ladder: success verdict and the linear retry
 * backoff (2 s after the first failure, 4 s after the second — at most three
 * attempts per event). [WebhookNotifier] executes the ladder on its daemon
 * worker; an unreachable webhook still never blocks the detection path, it
 * just gets three fair shots per dispatch.
 */
object WebhookRetryPolicy {

    /** Total attempts per dispatch, first POST included. */
    const val MAX_ATTEMPTS = 3

    /** Linear backoff step: retry n waits n × [RETRY_DELAY_STEP_MS]. */
    const val RETRY_DELAY_STEP_MS = 2_000L

    /** 2xx is a delivered webhook; anything else (or a throw) is retryable. */
    fun isSuccessful(statusCode: Int): Boolean = statusCode in 200..299

    /** Whether another attempt may follow [attemptsSoFar] completed attempts. */
    fun shouldRetry(attemptsSoFar: Int): Boolean = attemptsSoFar < MAX_ATTEMPTS

    /**
     * Wait before retry number [retryNumber] (1-based): 2 s, then 4 s. An out
     * of-range retry number clamps into the ladder rather than inventing a
     * longer wait.
     */
    fun retryDelayMs(retryNumber: Int): Long =
        retryNumber.coerceIn(1, MAX_ATTEMPTS - 1) * RETRY_DELAY_STEP_MS
}
