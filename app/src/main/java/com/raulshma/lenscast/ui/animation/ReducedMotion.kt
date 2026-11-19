package com.raulshma.lenscast.ui.animation

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberReducedMotion(): Boolean {
    val contentResolver = LocalContext.current.contentResolver
    return remember {
        Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

@Composable
fun <T> expressiveMotionSpec(base: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
    if (rememberReducedMotion()) snap() else base
