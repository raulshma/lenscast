package com.raulshma.lenscast.update

/**
 * Pure update-check policy: version comparison, auto-check gating, and
 * dismissal checks. No Android dependencies — unit-tested on the JVM.
 *
 * Single owner of these decisions; [UpdateChecker], `MainApplication`, and
 * [UpdateViewModel] delegate instead of hand-rolling `trimStart('v')` and
 * timestamp math inline.
 */
object UpdatePolicy {

    private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /**
     * Strips surrounding whitespace and a leading 'v' (e.g. " v1.2.0 " -> "1.2.0").
     */
    fun normalize(version: String): String =
        version.trim().removePrefix("v")

    /**
     * Numeric dot-part comparison; non-numeric parts count as 0 and the
     * shorter version is padded with 0 ("1.2" == "1.2.0").
     */
    fun isNewer(remoteTag: String, localVersion: String): Boolean {
        val remoteParts = normalize(remoteTag).split('.').map { it.toIntOrNull() ?: 0 }
        val localParts = normalize(localVersion).split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    /**
     * True when an automatic check should run: enabled, and the last check is
     * older than 24h. Never-checked (lastCheckMs <= 0) always checks; a
     * future timestamp (clock moved) skips, matching the previous
     * `lastCheck > now - 24h` gate.
     */
    fun shouldAutoCheck(
        lastCheckMs: Long,
        autoCheckEnabled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!autoCheckEnabled) return false
        if (lastCheckMs <= 0L) return true
        return lastCheckMs <= nowMs - AUTO_CHECK_INTERVAL_MS
    }

    /**
     * True when the remote release should be surfaced: the user has not
     * dismissed this version. A null/blank dismissal never suppresses.
     * Both sides are normalized, so a dismissed "v1.2.0" matches remote "1.2.0".
     */
    fun shouldNotify(dismissedVersion: String?, remoteTag: String): Boolean {
        if (dismissedVersion.isNullOrBlank()) return true
        return normalize(dismissedVersion) != normalize(remoteTag)
    }
}
