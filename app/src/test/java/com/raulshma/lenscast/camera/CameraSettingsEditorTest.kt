package com.raulshma.lenscast.camera

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.WhiteBalance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettingsEditorTest {

    @Test
    fun `edit applies the transform then persists`() = runBlocking {
        var current = CameraSettings()
        var persisted: CameraSettings? = null
        val editor = CameraSettingsEditor(
            current = { current },
            persist = { persisted = it },
            liveTorchOn = { false },
        )

        editor.edit { it.copy(exposureCompensation = 3) }

        assertEquals(3, persisted?.exposureCompensation)
    }

    @Test
    fun `manual white balance without a kelvin gets the default temperature`() = runBlocking {
        var persisted: CameraSettings? = null
        val editor = CameraSettingsEditor(
            current = { CameraSettings() },
            persist = { persisted = it },
            liveTorchOn = { false },
        )

        editor.edit { it.copy(whiteBalance = WhiteBalance.MANUAL) }

        assertEquals(
            CameraSettings.DEFAULT_COLOR_TEMPERATURE_K,
            persisted?.colorTemperature,
        )
        assertEquals(WhiteBalance.MANUAL, persisted?.whiteBalance)
    }

    @Test
    fun `manual focus without a distance gets the neutral infinity default`() = runBlocking {
        var persisted: CameraSettings? = null
        val editor = CameraSettingsEditor(
            current = { CameraSettings() },
            persist = { persisted = it },
            liveTorchOn = { false },
        )

        editor.edit { it.copy(focusMode = FocusMode.MANUAL) }

        assertEquals(0f, persisted?.focusDistance)
        assertEquals(FocusMode.MANUAL, persisted?.focusMode)
    }

    @Test
    fun `manual axes keep their explicit values`() = runBlocking {
        var persisted: CameraSettings? = null
        val editor = CameraSettingsEditor(
            current = { CameraSettings() },
            persist = { persisted = it },
            liveTorchOn = { false },
        )

        editor.edit {
            it.copy(
                whiteBalance = WhiteBalance.MANUAL,
                colorTemperature = 3200,
                focusMode = FocusMode.MANUAL,
                focusDistance = 2.5f,
            )
        }

        assertEquals(3200, persisted?.colorTemperature)
        assertEquals(2.5f, persisted?.focusDistance)
    }

    @Test
    fun `non-manual axes are untouched by the normalization`() = runBlocking {
        var persisted: CameraSettings? = null
        val editor = CameraSettingsEditor(
            current = { CameraSettings() },
            persist = { persisted = it },
            liveTorchOn = { false },
        )

        editor.edit { it.copy(whiteBalance = WhiteBalance.AUTO, focusMode = FocusMode.AUTO) }

        assertNull(persisted?.colorTemperature)
        assertNull(persisted?.focusDistance)
    }

    @Test
    fun `immediate apply runs before persist`() = runBlocking {
        val events = mutableListOf<String>()
        val editor = CameraSettingsEditor(
            current = { CameraSettings() },
            persist = { events.add("persist") },
            apply = { events.add("apply") },
            liveTorchOn = { false },
        )

        editor.edit { it }

        assertEquals(listOf("apply", "persist"), events)
    }

    @Test
    fun `persist-only mode skips apply`() = runBlocking {
        var applied = false
        var persisted = false
        val editor = CameraSettingsEditor(
            current = { CameraSettings() },
            persist = { persisted = true },
            apply = null,
            liveTorchOn = { false },
        )

        editor.edit { it.copy(zoomRatio = 2f) }

        assertEquals(false, applied)
        assertEquals(true, persisted)
    }

    // ── torch convergence (issue #16): the applier's re-assert must not ──
    // ── carry stale stored intent over a live-only lit LED             ──

    @Test
    fun `a transform that leaves the torch alone converges it to the live fact`() = runBlocking {
        var current = CameraSettings(torchEnabled = false)
        var applied: CameraSettings? = null
        var persisted: CameraSettings? = null
        val editor = CameraSettingsEditor(
            current = { current },
            persist = { persisted = it },
            apply = { applied = it },
            liveTorchOn = { true },
        )

        editor.edit { it.copy(iso = 800) }

        // Both halves of the write see the converged value: the immediate
        // apply drives the torch from it, and the persisted copy re-asserts
        // the same fact through the applier later.
        assertTrue(applied?.torchEnabled == true)
        assertTrue(persisted?.torchEnabled == true)
        assertEquals(800, persisted?.iso)
    }

    @Test
    fun `a transform that writes the torch explicitly passes through verbatim`() = runBlocking {
        var current = CameraSettings(torchEnabled = false)
        var persisted: CameraSettings? = null
        val editor = CameraSettingsEditor(
            current = { current },
            persist = { persisted = it },
            liveTorchOn = { true },
        )

        editor.edit { it.copy(torchEnabled = true) }

        // The torch toggle's write is the torch verdict — the live fact does
        // not override it.
        assertTrue(persisted?.torchEnabled == true)
    }

    @Test
    fun `an untouched torch converges to dark when the LED is dark`() = runBlocking {
        var current = CameraSettings(torchEnabled = true)
        var persisted: CameraSettings? = null
        val editor = CameraSettingsEditor(
            current = { current },
            persist = { persisted = it },
            liveTorchOn = { false },
        )

        editor.edit { it.copy(iso = 800) }

        // The opposite divergence: stored intent says on, the LED is dark —
        // the save must not re-light it.
        assertTrue(persisted?.torchEnabled == false)
    }

    @Test
    fun `iso label parsing matches both screens`() {
        assertNull(CameraSettingsEditor.parseIso("Auto"))
        assertEquals(400, CameraSettingsEditor.parseIso("400"))
        assertNull(CameraSettingsEditor.parseIso("grainy"))
    }

    @Test
    fun `scene mode off clears the override`() {
        assertNull(CameraSettingsEditor.parseSceneMode("OFF"))
        assertEquals("NIGHT", CameraSettingsEditor.parseSceneMode("NIGHT"))
    }
}
