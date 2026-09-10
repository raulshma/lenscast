package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.streaming.model.SettingsExportDto
import com.raulshma.lenscast.streaming.model.SettingsResponseDto
import com.raulshma.lenscast.streaming.model.SettingsUpdateRequestDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import body classifier: export envelopes (with their schema version
 * gate), bare settings documents, and everything that must be rejected.
 */
class SettingsImportParserTest {

    private val envelopeAdapter = AppJson.moshi.adapter(SettingsExportDto::class.java)
    private val updateAdapter = AppJson.moshi.adapter(SettingsUpdateRequestDto::class.java)

    private fun envelopeJson(schemaVersion: Int = 1, withSettings: Boolean = true): String {
        val export = SettingsExportDto(
            schemaVersion = schemaVersion,
            exportedAtMs = 1_788_825_600_000,
            settings = if (withSettings) SettingsResponseDto(
                camera = com.raulshma.lenscast.streaming.model.CameraSettingsDto(),
                streaming = com.raulshma.lenscast.streaming.model.StreamingSettingsDto(),
            ) else null,
        )
        return envelopeAdapter.toJson(export)
    }

    @Test
    fun `an export envelope parses as an envelope`() {
        val parsed = SettingsImportParser.parse(envelopeJson())
        assertTrue(parsed is SettingsImportParser.Parsed.Envelope)
        assertEquals(1, (parsed as SettingsImportParser.Parsed.Envelope).export.schemaVersion)
    }

    @Test
    fun `an unsupported schema version is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsImportParser.parse(envelopeJson(schemaVersion = 99))
        }
    }

    @Test
    fun `a bare settings document parses as a raw update`() {
        val update = SettingsUpdateRequestDto(
            streaming = com.raulshma.lenscast.streaming.model.StreamingSettingsDto(jpegQuality = 85),
        )
        val parsed = SettingsImportParser.parse(updateAdapter.toJson(update))
        assertTrue(parsed is SettingsImportParser.Parsed.RawUpdate)
        assertEquals(
            85,
            (parsed as SettingsImportParser.Parsed.RawUpdate).update.streaming?.jpegQuality,
        )
    }

    @Test
    fun `garbage is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsImportParser.parse("not json at all")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsImportParser.parse("{}")
        }
    }

    @Test
    fun `an envelope with no settings document is classified as an envelope`() {
        // exportedAtMs set marks it as an envelope even with a null settings
        // doc; the handler rejects the missing settings.
        val parsed = SettingsImportParser.parse(envelopeJson(withSettings = false))
        assertTrue(parsed is SettingsImportParser.Parsed.Envelope)
    }
}
