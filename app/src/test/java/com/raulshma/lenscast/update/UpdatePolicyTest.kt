package com.raulshma.lenscast.update

import com.raulshma.lenscast.update.model.GitHubAsset
import com.raulshma.lenscast.update.model.GitHubRelease
import com.raulshma.lenscast.update.model.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `non-numeric suffix counts as zero`() {
        assertFalse(UpdatePolicy.isNewer("1.2.0-beta", "1.2.0"))
        assertTrue(UpdatePolicy.isNewer("1.3.0-beta", "1.2.0"))
        assertFalse(UpdatePolicy.isNewer("1.2.0", "1.2.0-beta"))
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
