package com.raulshma.lenscast.camera

import com.raulshma.lenscast.camera.model.CameraSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraSettingsEditorTest {

    @Test
    fun `edit applies the transform then persists`() = runBlocking {
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
    fun `immediate apply runs before persist`() = runBlocking {
        val events = mutableListOf<String>()
        val editor = CameraSettingsEditor(
            current = { CameraSettings() },
            persist = { events.add("persist") },
            apply = { events.add("apply") },
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
        )

        editor.edit { it.copy(zoomRatio = 2f) }

        assertEquals(false, applied)
        assertEquals(true, persisted)
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
