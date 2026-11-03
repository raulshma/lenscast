package com.raulshma.lenscast.automation.schedule

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
    fun `catalog holds exactly the eleven specced ids in order`() {
        assertEquals(
            listOf(
                "camera_exposure", "camera_ev", "camera_zoom", "camera_fps",
                "camera_lens", "torch", "stream", "capture_photo", "recording",
                "siren", "detection",
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
        assertEquals(AutomationActionCatalog.ACTION_TYPES.size, 11)
    }
}
