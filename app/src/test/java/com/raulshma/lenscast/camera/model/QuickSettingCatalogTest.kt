package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSettingCatalogTest {

    private val ranges = QuickSettingRanges(
        iso = 100..3200,
        zoom = 1f..10f,
        exposure = -12..12,
    )

    @Test
    fun `every quick setting type has exactly one descriptor`() {
        val types = QuickSettingCatalog.entries.map { it.type }
        assertEquals(QuickSettingType.entries.size, types.size)
        assertEquals(QuickSettingType.entries.toSet(), types.toSet())
    }

    @Test
    fun `descriptorFor returns the matching descriptor for every type`() {
        QuickSettingType.entries.forEach { type ->
            assertEquals(type, QuickSettingCatalog.descriptorFor(type).type)
        }
    }

    @Test
    fun `exposure pill shows the raw compensation value`() {
        val descriptor = QuickSettingCatalog.descriptorFor(QuickSettingType.EXPOSURE)
        assertEquals("0", descriptor.label(CameraSettings()))
        assertEquals("-2", descriptor.label(CameraSettings(exposureCompensation = -2)))
    }

    @Test
    fun `iso pill shows A when auto and the value when manual`() {
        val descriptor = QuickSettingCatalog.descriptorFor(QuickSettingType.ISO)
        assertEquals("A", descriptor.label(CameraSettings()))
        assertEquals("800", descriptor.label(CameraSettings(iso = 800)))
    }

    @Test
    fun `white balance pill shows AWB in auto`() {
        val descriptor = QuickSettingCatalog.descriptorFor(QuickSettingType.WHITE_BALANCE)
        assertEquals("AWB", descriptor.label(CameraSettings()))
    }

    @Test
    fun `white balance pill falls back to 5500K in manual without a temperature`() {
        val descriptor = QuickSettingCatalog.descriptorFor(QuickSettingType.WHITE_BALANCE)
        assertEquals(
            "${CameraSettings.DEFAULT_COLOR_TEMPERATURE_K}K",
            descriptor.label(CameraSettings(whiteBalance = WhiteBalance.MANUAL)),
        )
    }

    @Test
    fun `white balance pill shows the stored temperature in manual`() {
        val descriptor = QuickSettingCatalog.descriptorFor(QuickSettingType.WHITE_BALANCE)
        assertEquals(
            "3200K",
            descriptor.label(CameraSettings(whiteBalance = WhiteBalance.MANUAL, colorTemperature = 3200)),
        )
    }

    @Test
    fun `zoom pill formats one decimal with an x suffix`() {
        val descriptor = QuickSettingCatalog.descriptorFor(QuickSettingType.ZOOM)
        assertEquals("1.0x", descriptor.label(CameraSettings()))
        assertEquals("2.5x", descriptor.label(CameraSettings(zoomRatio = 2.5f)))
    }

    @Test
    fun `stabilization pill shows OIS or OFF`() {
        val descriptor = QuickSettingCatalog.descriptorFor(QuickSettingType.STABILIZATION)
        assertEquals("OIS", descriptor.label(CameraSettings(stabilization = true)))
        assertEquals("OFF", descriptor.label(CameraSettings(stabilization = false)))
    }

    @Test
    fun `resolution pill truncates the display name to five chars`() {
        val descriptor = QuickSettingCatalog.descriptorFor(QuickSettingType.RESOLUTION)
        assertEquals("FHD 1", descriptor.label(CameraSettings()))
    }

    @Test
    fun `focus frame rate hdr and night vision pill labels`() {
        assertEquals("AUT", QuickSettingCatalog.descriptorFor(QuickSettingType.FOCUS).label(CameraSettings()))
        assertEquals("24", QuickSettingCatalog.descriptorFor(QuickSettingType.FRAME_RATE).label(CameraSettings()))
        assertEquals("OFF", QuickSettingCatalog.descriptorFor(QuickSettingType.HDR).label(CameraSettings()))
        assertEquals(
            "IR",
            QuickSettingCatalog.descriptorFor(QuickSettingType.NIGHT_VISION)
                .label(CameraSettings(nightVisionMode = NightVisionMode.ON)),
        )
        assertEquals(
            "AUTO",
            QuickSettingCatalog.descriptorFor(QuickSettingType.NIGHT_VISION)
                .label(CameraSettings(nightVisionMode = NightVisionMode.AUTO)),
        )
    }

    @Test
    fun `sheet titles match the legacy headers`() {
        val expected = mapOf(
            QuickSettingType.EXPOSURE to "Exposure Compensation",
            QuickSettingType.ISO to "ISO",
            QuickSettingType.WHITE_BALANCE to "White Balance",
            QuickSettingType.FOCUS to "Focus Mode",
            QuickSettingType.ZOOM to "Zoom",
            QuickSettingType.HDR to "HDR",
            QuickSettingType.RESOLUTION to "Resolution",
            QuickSettingType.FRAME_RATE to "Frame Rate",
            QuickSettingType.STABILIZATION to "Stabilization",
            QuickSettingType.NIGHT_VISION to "Night Vision / IR",
        )
        expected.forEach { (type, title) ->
            assertEquals(title, QuickSettingCatalog.descriptorFor(type).title)
        }
    }

    @Test
    fun `night vision descriptions are the shared copy`() {
        assertEquals(
            "Forces night scene mode with maximum exposure and reduced frame rate for best low-light performance.",
            QuickSettingCatalog.nightVisionDescription(NightVisionMode.ON),
        )
        assertEquals(
            "Automatically adapts to lighting conditions using night portrait mode with auto flash.",
            QuickSettingCatalog.nightVisionDescription(NightVisionMode.AUTO),
        )
        assertEquals(
            "Standard camera behavior without low-light enhancements.",
            QuickSettingCatalog.nightVisionDescription(NightVisionMode.OFF),
        )
    }

    @Test
    fun `night vision descriptor carries the description and display labels`() {
        val descriptor = QuickSettingCatalog.descriptorFor(QuickSettingType.NIGHT_VISION)
        assertNotNull(descriptor.description)
        assertEquals(
            QuickSettingCatalog.nightVisionDescription(NightVisionMode.AUTO),
            descriptor.description!!.invoke(CameraSettings(nightVisionMode = NightVisionMode.AUTO)),
        )
        assertEquals("IR On", QuickSettingCatalog.nightVisionOptionLabel(NightVisionMode.ON.name))
        assertEquals("Auto", QuickSettingCatalog.nightVisionOptionLabel(NightVisionMode.AUTO.name))
        assertEquals("Off", QuickSettingCatalog.nightVisionOptionLabel(NightVisionMode.OFF.name))
    }

    @Test
    fun `editor kinds cover the catalog`() {
        val kinds = QuickSettingCatalog.entries.map { it.editor }
        assertTrue(kinds.any { it is QuickSettingEditor.Toggle })
        assertTrue(kinds.count { it is QuickSettingEditor.Chips } >= 5)
        assertTrue(kinds.count { it is QuickSettingEditor.Slider } == 3)
    }

    @Test
    fun `slider editors resolve ranges from the device ranges`() {
        val exposure = QuickSettingCatalog.descriptorFor(QuickSettingType.EXPOSURE)
            .editor as QuickSettingEditor.Slider
        assertEquals(-12f..12f, exposure.range(ranges))

        val frameRate = QuickSettingCatalog.descriptorFor(QuickSettingType.FRAME_RATE)
            .editor as QuickSettingEditor.Slider
        assertEquals(
            CameraSettings.FRAME_RATE_SLIDER_MIN.toFloat()..CameraSettings.FRAME_RATE_SLIDER_MAX.toFloat(),
            frameRate.range(ranges),
        )

        val zoom = QuickSettingCatalog.descriptorFor(QuickSettingType.ZOOM)
            .editor as QuickSettingEditor.Slider
        assertEquals(1f..10f, zoom.range(ranges))
    }

    @Test
    fun `iso chip options are the stops within the device range`() {
        val iso = QuickSettingCatalog.descriptorFor(QuickSettingType.ISO)
            .editor as QuickSettingEditor.Chips
        assertEquals(isoStops(100..3200), iso.options(ranges))
        assertEquals("Auto", iso.selected(CameraSettings()))
        assertEquals("400", iso.selected(CameraSettings(iso = 400)))
    }

    @Test
    fun `default color temperature constant is 5500`() {
        assertEquals(5500, CameraSettings.DEFAULT_COLOR_TEMPERATURE_K)
    }
}
