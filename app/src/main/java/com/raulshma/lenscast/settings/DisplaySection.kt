package com.raulshma.lenscast.settings

import androidx.compose.runtime.Composable

/**
 * The Display section shared by the camera settings screen and the app
 * settings screen — the preview-visibility toggle both rendered as verbatim
 * copies. One home for the section; the two screens keep their own
 * [SettingsViewModel] instances (a scoping change is deliberately out of
 * scope), so the toggle callback stays a plain parameter.
 */
@Composable
fun DisplaySettingsSection(
    showPreview: Boolean,
    onTogglePreview: (Boolean) -> Unit,
) {
    SettingsSection(title = "Display") {
        SwitchSetting(
            title = "Show Camera Preview",
            checked = showPreview,
            onCheckedChange = onTogglePreview,
        )
    }
}
