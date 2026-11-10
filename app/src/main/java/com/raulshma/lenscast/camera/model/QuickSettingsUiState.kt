package com.raulshma.lenscast.camera.model

/**
 * The camera screen's quick-settings panel state — one sealed machine for
 * what the old `quickSettingsExpanded: Boolean` + `activeSetting: QuickSettingType?`
 * pair encoded across three shapes:
 *
 * - [Collapsed] — bar hidden, no sheet.
 * - [Expanded] — bar visible, no sheet.
 * - [Sheet] — bar visible with [Sheet.setting]'s editor sheet open.
 *
 * The invariant "a sheet is open only while the bar is expanded" is
 * structural here: [Sheet] cannot exist without the bar, because there is no
 * state with a sheet and a collapsed bar — the old pair held that invariant
 * only by callback discipline (every toggle had to remember to also clear
 * the active setting). The screen is the only writer, through the three
 * transitions; the composables that still take booleans read [isBarExpanded]
 * and [openSetting] projections off the state at the call site.
 *
 * The panel is plain `remember` state on the screen — it intentionally does
 * not survive rotation, and neither does this machine.
 */
sealed interface QuickSettingsUiState {

    /** Bar hidden, no sheet. The screen's initial state. */
    data object Collapsed : QuickSettingsUiState

    /** Bar visible, no sheet open. */
    data object Expanded : QuickSettingsUiState

    /** Bar visible with the editor sheet for [setting] open on top of it. */
    data class Sheet(val setting: QuickSettingType) : QuickSettingsUiState

    /** Whether the horizontal quick-settings bar is visible. */
    val isBarExpanded: Boolean
        get() = this !is Collapsed

    /** The sheet's open control, null when no sheet is open. */
    val openSetting: QuickSettingType?
        get() = (this as? Sheet)?.setting

    /**
     * The collapse/expand toggle's transition: collapsed opens the bar,
     * anything else closes the whole panel — collapsing with a sheet open
     * closes the sheet too (the old toggle's "if (!expanded) activeSetting =
     * null" is the [Sheet] → [Collapsed] arm here).
     */
    fun toggled(): QuickSettingsUiState = when (this) {
        Collapsed -> Expanded
        else -> Collapsed
    }

    /**
     * A pill tap's transition: tapping the open sheet's own control closes
     * the sheet (back to the bare bar), tapping any other control moves the
     * sheet to it, tapping from the bare bar opens the sheet.
     */
    fun settingTapped(type: QuickSettingType): QuickSettingsUiState = when {
        this is Sheet && setting == type -> Expanded
        else -> Sheet(type)
    }

    /**
     * The sheet's dismiss transition (swipe or scrim tap): back to the bare
     * bar — the bar stays expanded, matching the old "activeSetting = null".
     */
    fun sheetDismissed(): QuickSettingsUiState = when (this) {
        is Sheet -> Expanded
        else -> this
    }
}
