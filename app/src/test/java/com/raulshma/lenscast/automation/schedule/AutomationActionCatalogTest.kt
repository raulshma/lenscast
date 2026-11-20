package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.NightVisionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The action catalog's contract: the exact id and param-name strings other
 * agents build against, every bound's clamp direction, the absence
 * semantics (required → default, optional → absent), and the fold-to-default
 * handling of invalid values — all pure JVM.
 */
class AutomationActionCatalogTest {

    private fun sanitize(spec: AutomationActionSpec) = AutomationActionCatalog.sanitizeAction(spec)

    // ── The catalog contract: exact ids and param names ──

    @Test
    fun `catalog holds exactly the twelve specced ids in order`() {
        assertEquals(
            listOf(
                "camera_exposure", "camera_ev", "camera_zoom", "camera_fps",
                "camera_lens", "night_vision", "torch", "stream", "capture_photo",
                "recording", "siren", "detection",
            ),
            AutomationActionCatalog.ACTION_TYPES.map { it.id },
        )
    }

    @Test
    fun `param names match the spec exactly`() {
        fun params(id: String) = AutomationActionCatalog.typeFor(id)!!.params.map { it.name }
        assertEquals(listOf("iso", "exposure_time_ms"), params("camera_exposure"))
        assertEquals(listOf("index"), params("camera_ev"))
        assertEquals(listOf("ratio"), params("camera_zoom"))
        assertEquals(listOf("fps"), params("camera_fps"))
        assertEquals(listOf("index"), params("camera_lens"))
        assertEquals(listOf("mode"), params("night_vision"))
        assertEquals(listOf("enabled", "strength"), params("torch"))
        assertEquals(listOf("enabled"), params("stream"))
        assertEquals(emptyList<String>(), params("capture_photo"))
        assertEquals(listOf("enabled", "duration_seconds"), params("recording"))
        assertEquals(listOf("enabled", "duration_seconds"), params("siren"))
        assertEquals(listOf("motion_enabled", "sound_enabled"), params("detection"))
    }

    @Test
    fun `catch-up classes and stream interrupts match the spec`() {
        assertEquals(CatchUpClass.ONESHOT, AutomationActionCatalog.typeFor("capture_photo")!!.catchUp)
        AutomationActionCatalog.ACTION_TYPES
            .filter { it.id != "capture_photo" }
            .forEach { assertEquals("stateful: ${it.id}", CatchUpClass.STATEFUL, it.catchUp) }
        assertTrue(AutomationActionCatalog.typeFor("camera_lens")!!.interruptsStream)
        AutomationActionCatalog.ACTION_TYPES
            .filter { it.id != "camera_lens" }
            .forEach { assertFalse("no interrupt: ${it.id}", it.interruptsStream) }
    }

    @Test
    fun `capture_photo has no parameters and rejects any`() {
        assertTrue(AutomationActionCatalog.typeFor("capture_photo")!!.params.isEmpty())
        assertEquals(
            AutomationActionSpec("capture_photo"),
            sanitize(AutomationActionSpec("capture_photo")),
        )
        assertNull(sanitize(AutomationActionSpec("capture_photo", mapOf("duration" to "5"))))
    }

    // ── autoAtZero: the "0"-as-sentinel pin ──

    @Test
    fun `autoAtZero is true exactly on the exposure auto sentinels`() {
        // Only camera_exposure's iso/exposure_time_ms mean "auto" at 0; the
        // duration params' "0" means "no limit", NOT auto, so their clients
        // must not render an Auto toggle.
        val flagged = AutomationActionCatalog.ACTION_TYPES
            .flatMap { type -> type.params.map { type.id to it } }
            .filter { it.second.autoAtZero }
            .map { "${it.first}.${it.second.name}" }
        assertEquals(listOf("camera_exposure.iso", "camera_exposure.exposure_time_ms"), flagged)
        // Explicitly: the duration "0" sentinels stay ordinary values.
        assertFalse(AutomationActionCatalog.typeFor("recording")!!.params.last().autoAtZero)
        assertFalse(AutomationActionCatalog.typeFor("siren")!!.params.last().autoAtZero)
    }

    // ── Canonicalization API (the editor delegates here) ──

    @Test
    fun `canonicalIntValue parses coerces and rounds integrally`() {
        assertEquals(4000, AutomationActionCatalog.canonicalIntValue("4000"))
        assertEquals(4000, AutomationActionCatalog.canonicalIntValue("4000.4"))
        assertEquals(1, AutomationActionCatalog.canonicalIntValue("0.6"))
        assertEquals(24, AutomationActionCatalog.canonicalIntValue("  24  "))
        assertEquals(3, AutomationActionCatalog.canonicalIntValue("+3"))
        // Coerced into Int range instead of overflowing.
        assertEquals(Int.MAX_VALUE, AutomationActionCatalog.canonicalIntValue("1e300"))
        assertEquals(Int.MIN_VALUE, AutomationActionCatalog.canonicalIntValue("-1e300"))
        // Unparseable or non-finite is null.
        assertNull(AutomationActionCatalog.canonicalIntValue("abc"))
        assertNull(AutomationActionCatalog.canonicalIntValue(""))
        assertNull(AutomationActionCatalog.canonicalIntValue("NaN"))
        assertNull(AutomationActionCatalog.canonicalIntValue("Infinity"))
    }

    @Test
    fun `canonicalFloatValue rounds to one decimal and drops the trailing zero`() {
        assertEquals("2", AutomationActionCatalog.canonicalFloatValue("2.0"))
        assertEquals("2", AutomationActionCatalog.canonicalFloatValue("2"))
        assertEquals("1.5", AutomationActionCatalog.canonicalFloatValue("1.5"))
        assertEquals("1.5", AutomationActionCatalog.canonicalFloatValue("1.46"))
        assertEquals("10", AutomationActionCatalog.canonicalFloatValue("9.96"))
        assertEquals("2.5", AutomationActionCatalog.canonicalFloatValue("2.5"))
        // Unparseable or non-finite is null.
        assertNull(AutomationActionCatalog.canonicalFloatValue("abc"))
        assertNull(AutomationActionCatalog.canonicalFloatValue("NaN"))
        assertNull(AutomationActionCatalog.canonicalFloatValue("Infinity"))
    }

    // ── Bounds: above max clamps down, below min clamps up ──

    @Test
    fun `int values clamp into their span both directions`() {
        assertEquals(mapOf("index" to "12"), sanitize(AutomationActionSpec("camera_ev", mapOf("index" to "99")))!!.params)
        assertEquals(mapOf("index" to "-12"), sanitize(AutomationActionSpec("camera_ev", mapOf("index" to "-99")))!!.params)
        // The ISO ceiling covers modern manual-night sensors: 4000 (the
        // issue #16 ask) passes through, past 10_000 clamps down.
        assertEquals(mapOf("iso" to "4000"), sanitize(AutomationActionSpec("camera_exposure", mapOf("iso" to "4000")))!!.params)
        assertEquals(mapOf("iso" to "10000"), sanitize(AutomationActionSpec("camera_exposure", mapOf("iso" to "10001")))!!.params)
        assertEquals(
            mapOf("iso" to "10000"),
            sanitize(AutomationActionSpec("camera_exposure", mapOf("iso" to "99999999999999")))!!.params,
        )
        assertEquals(
            mapOf("enabled" to "true", "strength" to "8"),
            sanitize(AutomationActionSpec("torch", mapOf("enabled" to "true", "strength" to "99")))!!.params,
        )
        assertEquals(
            mapOf("enabled" to "true", "strength" to "1"),
            sanitize(AutomationActionSpec("torch", mapOf("enabled" to "true", "strength" to "0")))!!.params,
        )
    }

    @Test
    fun `float values clamp into their span both directions`() {
        assertEquals(mapOf("ratio" to "10"), sanitize(AutomationActionSpec("camera_zoom", mapOf("ratio" to "99")))!!.params)
        assertEquals(mapOf("ratio" to "1"), sanitize(AutomationActionSpec("camera_zoom", mapOf("ratio" to "0.5")))!!.params)
        assertEquals(mapOf("ratio" to "2.5"), sanitize(AutomationActionSpec("camera_zoom", mapOf("ratio" to "2.5")))!!.params)
        assertEquals(
            mapOf("exposure_time_ms" to "1000"),
            sanitize(AutomationActionSpec("camera_exposure", mapOf("exposure_time_ms" to "5000")))!!.params,
        )
    }

    @Test
    fun `values past every long clamp to the bound instead of failing`() {
        assertEquals(
            mapOf("fps" to "120"),
            sanitize(AutomationActionSpec("camera_fps", mapOf("fps" to "99999999999999999999")))!!.params,
        )
    }

    @Test
    fun `duration bounds clamp at zero and their ceilings`() {
        // enabled is required on both, so its absence folds to the default.
        assertEquals(
            mapOf("enabled" to "true", "duration_seconds" to "0"),
            sanitize(AutomationActionSpec("siren", mapOf("duration_seconds" to "-1")))!!.params,
        )
        assertEquals(
            mapOf("enabled" to "true", "duration_seconds" to "0"),
            sanitize(AutomationActionSpec("recording", mapOf("duration_seconds" to "-5")))!!.params,
        )
        assertEquals(
            "86400",
            sanitize(AutomationActionSpec("recording", mapOf("duration_seconds" to "90000")))!!.params["duration_seconds"],
        )
    }

    // ── Invalid values fold to the documented default ──

    @Test
    fun `invalid bool folds to the default`() {
        // Required bool: folds to its default value.
        assertEquals(
            mapOf("enabled" to "true"),
            sanitize(AutomationActionSpec("torch", mapOf("enabled" to "garbage")))!!.params,
        )
        // Optional bool with no persisted default: folds away entirely
        // (absent = leave unchanged).
        val detection = sanitize(AutomationActionSpec("detection", mapOf("motion_enabled" to "garbage")))!!
        assertFalse(detection.params.containsKey("motion_enabled"))
    }

    @Test
    fun `invalid numbers fold to the default`() {
        assertEquals(mapOf("fps" to "24"), sanitize(AutomationActionSpec("camera_fps", mapOf("fps" to "abc")))!!.params)
        // Non-finite literals parse as doubles but must not survive the clamp.
        assertEquals(mapOf("ratio" to "1"), sanitize(AutomationActionSpec("camera_zoom", mapOf("ratio" to "NaN")))!!.params)
        assertEquals(mapOf("ratio" to "1"), sanitize(AutomationActionSpec("camera_zoom", mapOf("ratio" to "Infinity")))!!.params)
        // Fractional values are invalid for INT params.
        assertEquals(mapOf("fps" to "24"), sanitize(AutomationActionSpec("camera_fps", mapOf("fps" to "29.97")))!!.params)
        // Optional numeric with a documented default folds to it ("0" = auto).
        assertEquals(
            mapOf("iso" to "0"),
            sanitize(AutomationActionSpec("camera_exposure", mapOf("iso" to "abc")))!!.params,
        )
        // Optional numeric without a default folds away.
        val torch = sanitize(AutomationActionSpec("torch", mapOf("enabled" to "true", "strength" to "garbage")))!!
        assertFalse(torch.params.containsKey("strength"))
    }

    @Test
    fun `bool parses leniently and writes canonically`() {
        assertEquals(mapOf("enabled" to "true"), sanitize(AutomationActionSpec("torch", mapOf("enabled" to "TRUE")))!!.params)
        assertEquals(mapOf("enabled" to "false"), sanitize(AutomationActionSpec("torch", mapOf("enabled" to "false")))!!.params)
    }

    // ── Absence semantics ──

    @Test
    fun `required param absent folds to its default value`() {
        assertEquals(mapOf("index" to "0"), sanitize(AutomationActionSpec("camera_ev", emptyMap()))!!.params)
        assertEquals(mapOf("ratio" to "1"), sanitize(AutomationActionSpec("camera_zoom", emptyMap()))!!.params)
        assertEquals(mapOf("fps" to "24"), sanitize(AutomationActionSpec("camera_fps", emptyMap()))!!.params)
        assertEquals(mapOf("enabled" to "true"), sanitize(AutomationActionSpec("torch", emptyMap()))!!.params)
        assertEquals(mapOf("mode" to "off"), sanitize(AutomationActionSpec("night_vision", emptyMap()))!!.params)
    }

    // ── STRING values: the closed enum vocabulary ──

    @Test
    fun `string values parse leniently and write the canonical id`() {
        assertEquals(mapOf("mode" to "on"), sanitize(AutomationActionSpec("night_vision", mapOf("mode" to "ON")))!!.params)
        assertEquals(mapOf("mode" to "auto"), sanitize(AutomationActionSpec("night_vision", mapOf("mode" to "  Auto ")))!!.params)
        assertEquals(mapOf("mode" to "off"), sanitize(AutomationActionSpec("night_vision", mapOf("mode" to "off")))!!.params)
    }

    @Test
    fun `invalid string folds to the documented default`() {
        assertEquals(
            mapOf("mode" to "off"),
            sanitize(AutomationActionSpec("night_vision", mapOf("mode" to "midnight")))!!.params,
        )
        assertEquals(
            mapOf("mode" to "off"),
            sanitize(AutomationActionSpec("night_vision", mapOf("mode" to "")))!!.params,
        )
    }

    @Test
    fun `optional param absent stays absent`() {
        assertEquals(
            mapOf("iso" to "800"),
            sanitize(AutomationActionSpec("camera_exposure", mapOf("iso" to "800")))!!.params,
        )
        assertEquals(
            mapOf("enabled" to "true"),
            sanitize(AutomationActionSpec("torch", mapOf("enabled" to "true")))!!.params,
        )
    }

    // ── Rejection ──

    @Test
    fun `unknown type returns null`() {
        assertNull(sanitize(AutomationActionSpec("laser", emptyMap())))
        assertNull(AutomationActionCatalog.typeFor("laser"))
        assertNull(AutomationActionCatalog.typeFor(""))
    }

    @Test
    fun `unknown param name returns null`() {
        assertNull(sanitize(AutomationActionSpec("torch", mapOf("enabled" to "true", "bogus" to "1"))))
        assertNull(sanitize(AutomationActionSpec("stream", mapOf("bogus" to "1"))))
    }

    // ── Canonical form is a fixed point ──

    @Test
    fun `sanitize is idempotent`() {
        val raw = AutomationActionSpec("torch", mapOf("enabled" to "TRUE", "strength" to "99"))
        val once = sanitize(raw)!!
        assertEquals(once, sanitize(once))
    }

    @Test
    fun `typeFor resolves every catalog id and nothing else`() {
        AutomationActionCatalog.ACTION_TYPES.forEach {
            assertEquals(it, AutomationActionCatalog.typeFor(it.id))
        }
        assertEquals(AutomationActionCatalog.ACTION_TYPES.size, 12)
    }

    // ── Behavior anatomy: each entry carries its own semantics ──
    // The old per-action ladders (executor dispatch, the camera merge, the
    // restore verdicts) are gone; these tests pin the declarations that
    // replaced them, so a new or edited entry cannot silently rely on
    // machinery elsewhere.

    @Test
    fun `every entry declares its dispatch effects`() {
        val expected = mapOf(
            "camera_exposure" to listOf(AutomationEffect.MergeCameraSettings),
            "camera_ev" to listOf(AutomationEffect.MergeCameraSettings),
            "camera_zoom" to listOf(AutomationEffect.MergeCameraSettings),
            "camera_fps" to listOf(AutomationEffect.MergeCameraSettings),
            "camera_lens" to listOf(AutomationEffect.SwitchLens),
            "night_vision" to listOf(AutomationEffect.MergeCameraSettings),
            "torch" to listOf(AutomationEffect.MergeCameraSettings),
            "stream" to listOf(AutomationEffect.ToggleStream),
            "capture_photo" to listOf(AutomationEffect.CapturePhoto),
            "recording" to listOf(AutomationEffect.ToggleRecording),
            "siren" to listOf(AutomationEffect.ToggleSiren),
            "detection" to listOf(AutomationEffect.SaveDetectionToggles),
        )
        AutomationActionCatalog.ACTION_TYPES.forEach { entry ->
            assertTrue("no effects declared: ${entry.id}", entry.effects.isNotEmpty())
            assertEquals("effects: ${entry.id}", expected[entry.id], entry.effects)
        }
    }

    @Test
    fun `every entry declares its merge and only camera-valued ids move the document`() {
        val base = CameraSettings(iso = 400, torchEnabled = true)
        val cameraValued = setOf("camera_exposure", "camera_ev", "camera_zoom", "camera_fps", "torch", "night_vision")
        AutomationActionCatalog.ACTION_TYPES.forEach { entry ->
            if (entry.id in cameraValued) {
                // A camera merge parses from params; an EMPTY param map must
                // leave the document untouched (the "leave unchanged" contract).
                assertEquals("identity on empty params: ${entry.id}", base, entry.merge(base, emptyMap()))
            } else {
                // Non-camera actions route to their own seams — the merge is
                // identity whatever the params claim.
                assertEquals(
                    "identity merge: ${entry.id}",
                    base,
                    entry.merge(base, mapOf("iso" to "1", "enabled" to "true")),
                )
            }
        }
    }

    @Test
    fun `every entry declares its restore spec`() {
        val expected = mapOf(
            "camera_exposure" to RestoreSpec.Axes(
                listOf(
                    RestorableAxis(RestoreAxis.ISO, onlyIfTouched = true),
                    RestorableAxis(RestoreAxis.EXPOSURE_TIME_MS, onlyIfTouched = true),
                ),
            ),
            "camera_ev" to RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.EV_INDEX))),
            "camera_zoom" to RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.ZOOM_RATIO))),
            "camera_fps" to RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.FRAME_RATE))),
            "camera_lens" to RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.LENS_INDEX))),
            "night_vision" to RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.NIGHT_VISION))),
            "torch" to RestoreSpec.Axes(
                listOf(
                    RestorableAxis(RestoreAxis.TORCH_ENABLED),
                    RestorableAxis(RestoreAxis.TORCH_STRENGTH, onlyIfTouched = true),
                ),
            ),
            "stream" to RestoreSpec.Axes(listOf(RestorableAxis(RestoreAxis.STREAM_RUNNING))),
            "capture_photo" to RestoreSpec.NotRestorable,
            "recording" to RestoreSpec.StopWhenArmedAndIdle,
            "siren" to RestoreSpec.StopWhenArmed,
            "detection" to RestoreSpec.Axes(
                listOf(
                    RestorableAxis(RestoreAxis.MOTION_ENABLED, onlyIfTouched = true),
                    RestorableAxis(RestoreAxis.SOUND_ENABLED, onlyIfTouched = true),
                ),
            ),
        )
        AutomationActionCatalog.ACTION_TYPES.forEach { entry ->
            assertEquals("restore: ${entry.id}", expected[entry.id], entry.restore)
        }
    }

    @Test
    fun `restore axes stay inside the entry's own param vocabulary`() {
        AutomationActionCatalog.ACTION_TYPES.forEach { entry ->
            val restore = entry.restore as? RestoreSpec.Axes ?: return@forEach
            restore.axes.forEach { declared ->
                assertEquals("axis param in schema: ${entry.id}/${declared.axis}", declared.axis.param in entry.params.map { it.name }, true)
            }
        }
    }

    @Test
    fun `the snapshot covers exactly the declared axes, recording-idle gate included`() {
        assertEquals(
            listOf(
                RestoreAxis.ISO,
                RestoreAxis.EXPOSURE_TIME_MS,
                RestoreAxis.EV_INDEX,
                RestoreAxis.ZOOM_RATIO,
                RestoreAxis.FRAME_RATE,
                RestoreAxis.LENS_INDEX,
                RestoreAxis.NIGHT_VISION,
                RestoreAxis.TORCH_ENABLED,
                RestoreAxis.TORCH_STRENGTH,
                RestoreAxis.STREAM_RUNNING,
                RestoreAxis.RECORDING_IDLE,
                RestoreAxis.MOTION_ENABLED,
                RestoreAxis.SOUND_ENABLED,
            ),
            AutomationActionCatalog.SNAPSHOT_AXES,
        )
    }

    @Test
    fun `camera merges fold their documented sentinel semantics`() {
        // Only the exact "0" is the auto sentinel; malformed values leave the
        // field untouched; night_vision maps its canonical ids and degrades
        // an unknown mode to no change.
        val base = CameraSettings(iso = 400, exposureTime = 5_000_000L, nightVisionMode = NightVisionMode.OFF)
        val exposure = AutomationActionCatalog.typeFor("camera_exposure")!!
        val auto = exposure.merge(base, mapOf("iso" to "0", "exposure_time_ms" to "0"))
        assertNull(auto.iso)
        assertNull(auto.exposureTime)
        val malformed = exposure.merge(base, mapOf("iso" to "garbage", "exposure_time_ms" to "abc"))
        assertEquals(base, malformed)
        val manual = exposure.merge(base, mapOf("iso" to "800", "exposure_time_ms" to "20"))
        assertEquals(800, manual.iso)
        assertEquals(20_000_000L, manual.exposureTime)

        val nightVision = AutomationActionCatalog.typeFor("night_vision")!!
        assertEquals(NightVisionMode.ON, nightVision.merge(base, mapOf("mode" to "on")).nightVisionMode)
        assertEquals(base, nightVision.merge(base, mapOf("mode" to "midnight")))
        val torch = AutomationActionCatalog.typeFor("torch")!!
        // Strength applies only when present; absent leaves a stored level.
        val torchKeep = torch.merge(base.copy(torchStrength = 3), mapOf("enabled" to "true"))
        assertEquals(3, torchKeep.torchStrength)
        assertTrue(torchKeep.torchEnabled)
        // A camera_lens action is a LIVE switch — its merge stays identity.
        assertEquals(base, AutomationActionCatalog.typeFor("camera_lens")!!.merge(base, mapOf("index" to "3")))
    }
}
