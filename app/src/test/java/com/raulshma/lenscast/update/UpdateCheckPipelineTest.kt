package com.raulshma.lenscast.update

import com.raulshma.lenscast.update.model.GitHubAsset
import com.raulshma.lenscast.update.model.GitHubRelease
import com.raulshma.lenscast.update.model.UpdateCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one update check pipeline under JVM fakes: the decision/persist/notify
 * sequence and the canonical silent RateLimited handling.
 */
class UpdateCheckPipelineTest {

    private class FakeStore(initiallyDismissed: String? = null) : UpdateCheckPipeline.Store {
        var dismissed: String? = initiallyDismissed
        var savedLastCheckTime: Long? = null

        override suspend fun dismissedVersion(): String = dismissed ?: ""

        override suspend fun saveLastCheckTime(timeMs: Long) {
            savedLastCheckTime = timeMs
        }
    }

    private class FakeNotifier : UpdateCheckPipeline.Notifier {
        var shownVersion: String? = null
        override fun showUpdateAvailable(version: String) {
            shownVersion = version
        }
    }

    private fun pipeline(
        result: UpdateCheckResult,
        store: FakeStore = FakeStore(),
        notifier: FakeNotifier = FakeNotifier(),
        nowMs: Long = 1_000_000L,
    ): UpdateCheckPipeline =
        UpdateCheckPipeline(
            checker = { result },
            store = store,
            notifier = notifier,
            nowMs = { nowMs },
        )

    private fun release(tag: String) = GitHubRelease(
        tagName = tag,
        name = "Release $tag",
        body = "Notes",
        htmlUrl = "https://example.com",
        assets = listOf(
            GitHubAsset("app-universal.apk", "https://example.com/app-universal.apk", 1024L)
        ),
    )

    @Test
    fun `a fresh update notifies and advances the check clock`() = runBlocking {
        val store = FakeStore()
        val notifier = FakeNotifier()
        val outcome = pipeline(UpdateCheckResult.UpdateAvailable(release("v1.2.0"), release("v1.2.0").assets.first()), store, notifier)
            .runCheck()

        val available = outcome as UpdateCheckPipeline.UpdateOutcome.UpdateAvailable
        assertEquals("1.2.0", available.version)
        assertEquals("Notes", available.releaseNotes)
        assertTrue(available.notified)
        assertEquals("1.2.0", notifier.shownVersion)
        assertEquals(1_000_000L, store.savedLastCheckTime)
    }

    @Test
    fun `a dismissed version stays silent but still advances the check clock`() = runBlocking {
        val store = FakeStore(initiallyDismissed = "v1.2.0")
        val notifier = FakeNotifier()
        val result = UpdateCheckResult.UpdateAvailable(release("v1.2.0"), release("v1.2.0").assets.first())
        val outcome = pipeline(result, store, notifier).runCheck()

        val upToDate = outcome as UpdateCheckPipeline.UpdateOutcome.UpToDate
        assertEquals("1.2.0", upToDate.remoteVersion)
        assertNull(notifier.shownVersion)
        assertEquals(1_000_000L, store.savedLastCheckTime)
    }

    @Test
    fun `up to date is silent and advances the check clock`() = runBlocking {
        val store = FakeStore()
        val notifier = FakeNotifier()
        val outcome = pipeline(UpdateCheckResult.UpToDate("1.0.0", "1.2.0"), store, notifier).runCheck()

        assertEquals(UpdateCheckPipeline.UpdateOutcome.UpToDate("1.0.0"), outcome)
        assertNull(notifier.shownVersion)
        assertEquals(1_000_000L, store.savedLastCheckTime)
    }

    @Test
    fun `rate limited is fully silent and does not advance the check clock`() = runBlocking {
        val store = FakeStore()
        val notifier = FakeNotifier()
        val outcome = pipeline(UpdateCheckResult.RateLimited, store, notifier).runCheck()

        assertEquals(UpdateCheckPipeline.UpdateOutcome.RateLimited, outcome)
        assertNull(notifier.shownVersion)
        assertNull(store.savedLastCheckTime)
    }

    @Test
    fun `errors are silent, do not advance the clock, and carry the message`() = runBlocking {
        val store = FakeStore()
        val notifier = FakeNotifier()
        val outcome = pipeline(UpdateCheckResult.Error("boom"), store, notifier).runCheck()

        assertEquals(UpdateCheckPipeline.UpdateOutcome.Error("boom"), outcome)
        assertNull(notifier.shownVersion)
        assertNull(store.savedLastCheckTime)
    }

    @Test
    fun `the pipeline normalizes the v prefix before notifying`() = runBlocking {
        val notifier = FakeNotifier()
        val result = UpdateCheckResult.UpdateAvailable(release(" v2.0.0 "), release(" v2.0.0 ").assets.first())
        pipeline(result, FakeStore(), notifier).runCheck()
        assertEquals("2.0.0", notifier.shownVersion)
        assertFalse(notifier.shownVersion!!.startsWith("v"))
    }
}
