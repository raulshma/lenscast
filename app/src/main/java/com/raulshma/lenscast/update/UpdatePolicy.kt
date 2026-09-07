package com.raulshma.lenscast.update

import com.raulshma.lenscast.update.model.GitHubAsset
import com.raulshma.lenscast.update.model.UpdateCheckResult

/**
 * What a caller should do after a completed update check: whether to surface
 * the result to the user and whether the check counts (advancing the 24h
 * auto-check clock). One decision for both the startup auto-check
 * (MainApplication) and the manual check (UpdateViewModel).
 */
data class UpdateDecision(
    val notify: Boolean,
    val saveLastCheck: Boolean,
)

/**
 * Pure update-check policy: version comparison, auto-check gating,
 * post-check decisions, dismissal checks, and the release-asset ladder
 * ([selectApkAsset]). No Android dependencies — unit-tested on the JVM.
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

    /**
     * The release-asset ladder: prefer a universal APK for ABI
     * compatibility, else the first `.apk` asset — null when the release
     * ships no APK. The extension match is exact-case; the universal
     * match is not.
     */
    fun selectApkAsset(assets: List<GitHubAsset>): GitHubAsset? {
        val universal = assets.firstOrNull {
            it.name.endsWith(".apk") && it.name.contains("universal", ignoreCase = true)
        }
        if (universal != null) return universal

        // Fallback to any APK
        return assets.firstOrNull { it.name.endsWith(".apk") }
    }

    /**
     * The one post-check policy, encoding exactly what both callers did:
     * a found update notifies unless the user dismissed that version (the
     * check still counts), an up-to-date result is silent but counts, and
     * RateLimited/Error are silent and do not count (the next check may run
     * again before 24h).
     */
    fun shouldNotifyAfterCheck(result: UpdateCheckResult, dismissedVersion: String?): UpdateDecision =
        when (result) {
            is UpdateCheckResult.UpdateAvailable -> UpdateDecision(
                notify = shouldNotify(dismissedVersion, result.release.tagName),
                saveLastCheck = true,
            )
            is UpdateCheckResult.UpToDate -> UpdateDecision(
                notify = false,
                saveLastCheck = true,
            )
            is UpdateCheckResult.RateLimited -> UpdateDecision(
                notify = false,
                saveLastCheck = false,
            )
            is UpdateCheckResult.Error -> UpdateDecision(
                notify = false,
                saveLastCheck = false,
            )
        }
}
