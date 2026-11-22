package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.FocusMode
import com.raulshma.lenscast.camera.model.HdrMode
import com.raulshma.lenscast.camera.model.NightVisionMode
import com.raulshma.lenscast.camera.model.Resolution
import com.raulshma.lenscast.camera.model.WhiteBalance
import com.raulshma.lenscast.companion.client.dto.RemoteCameraSettings
import com.raulshma.lenscast.companion.client.dto.SettingsUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings-seam lockstep for the remote console: wire → settings field
 * coverage (all 16 camera-section fields), the patch direction's diff
 * discipline (only carried changes emit; torch/zoom never ride settings;
 * the section travels whole), the per-field patch check (one edit per
 * carried row must surface exactly that row and ride the PUT), the stable
 * unmapped/excluded enumerations, and
 * the round-trip no-op invariant (wire → settings → patch of an unchanged
 * snapshot must refuse to PUT). The wire names in these assertions are the
 * pinned contract — they mirror web/contract/settings.json and the server's
 * own toCameraDto transcription.
 */
class RemoteSettingsMapperTest {

    /** A document exercising every camera-section wire field. */
    private fun wire(
        exposureCompensation: Int = 2,
        iso: Int? = 800,
        exposureTime: Long? = 10_000_000L,
        focusMode: String = "MANUAL",
        focusDistance: Double? = 5.0,
        whiteBalance: String = "MANUAL",
        colorTemperature: Int? = 5500,
        zoomRatio: Double = 1.75,
        frameRate: Int = 30,
        resolution: String = "UHD_4K",
        stabilization: Boolean = false,
        hdrMode: String = "ON",
        sceneMode: String? = "night",
        nightVisionMode: String = "AUTO",
        outputRotation: Int = 90,
        orientationLocked: Boolean = true,
    ) = RemoteCameraSettings(
        exposureCompensation = exposureCompensation,
        iso = iso,
        exposureTime = exposureTime,
        focusMode = focusMode,
        focusDistance = focusDistance,
        whiteBalance = whiteBalance,
        colorTemperature = colorTemperature,
        zoomRatio = zoomRatio,
        frameRate = frameRate,
        resolution = resolution,
        stabilization = stabilization,
        hdrMode = hdrMode,
        sceneMode = sceneMode,
        nightVisionMode = nightVisionMode,
        outputRotation = outputRotation,
        orientationLocked = orientationLocked,
    )

    // ── wire → settings ──

    @Test
    fun `every camera-section wire field lands in the settings snapshot`() {
        val settings = RemoteSettingsMapper.toCameraSettings(wire())
        assertEquals(2, settings.exposureCompensation)
        assertEquals(800, settings.iso)
        assertEquals(10_000_000L, settings.exposureTime)
        assertEquals(FocusMode.MANUAL, settings.focusMode)
        assertEquals(5.0f, settings.focusDistance)
        assertEquals(WhiteBalance.MANUAL, settings.whiteBalance)
        assertEquals(5500, settings.colorTemperature)
        assertEquals(1.75f, settings.zoomRatio)
        assertEquals(30, settings.frameRate)
        assertEquals(Resolution.UHD_4K, settings.resolution)
        assertEquals(false, settings.stabilization)
        assertEquals(HdrMode.ON, settings.hdrMode)
        assertEquals("night", settings.sceneMode)
        assertEquals(NightVisionMode.AUTO, settings.nightVisionMode)
        assertEquals(90, settings.outputRotation)
        assertEquals(true, settings.orientationLocked)
    }

    @Test
    fun `optional absences decode to nulls`() {
        val settings = RemoteSettingsMapper.toCameraSettings(
            wire(iso = null, exposureTime = null, focusDistance = null, colorTemperature = null, sceneMode = null),
        )
        assertNull(settings.iso)
        assertNull(settings.exposureTime)
        assertNull(settings.focusDistance)
        assertNull(settings.colorTemperature)
        assertNull(settings.sceneMode)
    }

    @Test
    fun `unknown enum names fall back to the camera settings defaults`() {
        val settings = RemoteSettingsMapper.toCameraSettings(
            wire(
                focusMode = "EDOF",
                whiteBalance = "TWILIGHT",
                resolution = "8K",
                hdrMode = "DOLBY",
                nightVisionMode = "GRAYSCALE",
            ),
        )
        assertEquals(FocusMode.AUTO, settings.focusMode)
        assertEquals(WhiteBalance.AUTO, settings.whiteBalance)
        assertEquals(Resolution.FHD_1080P, settings.resolution)
        assertEquals(HdrMode.OFF, settings.hdrMode)
        assertEquals(NightVisionMode.OFF, settings.nightVisionMode)
    }

    // ── settings → wire section (round-trip) ──

    @Test
    fun `wire to settings to wire is value-stable`() {
        val document = wire()
        val rebuilt = RemoteSettingsMapper.toRemoteSection(
            RemoteSettingsMapper.toCameraSettings(document),
        )
        assertEquals(document, rebuilt)
    }

    @Test
    fun `settings default document round-trips against the wire defaults`() {
        val rebuilt = RemoteSettingsMapper.toRemoteSection(CameraSettings())
        assertEquals(RemoteCameraSettings(), rebuilt)
    }

    // ── the patch direction ──

    @Test
    fun `unchanged snapshot refuses to put`() {
        val settings = RemoteSettingsMapper.toCameraSettings(wire())
        assertNull(RemoteSettingsMapper.toPatch(settings, settings))
    }

    @Test
    fun `delta emits only the camera section and leaves streaming untouched`() {
        val previous = RemoteSettingsMapper.toCameraSettings(wire())
        val next = previous.copy(exposureCompensation = 5)
        val patch = RemoteSettingsMapper.toPatch(previous, next) as SettingsUpdate
        // The camera section travels WHOLE (the server's wholesale-persist
        // rule); the streaming section stays null so the server leaves it.
        assertEquals(RemoteSettingsMapper.toRemoteSection(next), patch.camera)
        assertNull(patch.streaming)
    }

    @Test
    fun `untouched carried fields ride at their current values`() {
        val previous = RemoteSettingsMapper.toCameraSettings(wire())
        val next = previous.copy(frameRate = 60)
        val patch = RemoteSettingsMapper.toPatch(previous, next)!!
        // Every carried field except the edit equals the previous (device
        // reported) value — the section is a whole document, not a partial.
        assertEquals(previous.iso, patch.camera!!.iso)
        assertEquals(previous.resolution.name, patch.camera!!.resolution)
        assertEquals(60, patch.camera!!.frameRate)
    }

    @Test
    fun `zoom-only change never emits a put`() {
        // Live zoom rides POST /api/camera/zoom — a settings PUT would
        // double-write a fact another endpoint owns.
        val previous = RemoteSettingsMapper.toCameraSettings(wire())
        val next = previous.copy(zoomRatio = 4f)
        assertNull(RemoteSettingsMapper.toPatch(previous, next))
    }

    @Test
    fun `torch-only changes never emit a put`() {
        val previous = RemoteSettingsMapper.toCameraSettings(wire())
        assertNull(RemoteSettingsMapper.toPatch(previous, previous.copy(torchEnabled = true)))
        assertNull(RemoteSettingsMapper.toPatch(previous, previous.copy(torchStrength = 7)))
    }

    @Test
    fun `excluded fields are pinned and never smuggled into the section`() {
        val previous = RemoteSettingsMapper.toCameraSettings(wire())
        val next = previous.copy(exposureCompensation = -3, zoomRatio = 6f, torchEnabled = true)
        val patch = RemoteSettingsMapper.toPatch(previous, next)!!
        // The carried edit lands…
        assertEquals(-3, patch.camera!!.exposureCompensation)
        // …while zoom is pinned to the device-reported value, not the
        // console's edit, and torch has no wire field to leak into.
        assertEquals(1.75, patch.camera!!.zoomRatio, 0.0)
    }

    // ── the per-field patch check ──

    @Test
    fun `every carried field's edit surfaces its own row and rides the patch`() {
        // One edit per diff-table row: exactly THAT row must report the
        // change (a missing row, or one extracting the wrong field, would
        // emit no PUT for its field's edit — the round-trip no-op cannot see
        // either), and the resulting patch must carry the edit. The edit key
        // set tracks CARRIED_FIELDS, so a new row without an edit here fails
        // loudly too.
        val previous = RemoteSettingsMapper.toCameraSettings(wire())
        val edits = mapOf(
            "exposureCompensation" to previous.copy(exposureCompensation = 7),
            "iso" to previous.copy(iso = 200),
            "exposureTime" to previous.copy(exposureTime = 20_000_000L),
            "focusMode" to previous.copy(focusMode = FocusMode.MACRO),
            "focusDistance" to previous.copy(focusDistance = 9.5f),
            "whiteBalance" to previous.copy(whiteBalance = WhiteBalance.DAYLIGHT),
            "colorTemperature" to previous.copy(colorTemperature = 6500),
            "frameRate" to previous.copy(frameRate = 60),
            "resolution" to previous.copy(resolution = Resolution.FHD_1080P),
            "stabilization" to previous.copy(stabilization = true),
            "hdrMode" to previous.copy(hdrMode = HdrMode.OFF),
            "sceneMode" to previous.copy(sceneMode = null),
            "nightVisionMode" to previous.copy(nightVisionMode = NightVisionMode.ON),
            "outputRotation" to previous.copy(outputRotation = 270),
            "orientationLocked" to previous.copy(orientationLocked = false),
        )
        assertEquals(edits.keys, RemoteSettingsMapper.CARRIED_FIELDS.map { it.wireName }.toSet())
        RemoteSettingsMapper.CARRIED_FIELDS.forEach { row ->
            val next = edits.getValue(row.wireName)
            // Exactly this field's row moved — no sibling row leaks the edit.
            assertEquals(
                "wrong row(s) moved by the ${row.wireName} edit",
                listOf(row.wireName),
                RemoteSettingsMapper.CARRIED_FIELDS
                    .filter { it.get(previous) != it.get(next) }
                    .map { it.wireName },
            )
            // The PUT carries the change (the section travels whole).
            val patch = RemoteSettingsMapper.toPatch(previous, next) as SettingsUpdate
            assertEquals(RemoteSettingsMapper.toRemoteSection(next), patch.camera)
        }
    }

    // ── the stable enumerations ──

    @Test
    fun `unmapped fields enumeration is exactly the torch pair`() {
        assertEquals(setOf("torchEnabled", "torchStrength"), RemoteSettingsMapper.UNMAPPED_FIELDS)
    }

    @Test
    fun `patch-excluded fields enumeration is torch and zoom`() {
        assertEquals(setOf("torchEnabled", "zoomRatio"), RemoteSettingsMapper.PATCH_EXCLUDED_FIELDS)
    }

    @Test
    fun `diff table plus exclusions plus unmapped partition the settings fields`() {
        // CameraSettings' exact field set, DERIVED from the model's declared
        // instance fields: every val compiles to a same-named final field,
        // and the static companions/synthetics are filtered out — so adding
        // a 19th model field without a diff-table row or a documented set
        // entry fails here, with no literal list left to drift from.
        val allFields = CameraSettings::class.java.declaredFields
            .filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertTrue("derived no CameraSettings properties", allFields.isNotEmpty())
        val carried = RemoteSettingsMapper.CARRIED_FIELDS.map { it.wireName }.toSet()
        assertEquals(15, carried.size)
        val excludedWithCounterpart = setOf("zoomRatio")
        val unmapped = RemoteSettingsMapper.UNMAPPED_FIELDS
        assertEquals(allFields, carried + excludedWithCounterpart + unmapped)
        // No field is in two buckets at once.
        assertEquals(0, carried.intersect(excludedWithCounterpart).size)
        assertEquals(0, carried.intersect(unmapped).size)
    }

    // ── the fresh-wire-base patch direction ──

    @Test
    fun `a fresh wire section keeps unedited wire values and the device zoom`() {
        // The save-time GET reports an unrecognized focus mode ("EDOF" —
        // decoded as the AUTO fallback for display) and a newer live zoom
        // (persisted by the device's /api/camera/zoom): a frame-rate-only
        // edit must re-PUT neither. Unedited fields ride the wire verbatim
        // and zoom stays at the device's value — never the stale snapshot's.
        val fresh = wire(focusMode = "EDOF", zoomRatio = 3.5)
        val previous = RemoteSettingsMapper.toCameraSettings(fresh)
        val next = previous.copy(frameRate = 60)
        val patch = RemoteSettingsMapper.toPatch(previous, next, fresh) as SettingsUpdate
        assertEquals("EDOF", patch.camera!!.focusMode)
        assertEquals(3.5, patch.camera!!.zoomRatio, 0.0)
        assertEquals(60, patch.camera!!.frameRate)
    }

    @Test
    fun `a fresh wire section still carries the edit and refuses a no-op`() {
        // The fresh base carries the edit itself, and an unchanged snapshot
        // against the same wire document still refuses to PUT.
        val fresh = wire()
        val previous = RemoteSettingsMapper.toCameraSettings(fresh)
        val next = previous.copy(exposureCompensation = -3)
        val patch = RemoteSettingsMapper.toPatch(previous, next, fresh) as SettingsUpdate
        assertEquals(-3, patch.camera!!.exposureCompensation)
        assertNull(RemoteSettingsMapper.toPatch(previous, previous.copy(), fresh))
    }

    // ── round-trip sanity through the patch ──

    @Test
    fun `wire snapshot with an equal next is a no-op delta end to end`() {
        val document = wire()
        val previous = RemoteSettingsMapper.toCameraSettings(document)
        // An identical device report (the poll raced the save): nothing to PUT.
        assertNull(RemoteSettingsMapper.toPatch(previous, previous.copy()))
    }
}
