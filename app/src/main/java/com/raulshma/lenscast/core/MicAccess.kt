package com.raulshma.lenscast.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * One adapter for microphone access: the granted check plus the shared
 * "degraded to video-only" phrasing used by every warn-and-continue site,
 * so the policy text can't drift between callers.
 */
object MicAccess {
    const val PERMISSION = Manifest.permission.RECORD_AUDIO

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Full warn message for a feature that wanted audio and can't have it. */
    fun degradedMessage(feature: String): String =
        "Microphone permission not granted. $feature without audio."
}
