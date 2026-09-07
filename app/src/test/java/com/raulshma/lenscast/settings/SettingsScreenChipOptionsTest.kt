package com.raulshma.lenscast.settings

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.QuickSettingRanges
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.camera.model.chipLabel
import com.raulshma.lenscast.camera.model.isoStops
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parity pin: the settings screen's dropdown options and selected names are
 * derived from the Quick Setting Catalog's chips editors — the exact option
 * list the camera screen's sheet offers, and the exact name string the
 * catalog's write transform parses back.
 */
class SettingsScreenChipOptionsTest {

    private val ranges = QuickSettingRanges(
        iso = 100..3200,
        zoom = 1f..10f,
        exposure = -12..12,
    )

    @Test
    fun `focus mode options are the enum names and selected is the stored name`() {
        assertEquals(FocusMode.entries.map { it.name }, chipOptions(QuickSettingType.FOCUS, ranges))
        assertEquals(
            CameraSettings(focusMode = FocusMode.MANUAL).focusMode.name,
            chipSelected(QuickSettingType.FOCUS, CameraSettings(focusMode = FocusMode.MANUAL)),
        )
    }

    @Test
    fun `white balance options are the enum names`() {
        assertEquals(WhiteBalance.entries.map { it.name }, chipOptions(QuickSettingType.WHITE_BALANCE, ranges))
    }

    @Test
    fun `resolution options are the enum names`() {
        assertEquals(Resolution.entries.map { it.name }, chipOptions(QuickSettingType.RESOLUTION, ranges))
    }

    @Test
    fun `hdr options are the enum names`() {
        assertEquals(HdrMode.entries.map { it.name }, chipOptions(QuickSettingType.HDR, ranges))
    }

    @Test
    fun `night vision options are the enum names`() {
        assertEquals(NightVisionMode.entries.map { it.name }, chipOptions(QuickSettingType.NIGHT_VISION, ranges))
    }

    @Test
    fun `iso options are the stops within the device range and auto-selected when unset`() {
        assertEquals(isoStops(100..3200), chipOptions(QuickSettingType.ISO, ranges))
        assertEquals("Auto", chipSelected(QuickSettingType.ISO, CameraSettings()))
        assertEquals("400", chipSelected(QuickSettingType.ISO, CameraSettings(iso = 400)))
    }

    @Test
    fun `chip label is the catalog's underscore to space rule`() {
        assertEquals("UHD 4K", chipLabel("UHD_4K"))
        assertEquals("AUTO", chipLabel("AUTO"))
    }
}
