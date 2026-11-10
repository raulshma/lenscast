package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The [QuickSettingsUiState] machine's transitions and projections — the
 * camera screen's quick-settings panel (Collapsed / Expanded / Sheet). Each
 * transition mirrors one screen callback; the structural invariant under test
 * is "a sheet is open only while the bar is expanded".
 */
class QuickSettingsUiStateTest {

    // ---- the invariant --------------------------------------------------------------

    @Test
    fun `sheet open implies bar expanded - the invariant is structural`() {
        assertTrue(QuickSettingsUiState.Expanded.isBarExpanded)
        assertTrue(QuickSettingsUiState.Sheet(QuickSettingType.EXPOSURE).isBarExpanded)
        assertFalse(QuickSettingsUiState.Collapsed.isBarExpanded)
    }

    // ---- projections ----------------------------------------------------------------

    @Test
    fun `openSetting is null without a sheet and the control with one`() {
        assertNull(QuickSettingsUiState.Collapsed.openSetting)
        assertNull(QuickSettingsUiState.Expanded.openSetting)
        assertEquals(
            QuickSettingType.ISO,
            QuickSettingsUiState.Sheet(QuickSettingType.ISO).openSetting,
        )
    }

    // ---- toggled: the collapse/expand toggle ----------------------------------------

    @Test
    fun `toggle from collapsed opens the bar`() {
        assertEquals(QuickSettingsUiState.Expanded, QuickSettingsUiState.Collapsed.toggled())
    }

    @Test
    fun `toggle from the bare bar collapses`() {
        assertEquals(QuickSettingsUiState.Collapsed, QuickSettingsUiState.Expanded.toggled())
    }

    @Test
    fun `toggle with a sheet open collapses the whole panel - sheet included`() {
        // The old boolean-pair toggle cleared the active setting whenever it
        // collapsed; here that is the Sheet → Collapsed arm.
        assertEquals(
            QuickSettingsUiState.Collapsed,
            QuickSettingsUiState.Sheet(QuickSettingType.ZOOM).toggled(),
        )
    }

    // ---- settingTapped: the pill tap -------------------------------------------------

    @Test
    fun `tap on the bare bar opens the sheet`() {
        assertEquals(
            QuickSettingsUiState.Sheet(QuickSettingType.HDR),
            QuickSettingsUiState.Expanded.settingTapped(QuickSettingType.HDR),
        )
    }

    @Test
    fun `tap on the open sheet's own control closes the sheet`() {
        assertEquals(
            QuickSettingsUiState.Expanded,
            QuickSettingsUiState.Sheet(QuickSettingType.HDR).settingTapped(QuickSettingType.HDR),
        )
    }

    @Test
    fun `tap on another control moves the sheet to it`() {
        assertEquals(
            QuickSettingsUiState.Sheet(QuickSettingType.ISO),
            QuickSettingsUiState.Sheet(QuickSettingType.HDR).settingTapped(QuickSettingType.ISO),
        )
    }

    // ---- sheetDismissed: the sheet's swipe or scrim tap -------------------------------

    @Test
    fun `dismiss returns to the bare bar - the bar stays expanded`() {
        assertEquals(
            QuickSettingsUiState.Expanded,
            QuickSettingsUiState.Sheet(QuickSettingType.FOCUS).sheetDismissed(),
        )
    }

    @Test
    fun `dismiss without a sheet is a no-op`() {
        assertEquals(QuickSettingsUiState.Collapsed, QuickSettingsUiState.Collapsed.sheetDismissed())
        assertEquals(QuickSettingsUiState.Expanded, QuickSettingsUiState.Expanded.sheetDismissed())
    }
}
