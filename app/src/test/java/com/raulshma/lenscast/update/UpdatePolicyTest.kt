package com.raulshma.lenscast.update

import com.raulshma.lenscast.update.model.GitHubAsset
import com.raulshma.lenscast.update.model.GitHubRelease
import com.raulshma.lenscast.update.model.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    // normalize

    @Test
    fun `normalize strips a leading v`() {
        assertEquals("1.2.0", UpdatePolicy.normalize("v1.2.0"))
        assertEquals("1.2.0", UpdatePolicy.normalize("1.2.0"))
    }

    @Test
    fun `normalize trims surrounding whitespace`() {
        assertEquals("1.2.0", UpdatePolicy.normalize("  v1.2.0  "))
        assertEquals("1.2.0", UpdatePolicy.normalize(" 1.2.0\n"))
    }

    // isNewer

    @Test
    fun `newer patch is newer`() {
        assertTrue(UpdatePolicy.isNewer("1.2.1", "1.2.0"))
    }

    @Test
    fun `newer minor and major are newer`() {
        assertTrue(UpdatePolicy.isNewer("1.3.0", "1.2.9"))
        assertTrue(UpdatePolicy.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `older remote is not newer`() {
        assertFalse(UpdatePolicy.isNewer("1.2.0", "1.2.1"))
        assertFalse(UpdatePolicy.isNewer("1.2.0", "2.0.0"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(UpdatePolicy.isNewer("1.2.0", "1.2.0"))
    }

    @Test
    fun `v prefix is ignored on either side`() {
        assertTrue(UpdatePolicy.isNewer("v1.2.1", "1.2.0"))
        assertTrue(UpdatePolicy.isNewer("v1.2.1", "v1.2.0"))
        assertFalse(UpdatePolicy.isNewer("v1.2.0", "v1.2.0"))
        assertFalse(UpdatePolicy.isNewer("1.2.0", "v1.2.1"))
    }

    @Test
    fun `different lengths pad with zero`() {
        assertFalse(UpdatePolicy.isNewer("1.2", "1.2.0"))
        assertFalse(UpdatePolicy.isNewer("1.2.0", "1.2"))
        assertTrue(UpdatePolicy.isNewer("1.2.1", "1.2"))
        assertFalse(UpdatePolicy.isNewer("1.2", "1.2.1"))
    }

    @Test
    fun `a pre-release suffix never outranks its stable core`() {
        assertFalse(UpdatePolicy.isNewer("1.2.0-beta", "1.2.0"))
        assertTrue(UpdatePolicy.isNewer("1.2.0", "1.2.0-beta"))
        assertTrue(UpdatePolicy.isNewer("1.3.0-beta", "1.2.0"))
    }

    @Test
    fun `a beta of the next patch is newer than the current stable`() {
        assertTrue(UpdatePolicy.isNewer("0.2.3-beta.1", "0.2.2"))
    }

    @Test
    fun `a stable release is newer than its own beta`() {
        assertTrue(UpdatePolicy.isNewer("0.2.3", "0.2.3-beta.1"))
        assertFalse(UpdatePolicy.isNewer("0.2.3-beta.1", "0.2.3"))
    }

    @Test
    fun `a newer beta counter is newer`() {
        assertTrue(UpdatePolicy.isNewer("0.2.3-beta.2", "0.2.3-beta.1"))
        assertTrue(UpdatePolicy.isNewer("0.2.3-beta.10", "0.2.3-beta.2"))
        assertFalse(UpdatePolicy.isNewer("0.2.3-beta.1", "0.2.3-beta.2"))
        assertFalse(UpdatePolicy.isNewer("0.2.3-beta.1", "0.2.3-beta.1"))
    }

    @Test
    fun `an installed beta is not offered the previous stable`() {
        assertFalse(UpdatePolicy.isNewer("0.2.2", "0.2.3-beta.1"))
    }

    // shouldAutoCheck

    @Test
    fun `disabled never auto-checks`() {
        val now = System.currentTimeMillis()
        assertFalse(UpdatePolicy.shouldAutoCheck(0L, false, now))
        assertFalse(UpdatePolicy.shouldAutoCheck(now - 48 * 3_600_000L, false, now))
    }

    @Test
    fun `recent check within 24h skips`() {
        val now = System.currentTimeMillis()
        assertFalse(UpdatePolicy.shouldAutoCheck(now - 3_600_000L, true, now))
        assertFalse(UpdatePolicy.shouldAutoCheck(now, true, now))
    }

    @Test
    fun `stale check older than 24h runs`() {
        val now = System.currentTimeMillis()
        assertTrue(UpdatePolicy.shouldAutoCheck(now - 25 * 3_600_000L, true, now))
    }

    @Test
    fun `never-checked runs when enabled`() {
        val now = System.currentTimeMillis()
        assertTrue(UpdatePolicy.shouldAutoCheck(0L, true, now))
    }

    @Test
    fun `future timestamp skips`() {
        val now = System.currentTimeMillis()
        assertFalse(UpdatePolicy.shouldAutoCheck(now + 3_600_000L, true, now))
    }

    // shouldNotify

    @Test
    fun `dismissed version suppresses the notification`() {
        assertFalse(UpdatePolicy.shouldNotify("1.2.0", "v1.2.0"))
        assertFalse(UpdatePolicy.shouldNotify("v1.2.0", "1.2.0"))
        assertFalse(UpdatePolicy.shouldNotify("1.2.0", "1.2.0"))
    }

    @Test
    fun `different dismissed version still notifies`() {
        assertTrue(UpdatePolicy.shouldNotify("1.1.0", "v1.2.0"))
    }

    @Test
    fun `null or blank dismissal notifies`() {
        assertTrue(UpdatePolicy.shouldNotify(null, "v1.2.0"))
        assertTrue(UpdatePolicy.shouldNotify("", "v1.2.0"))
        assertTrue(UpdatePolicy.shouldNotify("   ", "v1.2.0"))
    }

    // selectApkAsset

    private fun asset(name: String) = GitHubAsset(name, "https://example.invalid/$name", 1L)

    @Test
    fun `a universal apk wins over the other apks`() {
        val universal = asset("lenscast-2.0-universal.apk")
        val chosen = UpdatePolicy.selectApkAsset(
            listOf(asset("lenscast-2.0-arm64-v8a.apk"), universal, asset("notes.txt")),
        )
        assertEquals(universal, chosen)
    }

    @Test
    fun `an apk for a supported abi wins over universal and other abis`() {
        val arm64 = asset("lenscast-0.1.1-arm64-v8a-vc1012.apk")
        val chosen = UpdatePolicy.selectApkAsset(
            listOf(
                asset("lenscast-0.1.1-armeabi-v7a-vc1011.apk"),
                asset("lenscast-0.1.1-universal-vc1010.apk"),
                arm64,
            ),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertEquals(arm64, chosen)
    }

    @Test
    fun `abi preference follows the supported list order, not asset order`() {
        val arm64 = asset("lenscast-0.1.1-arm64-v8a-vc1012.apk")
        val chosen = UpdatePolicy.selectApkAsset(
            listOf(asset("lenscast-0.1.1-armeabi-v7a-vc1011.apk"), arm64),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertEquals(arm64, chosen)
    }

    @Test
    fun `an abi-suffixed apk name also matches`() {
        val chosen = UpdatePolicy.selectApkAsset(
            listOf(asset("lenscast-2.0-x86_64.apk")),
            supportedAbis = listOf("x86_64"),
        )
        assertEquals("lenscast-2.0-x86_64.apk", chosen?.name)
    }

    @Test
    fun `x86 does not match an x86_64 asset`() {
        val universal = asset("lenscast-0.1.1-universal-vc1010.apk")
        val chosen = UpdatePolicy.selectApkAsset(
            listOf(asset("lenscast-0.1.1-x86_64-vc1013.apk"), universal),
            supportedAbis = listOf("x86"),
        )
        // No whole-segment "-x86-" match, so the ladder falls past the
        // x86_64 asset to universal instead of an uninstallable APK.
        assertEquals(universal, chosen)
    }

    @Test
    fun `the universal match is case-insensitive`() {
        val chosen = UpdatePolicy.selectApkAsset(listOf(asset("LensCast-Universal.apk")))
        assertEquals("LensCast-Universal.apk", chosen?.name)
    }

    @Test
    fun `any apk is the fallback when no universal ships`() {
        val arm64 = asset("lenscast-arm64-v8a.apk")
        val chosen = UpdatePolicy.selectApkAsset(listOf(asset("checksums.txt"), arm64))
        assertEquals(arm64, chosen)
    }

    @Test
    fun `non-apk assets never win`() {
        assertNull(
            UpdatePolicy.selectApkAsset(
                listOf(asset("release-notes.txt"), asset("universal-blockmap.zip")),
            )
        )
    }

    @Test
    fun `a release with no apk yields null`() {
        assertNull(UpdatePolicy.selectApkAsset(emptyList()))
    }

    @Test
    fun `the apk extension match is exact-case`() {
        // Pinned: today's ladder matches ".apk" exactly, so an ".APK" asset
        // is not picked up — loosening that is a deliberate policy change.
        assertNull(UpdatePolicy.selectApkAsset(listOf(asset("lenscast-universal.APK"))))
    }

    // selectRelease

    private fun release(tag: String, prerelease: Boolean = false) = GitHubRelease(
        tagName = tag,
        name = "Release $tag",
        body = "notes",
        htmlUrl = "https://example.invalid",
        assets = listOf(asset("lenscast-universal.apk")),
        prerelease = prerelease,
    )

    @Test
    fun `stable selection ignores pre-releases`() {
        val stable = release("v1.2.0")
        val beta = release("v1.3.0-beta.1", prerelease = true)
        assertEquals(stable, UpdatePolicy.selectRelease(listOf(beta, stable), includePrereleases = false))
    }

    @Test
    fun `beta selection picks a newer pre-release`() {
        val stable = release("v1.2.0")
        val beta = release("v1.3.0-beta.1", prerelease = true)
        assertEquals(beta, UpdatePolicy.selectRelease(listOf(stable, beta), includePrereleases = true))
    }

    @Test
    fun `beta selection still prefers the stable that outranks its betas`() {
        val stable = release("v1.3.0")
        val beta = release("v1.3.0-beta.2", prerelease = true)
        assertEquals(stable, UpdatePolicy.selectRelease(listOf(beta, stable), includePrereleases = true))
    }

    @Test
    fun `beta selection falls back to the newest stable when no beta exists`() {
        val stable = release("v1.2.0")
        assertEquals(stable, UpdatePolicy.selectRelease(listOf(stable), includePrereleases = true))
    }

    @Test
    fun `release selection follows version, not API order`() {
        val newest = release("v2.0.0")
        val older = release("v1.9.0")
        assertEquals(newest, UpdatePolicy.selectRelease(listOf(newest, older), includePrereleases = false))
        assertEquals(newest, UpdatePolicy.selectRelease(listOf(older, newest), includePrereleases = false))
    }

    @Test
    fun `no eligible release yields null`() {
        assertNull(UpdatePolicy.selectRelease(emptyList(), includePrereleases = true))
        assertNull(
            UpdatePolicy.selectRelease(
                listOf(release("v1.3.0-beta.1", prerelease = true)),
                includePrereleases = false,
            )
        )
    }

    // shouldNotifyAfterCheck

    private fun updateAvailable(tag: String = "v1.2.0") = UpdateCheckResult.UpdateAvailable(
        release = GitHubRelease(
            tagName = tag,
            name = "Release $tag",
            body = "notes",
            htmlUrl = "https://example.invalid",
            assets = listOf(GitHubAsset("app.apk", "https://example.invalid/app.apk", 1L)),
        ),
        apkAsset = GitHubAsset("app.apk", "https://example.invalid/app.apk", 1L),
    )

    @Test
    fun `update available notifies and counts when not dismissed`() {
        val decision = UpdatePolicy.shouldNotifyAfterCheck(updateAvailable(), dismissedVersion = null)
        assertTrue(decision.notify)
        assertTrue(decision.saveLastCheck)
    }

    @Test
    fun `update available stays silent for the dismissed version but still counts`() {
        val decision = UpdatePolicy.shouldNotifyAfterCheck(updateAvailable("v1.2.0"), dismissedVersion = "1.2.0")
        assertFalse(decision.notify)
        assertTrue(decision.saveLastCheck)
    }

    @Test
    fun `update available notifies for a different dismissed version`() {
        val decision = UpdatePolicy.shouldNotifyAfterCheck(updateAvailable("v1.3.0"), dismissedVersion = "1.2.0")
        assertTrue(decision.notify)
        assertTrue(decision.saveLastCheck)
    }

    @Test
    fun `up to date is silent but counts`() {
        val decision = UpdatePolicy.shouldNotifyAfterCheck(
            UpdateCheckResult.UpToDate(remoteVersion = "1.0.0", localVersion = "1.0.0"),
            dismissedVersion = null,
        )
        assertFalse(decision.notify)
        assertTrue(decision.saveLastCheck)
    }

    @Test
    fun `rate limited is silent and does not count`() {
        val decision = UpdatePolicy.shouldNotifyAfterCheck(UpdateCheckResult.RateLimited, dismissedVersion = null)
        assertFalse(decision.notify)
        assertFalse(decision.saveLastCheck)
    }

    @Test
    fun `rate limited stays silent even with a stale dismissal`() {
        val decision = UpdatePolicy.shouldNotifyAfterCheck(UpdateCheckResult.RateLimited, dismissedVersion = "1.2.0")
        assertFalse(decision.notify)
        assertFalse(decision.saveLastCheck)
    }

    @Test
    fun `error is silent and does not count`() {
        val decision = UpdatePolicy.shouldNotifyAfterCheck(
            UpdateCheckResult.Error("Server returned HTTP 500"),
            dismissedVersion = null,
        )
        assertFalse(decision.notify)
        assertFalse(decision.saveLastCheck)
    }
}
