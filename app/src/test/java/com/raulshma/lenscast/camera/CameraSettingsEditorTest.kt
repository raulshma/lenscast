package com.raulshma.lenscast.camera

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.WhiteBalance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The persist-only editor: one normalized write per edit, the Settings
 * Applier applies it. The torch-touch verdict is NOT here — it lives in
 * CameraService.applySettings (the one seam that sees the previously
 * applied snapshot and the new write), so these tests pin the writer-side
 * shape only: normalization and parsing.
 */
class CameraSettingsEditorTest {

    @Test
    fun `edit persists the transform's output`() = runBlocking {
        var current = CameraSettings()
        var persisted: CameraSettings? = null
        val editor = CameraSettingsEditor(
            current = { current },
            persist = { persisted = it },
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
        )

        editor.edit { it.copy(whiteBalance = WhiteBalance.AUTO, focusMode = FocusMode.AUTO) }

        assertNull(persisted?.colorTemperature)
        assertNull(persisted?.focusDistance)
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
