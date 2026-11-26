package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.StreamDefaults
import com.raulshma.lenscast.streaming.model.WebUiColumnDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutDto
import com.raulshma.lenscast.streaming.model.WebUiLayoutPresetDto
import com.raulshma.lenscast.streaming.model.WebUiSectionPlacementDto
import java.util.UUID

/**
 * Pure normalization for the persisted web inspector layout.
 *
 * The policy is total over the typed DTO graph: unknown width presets fold to
 * the width's named preset (or `custom`), unknown section kinds use the safe
 * built-in shape, blank built-in ids drop, and blank/duplicate placement ids
 * are replaced with UUIDs. Lists are capped before callers can persist or
 * return them, including every custom-preset snapshot.
 */
object WebUiLayoutPolicy {

    private const val KIND_BUILTIN = "builtin"
    private const val KIND_CUSTOM = "custom"
    private const val WIDTH_PRESET_COMPACT = "compact"
    private const val WIDTH_PRESET_DEFAULT = "default"
    private const val WIDTH_PRESET_WIDE = "wide"
    private const val WIDTH_PRESET_CUSTOM = "custom"
    private const val COMPACT_WIDTH_PX = 280
    private const val WIDE_WIDTH_PX = 420
    private const val MAX_ID_LENGTH = 96
    private const val MAX_ACTIVE_PRESET_ID_LENGTH = 64

    /** A fresh layout: the exact five built-ins, in display order. */
    fun defaultLayout(): WebUiLayoutDto = WebUiLayoutDto(
        schemaVersion = StreamDefaults.WEB_UI_LAYOUT_SCHEMA_VERSION,
        updatedAtMs = 0L,
        activePresetId = WIDTH_PRESET_DEFAULT,
        column = WebUiColumnDto(
            widthPx = StreamDefaults.COLUMN_WIDTH_DEFAULT_PX,
            widthPreset = WIDTH_PRESET_DEFAULT,
            collapsed = false,
        ),
        sections = WebUiLayoutDto.defaultSections(),
        presets = emptyList(),
    )

    /** Normalize the whole document, recursively including preset snapshots. */
    fun sanitize(layout: WebUiLayoutDto): WebUiLayoutDto = WebUiLayoutDto(
        schemaVersion = StreamDefaults.WEB_UI_LAYOUT_SCHEMA_VERSION,
        updatedAtMs = layout.updatedAtMs.coerceAtLeast(0L),
        activePresetId = text(layout.activePresetId, MAX_ACTIVE_PRESET_ID_LENGTH)
            .ifEmpty { WIDTH_PRESET_DEFAULT },
        column = sanitizeColumn(layout.column),
        sections = sanitizeSections(layout.sections),
        presets = sanitizePresets(layout.presets),
    )

    private fun sanitizeColumn(column: WebUiColumnDto): WebUiColumnDto {
        val widthPx = column.widthPx.coerceIn(
            StreamDefaults.COLUMN_WIDTH_MIN_PX,
            StreamDefaults.COLUMN_WIDTH_MAX_PX,
        )
        return WebUiColumnDto(
            widthPx = widthPx,
            // A recognized choice is retained even when its width was clamped
            // (the editor may intentionally label a clamped width "wide"). An
            // unknown label is inferred from the final width when possible.
            widthPreset = when (column.widthPreset) {
                WIDTH_PRESET_COMPACT, WIDTH_PRESET_DEFAULT, WIDTH_PRESET_WIDE, WIDTH_PRESET_CUSTOM ->
                    column.widthPreset
                else -> when (widthPx) {
                    COMPACT_WIDTH_PX -> WIDTH_PRESET_COMPACT
                    StreamDefaults.COLUMN_WIDTH_DEFAULT_PX -> WIDTH_PRESET_DEFAULT
                    WIDE_WIDTH_PX -> WIDTH_PRESET_WIDE
                    else -> WIDTH_PRESET_CUSTOM
                }
            },
            collapsed = column.collapsed,
        )
    }

    private fun sanitizeSections(
        sections: List<WebUiSectionPlacementDto>,
    ): List<WebUiSectionPlacementDto> {
        val usedIds = mutableSetOf<String>()
        val usedBuiltins = mutableSetOf<String>()
        val result = ArrayList<WebUiSectionPlacementDto>(sections.size.coerceAtMost(StreamDefaults.MAX_SECTIONS))

        for ((index, section) in sections.withIndex()) {
            if (result.size == StreamDefaults.MAX_SECTIONS) break
            val kind = if (section.kind == KIND_CUSTOM) KIND_CUSTOM else KIND_BUILTIN
            val sectionId = text(section.sectionId, MAX_ID_LENGTH)
            if (kind == KIND_BUILTIN) {
                // Forward-compatible built-in ids survive; a blank one cannot
                // identify a catalog entry, and each built-in appears once.
                if (sectionId.isEmpty() || !usedBuiltins.add(sectionId)) continue
            }

            val id = uniqueId(
                rawId = section.id,
                usedIds = usedIds,
                seed = "section:$index:$kind:$sectionId",
            )
            result += WebUiSectionPlacementDto(
                id = id,
                kind = kind,
                sectionId = if (kind == KIND_BUILTIN) sectionId else sectionId.ifEmpty { "$KIND_CUSTOM-$id" },
                visible = section.visible,
                collapsed = section.collapsed,
                hiddenItems = if (kind == KIND_BUILTIN) {
                    sanitizeItemList(section.hiddenItems)
                } else {
                    emptyList()
                },
                customTitle = text(section.customTitle.orEmpty(), StreamDefaults.MAX_TITLE_LENGTH).ifEmpty { null },
                items = if (kind == KIND_CUSTOM) sanitizeItemList(section.items) else emptyList(),
            )
        }
        return result
    }

    private fun sanitizePresets(presets: List<WebUiLayoutPresetDto>): List<WebUiLayoutPresetDto> {
        val usedIds = mutableSetOf<String>()
        val result = ArrayList<WebUiLayoutPresetDto>(presets.size.coerceAtMost(StreamDefaults.MAX_CUSTOM_PRESETS))

        for ((index, preset) in presets.withIndex()) {
            if (result.size == StreamDefaults.MAX_CUSTOM_PRESETS) break
            val name = text(preset.name, StreamDefaults.MAX_PRESET_NAME_LENGTH)
            if (name.isEmpty()) continue
            result += WebUiLayoutPresetDto(
                id = uniqueId(
                    rawId = preset.id,
                    usedIds = usedIds,
                    seed = "preset:$index:$name",
                ),
                name = name,
                column = sanitizeColumn(preset.column),
                sections = sanitizeSections(preset.sections),
            )
        }
        return result
    }

    private fun sanitizeItemList(items: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val result = ArrayList<String>(items.size.coerceAtMost(StreamDefaults.MAX_CUSTOM_ITEMS_PER_SECTION))
        for (item in items) {
            if (result.size == StreamDefaults.MAX_CUSTOM_ITEMS_PER_SECTION) break
            val id = text(item, MAX_ID_LENGTH)
            if (id.isNotEmpty() && seen.add(id)) result += id
        }
        return result
    }

    private fun uniqueId(
        rawId: String,
        usedIds: MutableSet<String>,
        seed: String,
    ): String {
        val candidate = text(rawId, MAX_ID_LENGTH)
        if (candidate.isNotEmpty() && usedIds.add(candidate)) return candidate

        // Name-based UUIDs keep normalization reproducible: sanitizing the
        // same raw graph twice must mint the same identities, while the used
        // set still makes collisions (including an explicit id equal to a
        // previously minted one) impossible. Placement ids are local document
        // identities, not capabilities, so predictability is harmless.
        var attempt = 0
        while (true) {
            val minted = UUID.nameUUIDFromBytes(
                "lenscast:web-ui-layout:$seed:$attempt".toByteArray(Charsets.UTF_8),
            ).toString()
            if (usedIds.add(minted)) return minted
            attempt++
        }
    }

    private fun text(value: String, maxLength: Int): String = value.trim().take(maxLength)
}
