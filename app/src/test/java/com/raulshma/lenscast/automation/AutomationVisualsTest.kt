package com.raulshma.lenscast.automation

import com.raulshma.lenscast.R
import com.raulshma.lenscast.automation.schedule.AutomationActionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationConditionCatalog
import com.raulshma.lenscast.automation.schedule.AutomationIconVocabulary
import com.raulshma.lenscast.automation.schedule.AutomationTriggerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker decorations' single-source contract: the GROUPS and the ICON
 * KINDS are published device truth (each catalog entry carries its `group`
 * and `icon`), so [AutomationVisuals] only translates — group ids to
 * localized titles, icon kinds to drawables. These tests pin the
 * translation's completeness, so a catalog entry added with a group or icon
 * the visuals do not know fails here (and in the web contract test) instead
 * of rendering ungrouped or falling back to the generic badge on a device.
 */
class AutomationVisualsTest {

    // ── groups derive from the catalogs ──

    @Test
    fun `trigger groups cover the catalog exactly once in catalog order`() {
        val grouped = AutomationVisuals.TRIGGER_GROUPS.flatMap { it.ids }
        assertEquals(
            AutomationTriggerCatalog.ACTION_TYPES.map { it.id },
            grouped,
        )
        // No id listed twice, every group non-empty.
        assertEquals(grouped.size, grouped.toSet().size)
        assertTrue(AutomationVisuals.TRIGGER_GROUPS.all { it.ids.isNotEmpty() })
    }

    @Test
    fun `action groups cover the catalog exactly once in catalog order`() {
        val groups = AutomationVisuals.ACTION_GROUPS
        val grouped = groups.flatMap { it.ids }
        // Groups appear in first-appearance (catalog display) order and each
        // group preserves the catalog's relative order — so a light_sound
        // entry late in the catalog (siren) groups up with torch instead of
        // dragging its whole group to the catalog-tail position.
        assertEquals(grouped.size, grouped.toSet().size)
        assertEquals(
            AutomationActionCatalog.ACTION_TYPES.map { it.id }.sorted(),
            grouped.sorted(),
        )
        for (group in groups) {
            val catalogOrder = AutomationActionCatalog.ACTION_TYPES.map { it.id }.filter { it in group.ids }
            assertEquals("group members must keep the catalog's relative order", catalogOrder, group.ids)
        }
        assertTrue(groups.all { it.ids.isNotEmpty() })
    }

    @Test
    fun `condition groups cover the catalog exactly once in catalog order`() {
        val grouped = AutomationVisuals.CONDITION_GROUPS.flatMap { it.ids }
        assertEquals(
            AutomationConditionCatalog.CONDITIONS.map { it.id },
            grouped,
        )
        assertEquals(grouped.size, grouped.toSet().size)
        assertTrue(AutomationVisuals.CONDITION_GROUPS.all { it.ids.isNotEmpty() })
    }

    // ── icon kinds resolve to real visuals ──

    @Test
    fun `every catalog entry has a group and a vocabulary icon that resolves to a visual`() {
        for (type in AutomationTriggerCatalog.ACTION_TYPES) {
            assertTrue("trigger ${type.id} has no group", type.group.isNotBlank())
            assertTrue(
                "trigger ${type.id} icon outside the closed vocabulary: ${type.icon}",
                type.icon in AutomationIconVocabulary.ALL,
            )
            assertNotEquals(
                "trigger ${type.id} fell back to the unknown visual",
                R.string.automation_action_unknown,
                AutomationVisuals.triggerVisual(type.id).descriptionRes,
            )
        }
        for (type in AutomationActionCatalog.ACTION_TYPES) {
            assertTrue("action ${type.id} has no group", type.group.isNotBlank())
            assertTrue(
                "action ${type.id} icon outside the closed vocabulary: ${type.icon}",
                type.icon in AutomationIconVocabulary.ALL,
            )
            assertNotEquals(
                "action ${type.id} fell back to the unknown visual",
                R.string.automation_action_unknown,
                AutomationVisuals.actionVisual(type.id).descriptionRes,
            )
        }
        for (type in AutomationConditionCatalog.CONDITIONS) {
            assertTrue("condition ${type.id} has no group", type.group.isNotBlank())
            assertTrue(
                "condition ${type.id} icon outside the closed vocabulary: ${type.icon}",
                type.icon in AutomationIconVocabulary.ALL,
            )
            assertNotEquals(
                "condition ${type.id} fell back to the unknown visual",
                R.string.automation_action_unknown,
                AutomationVisuals.conditionVisual(type.id).descriptionRes,
            )
        }
    }

    @Test
    fun `unknown ids and kinds degrade to the fallback visual`() {
        val fallbackRes = R.string.automation_action_unknown
        assertEquals(fallbackRes, AutomationVisuals.triggerVisual("mystery").descriptionRes)
        assertEquals(fallbackRes, AutomationVisuals.actionVisual("mystery").descriptionRes)
        assertEquals(fallbackRes, AutomationVisuals.conditionVisual("mystery").descriptionRes)
    }
}
