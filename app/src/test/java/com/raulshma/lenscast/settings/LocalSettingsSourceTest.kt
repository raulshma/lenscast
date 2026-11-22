package com.raulshma.lenscast.settings

import com.raulshma.lenscast.camera.CameraService
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.capture.ml.AudioModelStore
import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.streaming.WebAuthGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * Event-sequence pass over the [LocalSettingsSource]'s two ordered write
 * funnels (the store mocked as disk-truth variables — the drains must fold
 * onto [SettingsDataStore.settingsFromDisk] / the editAuthSettings
 * transaction, never the shared flows). Pins: the camera chain's
 * submission-order fold (reset included — a Reset is the last-submitted
 * edit, not a racing bare write), the same-username auth edit's no-op (the
 * RTSP digest survives), and the auth drain's failure recovery (restore +
 * retry, revocation only on the committed save).
 */
class LocalSettingsSourceTest {

    private val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
    private val webAuthGate = mockk<WebAuthGate>(relaxed = true)

    /** The disk truth the drains must fold onto (the FromDisk reads / the auth transaction). */
    private var cameraOnDisk = CameraSettings()
    private var authOnDisk = StreamAuthSettings()

    private val savedCameraDocs = mutableListOf<CameraSettings>()
    private val savedAuthDocs = mutableListOf<StreamAuthSettings>()
    private var authSaveAttempts = 0
    private var failNextAuthSave = false

    private fun TestScope.launchSource(): LocalSettingsSource {
        coEvery { settingsDataStore.settingsFromDisk() } answers { cameraOnDisk }
        coEvery { settingsDataStore.saveSettings(any()) } answers {
            savedCameraDocs.add(firstArg())
        }
        coEvery { settingsDataStore.editAuthSettings(any()) } answers {
            authSaveAttempts += 1
            if (failNextAuthSave) {
                failNextAuthSave = false
                throw IOException("editAuthSettings failed")
            }
            val transform = firstArg<(StreamAuthSettings) -> StreamAuthSettings>()
            savedAuthDocs.add(transform(authOnDisk))
        }
        // The test scope itself, not backgroundScope: this coroutines-test
        // version never runs background-scope launches under
        // advanceUntilIdle, and a child-of-test launch doubles as the
        // no-crash pin — an uncaught drain exception fails the test.
        return LocalSettingsSource(
            settingsDataStore = settingsDataStore,
            cameraService = mockk(relaxed = true),
            powerManager = null,
            detectionModelStore = mockk(relaxed = true),
            audioModelStore = mockk(relaxed = true),
            webAuthGate = webAuthGate,
            scope = this,
        )
    }

    @Test
    fun `camera edits and reset fold in submission order into one write`() = runTest {
        val source = launchSource()

        // Neither drain has run yet — both transforms land in the pending
        // chain, and the fold must apply them in call order.
        source.updateFocusDistance(2f)
        source.resetToDefaults()
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsDataStore.settingsFromDisk() }
        coVerify(exactly = 1) { settingsDataStore.saveSettings(any()) }
        // The Reset was submitted last, so the batch's end state is the
        // defaults — the reset cannot be overtaken by an earlier edit, and
        // it rode the same mutex-guarded path (no bare write).
        check(savedCameraDocs.single() == CameraSettings())
    }

    @Test
    fun `reset then edit lands the edit on the defaults`() = runTest {
        val source = launchSource()

        source.resetToDefaults()
        source.updateFocusDistance(2f)
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsDataStore.saveSettings(any()) }
        check(savedCameraDocs.single() == CameraSettings(focusDistance = 2f))
    }

    @Test
    fun `last submitted camera edit wins the folded write`() = runTest {
        val source = launchSource()

        source.updateFocusDistance(1f)
        source.updateFocusDistance(2f)
        advanceUntilIdle()

        // One fold in submission order: the earlier 1f cannot overtake the
        // later 2f onto the disk.
        coVerify(exactly = 1) { settingsDataStore.saveSettings(any()) }
        check(savedCameraDocs.single() == CameraSettings(focusDistance = 2f))
    }

    @Test
    fun `resubmitting the current username keeps the settings and the RTSP digest`() = runTest {
        authOnDisk = StreamAuthSettings(
            enabled = true,
            username = "admin",
            passwordHash = "hash",
            rtspDigestHa1 = "ha1",
        )
        val source = launchSource()

        source.updateAuthUsername("admin")
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsDataStore.editAuthSettings(any()) }
        check(savedAuthDocs.single() == authOnDisk)
        // Nothing changed, so no session revocation either.
        coVerify(exactly = 0) { webAuthGate.revokeAllSessions() }
    }

    @Test
    fun `renaming the username drops the RTSP digest and revokes after the commit`() = runTest {
        authOnDisk = StreamAuthSettings(
            enabled = true,
            username = "admin",
            passwordHash = "hash",
            rtspDigestHa1 = "ha1",
        )
        val source = launchSource()

        source.updateAuthUsername("root")
        advanceUntilIdle()

        coVerify(exactly = 1) { settingsDataStore.editAuthSettings(any()) }
        check(savedAuthDocs.single() == authOnDisk.copy(username = "root", rtspDigestHa1 = ""))
        // The AuthWebHandler's order: save, then revoke on the transition.
        coVerifyOrder {
            settingsDataStore.editAuthSettings(any())
            webAuthGate.revokeAllSessions()
        }
    }

    @Test
    fun `failed auth save is restored and retried without crashing the scope`() = runTest {
        // A test that completes with an uncaught drain exception fails in
        // runTest — the pass below doubles as the no-app-scope-crash pin.
        authOnDisk = StreamAuthSettings(enabled = true, username = "admin", rtspDigestHa1 = "ha1")
        failNextAuthSave = true
        val source = launchSource()

        source.updateAuthUsername("root")
        advanceUntilIdle()

        // Attempt one failed; the restored batch was re-drained (virtual
        // time skips the retry delay) and committed.
        check(authSaveAttempts == 2)
        check(savedAuthDocs.single() == authOnDisk.copy(username = "root", rtspDigestHa1 = ""))
        // Revocation fired once — for the committed save, never for the
        // failed attempt (a failed save must not lock browsers out).
        coVerify(exactly = 1) { webAuthGate.revokeAllSessions() }
    }
}
