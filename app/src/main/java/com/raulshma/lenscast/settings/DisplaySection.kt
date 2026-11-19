package com.raulshma.lenscast.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.raulshma.lenscast.R

/**
 * The Display section shared by the camera settings screen and the app
 * settings screen — the preview-visibility toggle both rendered as verbatim
 * copies. One home for the section; the two screens keep their own
 * [SettingsViewModel] instances (a scoping change is deliberately out of
 * scope), so the toggle callback stays a plain parameter. The dynamic-color
 * row is null-gated: it is an app-appearance setting, so only the app screen
 * passes it, and it is Android 12+-gated to match the theme (below S the
 * dynamic scheme never applies, so the switch would be dead).
 */
@Composable
fun DisplaySettingsSection(
    showPreview: Boolean,
    onTogglePreview: (Boolean) -> Unit,
    dynamicTheming: Boolean? = null,
    onToggleDynamicTheming: ((Boolean) -> Unit)? = null,
) {
    SettingsSection(title = stringResource(R.string.settings_section_display)) {
        SwitchSetting(
            title = stringResource(R.string.settings_show_preview),
            checked = showPreview,
            onCheckedChange = onTogglePreview,
        )
        if (dynamicTheming != null && onToggleDynamicTheming != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            SwitchSetting(
                title = stringResource(R.string.settings_dynamic_color),
                description = stringResource(R.string.settings_dynamic_color_description),
                checked = dynamicTheming,
                onCheckedChange = onToggleDynamicTheming,
            )
        }
    }
}
