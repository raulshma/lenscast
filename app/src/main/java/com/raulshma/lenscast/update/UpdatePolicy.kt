package com.raulshma.lenscast.update

import com.raulshma.lenscast.update.model.GitHubAsset
import com.raulshma.lenscast.update.model.GitHubRelease
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
 * post-check decisions, dismissal checks, and the release/asset ladders
 * ([selectRelease], [selectApkAsset]). No Android dependencies —
 * unit-tested on the JVM.
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
     * Version precedence for release tags. The dot-separated numeric core
     * compares numerically (the shorter side padded with 0, so "1.2" ==
     * "1.2.0"), and a `-<pre>` suffix marks a pre-release that sorts BELOW
     * the same core without one. Pre-releases of the same core compare
     * identifier by identifier ("beta.2" > "beta.1"; a numeric identifier
     * sorts below an alphanumeric one, per SemVer).
     *
     * The beta channel relies on both halves: `0.2.3-beta.1` is newer than
     * stable `0.2.2` (the patch core moved), while stable `0.2.3` is newer
     * than its own `0.2.3-beta.1` (a beta never outranks its stable).
     */
    fun isNewer(remoteTag: String, localVersion: String): Boolean {
        val remote = parseVersion(remoteTag)
        val local = parseVersion(localVersion)

        val coreLength = maxOf(remote.core.size, local.core.size)
        for (i in 0 until coreLength) {
            val r = remote.core.getOrElse(i) { 0 }
            val l = local.core.getOrElse(i) { 0 }
            if (r != l) return r > l
        }

        // Same core: a stable release outranks any pre-release of that core.
        if (local.prerelease.isEmpty()) return false
        if (remote.prerelease.isEmpty()) return true

        val prereleaseLength = maxOf(remote.prerelease.size, local.prerelease.size)
        for (i in 0 until prereleaseLength) {
            val r = remote.prerelease.getOrNull(i) ?: return false
            val l = local.prerelease.getOrNull(i) ?: return true
            val comparison = comparePrereleaseIdentifier(r, l)
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    private data class ParsedVersion(val core: List<Int>, val prerelease: List<String>)

    private fun parseVersion(version: String): ParsedVersion {
        val normalized = normalize(version)
        val core = normalized.substringBefore('-')
        val prerelease = normalized.substringAfter('-', missingDelimiterValue = "")
        return ParsedVersion(
            core = core.split('.').map { it.toIntOrNull() ?: 0 },
            prerelease = if (prerelease.isEmpty()) emptyList() else prerelease.split('.'),
        )
    }

    private fun comparePrereleaseIdentifier(remote: String, local: String): Int {
        val remoteNumber = remote.toIntOrNull()
        val localNumber = local.toIntOrNull()
        return when {
            remoteNumber != null && localNumber != null -> remoteNumber.compareTo(localNumber)
            // SemVer: numeric identifiers always rank below alphanumeric ones.
            remoteNumber != null -> -1
            localNumber != null -> 1
            else -> remote.compareTo(local)
        }
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
     * The release the checker should consider: the highest-versioned entry
     * per [isNewer], skipping GitHub pre-releases unless [includePrereleases]
     * is set. API order is irrelevant — the version, not the publish date,
     * decides. Null when nothing qualifies (an empty list, or only
     * pre-releases with the beta channel off).
     */
    fun selectRelease(
        releases: List<GitHubRelease>,
        includePrereleases: Boolean,
    ): GitHubRelease? =
        releases
            .filter { includePrereleases || !it.prerelease }
            .reduceOrNull { best, candidate ->
                if (isNewer(candidate.tagName, best.tagName)) candidate else best
            }

    /**
     * The release-asset ladder: the APK built for the device's best
     * supported ABI (per-ABI releases carry a lower versionCode on every
     * other ABI, so a wrong-ABI pick reads as a downgrade and the installer
     * rejects it), else a universal APK for ABI compatibility, else the
     * first `.apk` asset — null when the release ships no APK. The
     * extension match is exact-case; the universal and ABI matches are not.
     * ABI names must match whole segments (`-<abi>-` or a `-<abi>.apk`
     * suffix) so `x86` never matches an `x86_64` asset.
     */
    fun selectApkAsset(assets: List<GitHubAsset>, supportedAbis: List<String> = emptyList()): GitHubAsset? {
        val apks = assets.filter { it.name.endsWith(".apk") }
        for (abi in supportedAbis) {
            apks.firstOrNull { it.name.contains("-$abi-", ignoreCase = true) || it.name.endsWith("-$abi.apk", ignoreCase = true) }
                ?.let { return it }
        }
        return apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apks.firstOrNull()
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
