package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.capture.ml.DetectionModelStore
import com.raulshma.lenscast.companion.client.dto.AuthConfigInfo
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.settings.SettingsCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings wave's pure mapping and verdict pins — the pieces that need
 * no client, no scope, no server:
 *
 * - the document's response-only model-lifecycle fields decode onto the
 *   screen's [DetectionModelStore.State] echo (all four wire verdicts);
 * - the redacted auth-config read maps onto [StreamAuthSettings] with the
 *   password NEVER crossing (empty hash, empty RTSP HA1 — the honest
 *   unknown), while the enabled state and username render;
 * - the capability tables: `local()` is what the local screens render today
 *   (all-true — the behavior-neutral invariant), and `remote()` hides exactly
 *   the wire-lacks-a-carrier / phone-only lanes while pinning the write gate
 *   to the roster role;
 * - the diagnostics page size stays the System tab's own constant's value
 *   (the tab's paging rule, single-homed).
 */
class RemoteSettingsMappingTest {

    // ── the model lifecycle decode ──

    @Test
    fun `the wire's model states decode onto the screen's state echo`() {
        assertEquals(
            DetectionModelStore.State.NotDownloaded,
            RemoteSettingsMap.toDetectionModelState("not_downloaded", -1.0, ""),
        )
        val downloading = RemoteSettingsMap.toDetectionModelState("downloading", 0.42, "")
        assertTrue(downloading is DetectionModelStore.State.Downloading)
        assertEquals(0.42f, (downloading as DetectionModelStore.State.Downloading).progress)
        // An in-flight download without progress rides the indeterminate -1.
        assertEquals(
            -1f,
            (RemoteSettingsMap.toDetectionModelState("downloading", -1.0, "") as DetectionModelStore.State.Downloading).progress,
        )
        val ready = RemoteSettingsMap.toDetectionModelState("ready", -1.0, "")
        assertTrue(ready is DetectionModelStore.State.Ready)
        val failed = RemoteSettingsMap.toDetectionModelState("failed", -1.0, "network down")
        assertEquals("network down", (failed as DetectionModelStore.State.Failed).reason)
        // An unknown future verdict decodes to the safe baseline, like every
        // other enum-by-name decode at this seam.
        assertEquals(
            DetectionModelStore.State.NotDownloaded,
            RemoteSettingsMap.toDetectionModelState("archived_on_the_moon", -1.0, ""),
        )
    }

    // ── the redacted auth mapping ──

    @Test
    fun `the redacted auth config maps with the password never crossing`() {
        val mapped = RemoteSettingsSource.toAuthSettings(
            AuthConfigInfo(
                enabled = true,
                username = "admin",
                password = "", // the server's write-only blank
                viewerEnabled = true,
                viewerUsername = "guest",
            ),
        )
        assertTrue(mapped.enabled)
        assertEquals("admin", mapped.username)
        assertEquals("guest", mapped.viewerUsername)
        // The hash NEVER round-trips: the mapped settings carry the honest
        // unknown, not a fake credential.
        assertEquals("", mapped.passwordHash)
        assertEquals("", mapped.rtspDigestHa1)
        assertNull(mapped.viewerPasswordHash)
    }

    @Test
    fun `an empty (auth-off or viewer-denied) config maps to the disabled default`() {
        val mapped = RemoteSettingsSource.toAuthSettings(AuthConfigInfo())
        assertFalse(mapped.enabled)
        assertEquals("", mapped.username)
    }

    // ── the capability tables ──

    @Test
    fun `the local capability set is what the screens render today`() {
        val local = SettingsCapabilities.local()
        assertTrue(local.canEdit)
        assertTrue(local.photoCaptureSection)
        assertTrue(local.selfUpdate)
        assertTrue(local.batteryOptimizationRow)
        assertTrue(local.resumeOnBootRow)
        assertTrue(local.fsiResumeRow)
        assertTrue(local.microphonePicker)
        assertTrue(local.livePublisherStatus)
        assertTrue(local.pushVapidSubjectField)
        assertTrue(local.securitySection)
    }

    @Test
    fun `the remote capability set hides exactly the phone-only lanes and gates writes by role`() {
        // An admin pairing: the wire-carried sections render…
        val admin = SettingsCapabilities.remote(canEdit = true)
        assertTrue(admin.canEdit)
        assertTrue(admin.securitySection) // /api/auth/config is an admin-only read
        // …and every phone-only / wire-lacks-a-carrier lane hides.
        assertFalse(admin.photoCaptureSection)
        assertFalse(admin.selfUpdate)
        assertFalse(admin.batteryOptimizationRow)
        assertFalse(admin.resumeOnBootRow)
        assertFalse(admin.fsiResumeRow)
        assertFalse(admin.microphonePicker)
        assertFalse(admin.livePublisherStatus)
        assertFalse(admin.pushVapidSubjectField)

        // A viewer pairing: same hides, plus the write gate and the admin-only
        // credential section fold shut.
        val viewer = SettingsCapabilities.remote(canEdit = false)
        assertFalse(viewer.canEdit)
        assertFalse(viewer.securitySection)
        assertFalse(viewer.photoCaptureSection)
    }

    // ── the diagnostics page rule ──

    @Test
    fun `the diagnostics audit page keeps the four-step page size`() {
        // Four 50-entry steps cover the device server's 200-entry audit cap
        // on its single-threaded lane — the companion System tab's page size,
        // pinned here now that the tab's own constant is gone.
        assertEquals(50, RemoteDiagnosticsSource.SYSTEM_AUDIT_PAGE_LIMIT)
    }

    @Test
    fun `the soft restart mode keeps its wire spelling`() {
        assertEquals("soft", RemoteDiagnosticsSource.RESTART_MODE_SOFT)
    }
}
