package com.raulshma.lenscast.companion.console

import androidx.camera.core.CameraSelector
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.LensLabelKind
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.companion.client.dto.ControlRange
import com.raulshma.lenscast.companion.client.dto.LensInfo
import com.raulshma.lenscast.companion.client.dto.StreamingStatus
import com.raulshma.lenscast.companion.session.ConnectionState
import com.raulshma.lenscast.core.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote console backend's status-mapping lockstep: every
 * [DeviceStatusMappers] verdict a dashboard render depends on, including the
 * fallbacks (null/inverted control ranges, unknown thermal names, missing
 * lens kinds) and the deliberate RECONNECTING → Ready choice. The mappings
 * reuse the local side's derivations (StreamStatusSnapshot, the
 * CameraSettings range constants), so these tests also pin the "one
 * derivation, two backends" contract: a drift here means the dashboard
 * renders differently over the remote backend than over the local one.
 */
class DeviceStatusMappersTest {

    // ── ConnectionState → CameraState ──

    @Test
    fun `connecting maps to initializing and connected maps to ready`() {
        assertEquals(CameraState.Initializing, DeviceStatusMappers.cameraState(ConnectionState.CONNECTING))
        assertEquals(CameraState.Ready, DeviceStatusMappers.cameraState(ConnectionState.CONNECTED))
    }

    @Test
    fun `reconnecting stays ready so the dashboard stays warm through backoff`() {
        // The session keeps polling status through the ladder's backoff, so
        // the snapshot is alive — the dashboard must not thrash to an error
        // state on every transient reconnect window.
        assertEquals(CameraState.Ready, DeviceStatusMappers.cameraState(ConnectionState.RECONNECTING))
    }

    @Test
    fun `idle maps to idle`() {
        assertEquals(CameraState.Idle, DeviceStatusMappers.cameraState(ConnectionState.IDLE))
    }

    @Test
    fun `offline maps to error with retry guidance and no taxonomy verdict`() {
        val state = DeviceStatusMappers.cameraState(ConnectionState.OFFLINE)
        assertTrue(state is CameraState.Error)
        state as CameraState.Error
        // Actionable: points at device power/network, then retry.
        assertTrue(state.message.contains("unreachable", ignoreCase = true))
        assertTrue(state.message.contains("retry", ignoreCase = true))
        assertNull(state.recovery)
    }

    @Test
    fun `auth failed maps to a distinct re-pair error`() {
        val state = DeviceStatusMappers.cameraState(ConnectionState.AUTH_FAILED)
        assertTrue(state is CameraState.Error)
        state as CameraState.Error
        // Distinct wording from OFFLINE: a retry cannot fix a rejected
        // session — the cue is re-pairing.
        assertTrue(state.message.contains("re-pair", ignoreCase = true))
        val offline = DeviceStatusMappers.cameraState(ConnectionState.OFFLINE) as CameraState.Error
        assertTrue(state.message != offline.message)
        assertNull(state.recovery)
    }

    // ── StreamingStatus → StreamStatus ──

    private fun streaming(
        isActive: Boolean = true,
        webActive: Boolean = true,
        clientCount: Int = 2,
        audioEnabled: Boolean = true,
        rtspActive: Boolean = false,
        webEnabled: Boolean = true,
        rtspEnabled: Boolean = false,
        whepClients: Int? = 5,
    ) = StreamingStatus(
        isActive = isActive,
        url = "http://192.168.1.10:8080/stream",
        webStreamingEnabled = webEnabled,
        webStreamingActive = webActive,
        clientCount = clientCount,
        audioEnabled = audioEnabled,
        audioUrl = "http://192.168.1.10:8080/audio",
        rtspEnabled = rtspEnabled,
        rtspStreamingActive = rtspActive,
        rtspUrl = "rtsp://192.168.1.10:8554/live",
        rtmpEnabled = true,
        rtmpActive = true,
        rtmpStatus = "connected",
        whipEnabled = false,
        srtEnabled = true,
        srtStatus = "connecting",
        srtRttMs = 42,
        whepClients = whepClients,
    )

    @Test
    fun `stream status maps through the shared snapshot builder`() {
        val status = DeviceStatusMappers.streamStatus(
            streaming(rtspActive = true, rtspEnabled = true, webEnabled = true),
        )
        assertEquals(true, status.isActive)
        assertEquals(true, status.isWebActive)
        assertEquals(true, status.isServerRunning)
        assertEquals("http://192.168.1.10:8080/stream", status.url)
        assertEquals(2, status.clientCount)
        assertEquals(true, status.isAudioActive)
        assertEquals("http://192.168.1.10:8080/audio", status.audioUrl)
        assertEquals(true, status.isRtspActive)
        assertEquals("rtsp://192.168.1.10:8554/live", status.rtspUrl)
        assertEquals(true, status.isWebEnabled)
        assertEquals(true, status.isRtspEnabled)
    }

    @Test
    fun `audio enablement stands in for the local active flag`() {
        assertEquals(false, DeviceStatusMappers.streamStatus(streaming(audioEnabled = false)).isAudioActive)
    }

    @Test
    fun `push outputs and whep clients do not leak into the dashboard model`() {
        // StreamStatus predates the push outputs; the local side's snapshot
        // drops them and so does this mapping. whepClients is a second viewer
        // lane and must NOT be summed into clientCount.
        val status = DeviceStatusMappers.streamStatus(streaming(clientCount = 2, whepClients = 9))
        assertEquals(2, status.clientCount)
    }

    // ── ControlRange → ranges ──

    @Test
    fun `live ranges map through raw`() {
        assertEquals(
            100..3200,
            DeviceStatusMappers.isoRange(ControlRange(min = 100.0, max = 3200.0)),
        )
        assertEquals(
            -12..12,
            DeviceStatusMappers.exposureCompensationRange(ControlRange(min = -12.0, max = 12.0)),
        )
        assertEquals(
            1f..8f,
            DeviceStatusMappers.zoomRange(ControlRange(min = 1.0, max = 8.0)),
        )
    }

    @Test
    fun `fractional integer bounds round to nearest without shrinking the span`() {
        assertEquals(1..9, DeviceStatusMappers.isoRange(ControlRange(min = 1.4, max = 8.6)))
    }

    @Test
    fun `null ranges fall back to the camera settings companion bounds`() {
        // The local side publishes these same assumed spans before the
        // device's live ranges arrive — the remote fallback mirrors it.
        assertEquals(
            CameraSettings.ISO_RANGE_MIN..CameraSettings.ISO_RANGE_MAX,
            DeviceStatusMappers.isoRange(null),
        )
        assertEquals(
            CameraSettings.EXPOSURE_COMPENSATION_MIN..CameraSettings.EXPOSURE_COMPENSATION_MAX,
            DeviceStatusMappers.exposureCompensationRange(null),
        )
        assertEquals(
            1f..CameraSettings.ZOOM_RATIO_MAX,
            DeviceStatusMappers.zoomRange(null),
        )
    }

    @Test
    fun `inverted ranges fall back instead of breaking the sliders`() {
        val inverted = ControlRange(min = 3200.0, max = 100.0)
        assertEquals(
            CameraSettings.ISO_RANGE_MIN..CameraSettings.ISO_RANGE_MAX,
            DeviceStatusMappers.isoRange(inverted),
        )
        assertEquals(
            CameraSettings.EXPOSURE_COMPENSATION_MIN..CameraSettings.EXPOSURE_COMPENSATION_MAX,
            DeviceStatusMappers.exposureCompensationRange(inverted),
        )
        assertEquals(
            1f..CameraSettings.ZOOM_RATIO_MAX,
            DeviceStatusMappers.zoomRange(ControlRange(min = 8.0, max = 1.0)),
        )
    }

    // ── Thermal name → ThermalState ──

    @Test
    fun `known platform thermal names map by value`() {
        for (state in ThermalState.entries) {
            assertEquals(state, DeviceStatusMappers.thermalState(state.name))
        }
    }

    @Test
    fun `unknown or blank thermal names fall back to normal`() {
        assertEquals(ThermalState.NORMAL, DeviceStatusMappers.thermalState("BOGUS"))
        assertEquals(ThermalState.NORMAL, DeviceStatusMappers.thermalState("normal"))
        assertEquals(ThermalState.NORMAL, DeviceStatusMappers.thermalState(""))
        assertEquals(ThermalState.NORMAL, DeviceStatusMappers.thermalState(null))
    }

    // ── LensInfo → MappedLens ──

    private fun lensInfo(
        index: Int = 0,
        id: String = "0",
        label: String = "Wide",
        kind: String? = "WIDE",
        focalLength: Double = 4.3,
        zoomRatio: Double? = null,
        isFront: Boolean = false,
        selected: Boolean = false,
    ) = LensInfo(
        index = index,
        id = id,
        label = label,
        kind = kind,
        focalLength = focalLength,
        zoomRatio = zoomRatio,
        isFront = isFront,
        selected = selected,
    )

    @Test
    fun `lens maps field by field`() {
        val mapped = DeviceStatusMappers.lens(
            lensInfo(id = "2", kind = "ZOOM", focalLength = 8.6, zoomRatio = 2.0),
        )
        assertEquals("2", mapped.id)
        assertEquals(LensLabelKind.ZOOM, mapped.labelKind)
        assertEquals(MappedLens.LENS_FACING_BACK, mapped.lensFacing)
        assertEquals(8.6f, mapped.focalLength)
        assertEquals(2f, mapped.zoomRatio)
        assertNull(mapped.physicalCameraId)
    }

    @Test
    fun `front lens carries the front facing constant`() {
        assertEquals(MappedLens.LENS_FACING_FRONT, DeviceStatusMappers.lens(lensInfo(isFront = true)).lensFacing)
        assertEquals(MappedLens.LENS_FACING_BACK, DeviceStatusMappers.lens(lensInfo(isFront = false)).lensFacing)
    }

    @Test
    fun `mirrored facing constants match the real camera selector constants`() {
        // The literals are mirrored so the pure layer never names the Android
        // class; pin the mirror here (test sources may import it) so a drift
        // in the androidx constants fails loudly.
        assertEquals(CameraSelector.LENS_FACING_FRONT, MappedLens.LENS_FACING_FRONT)
        assertEquals(CameraSelector.LENS_FACING_BACK, MappedLens.LENS_FACING_BACK)
    }

    @Test
    fun `null or unknown lens kind re-derives the band locally`() {
        // Old device without kind: the console bands by the same
        // LensInventory algorithm from isFront + focal.
        assertEquals(
            LensLabelKind.FRONT,
            DeviceStatusMappers.lens(lensInfo(kind = null, focalLength = 4.3, isFront = true)).labelKind,
        )
        assertEquals(
            LensLabelKind.WIDE,
            DeviceStatusMappers.lens(lensInfo(kind = null, focalLength = 4.3)).labelKind,
        )
        assertEquals(
            LensLabelKind.ULTRAWIDE,
            DeviceStatusMappers.lens(lensInfo(kind = null, focalLength = 2.2)).labelKind,
        )
        assertEquals(
            LensLabelKind.ZOOM,
            DeviceStatusMappers.lens(lensInfo(kind = null, focalLength = 8.6)).labelKind,
        )
        // An unknown future name must not crash the decode path either.
        assertEquals(
            LensLabelKind.WIDE,
            DeviceStatusMappers.lens(lensInfo(kind = "PERISCOPE", focalLength = 4.3)).labelKind,
        )
    }

    @Test
    fun `absent zoom ratio stays null`() {
        assertNull(DeviceStatusMappers.lens(lensInfo(zoomRatio = null)).zoomRatio)
    }

    // ── withLiveFacts ──

    @Test
    fun `live facts overlay torch and zoom onto the settings snapshot`() {
        // The unrelated fields carry NON-default values on purpose: a default
        // here would pass even if the overlay rebuilt the snapshot from
        // scratch instead of preserving the GET-derived document. Only
        // torchEnabled and zoomRatio are the overlay's writes; the stored
        // zoom (4.0f) must lose to the live status (1.75).
        val document = DeviceStatusMappers.withLiveFacts(
            CameraSettings(
                exposureCompensation = 2,
                iso = 800,
                focusMode = FocusMode.MACRO,
                whiteBalance = WhiteBalance.DAYLIGHT,
                zoomRatio = 4.0f,
                frameRate = 60,
                resolution = Resolution.UHD_4K,
                stabilization = false,
                hdrMode = HdrMode.AUTO,
            ),
            torchOn = true,
            zoomRatio = 1.75,
        )
        assertEquals(true, document.torchEnabled)
        assertEquals(1.75f, document.zoomRatio)
        // Everything else rides the document untouched.
        assertEquals(2, document.exposureCompensation)
        assertEquals(800, document.iso)
        assertEquals(FocusMode.MACRO, document.focusMode)
        assertEquals(WhiteBalance.DAYLIGHT, document.whiteBalance)
        assertEquals(60, document.frameRate)
        assertEquals(Resolution.UHD_4K, document.resolution)
        assertEquals(false, document.stabilization)
        assertEquals(HdrMode.AUTO, document.hdrMode)
    }
}
