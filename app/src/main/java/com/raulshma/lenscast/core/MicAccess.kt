package com.raulshma.lenscast.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The warn-and-degrade decision for a feature that wants audio: proceed when
 * the feature is off or the mic is granted, degrade with the shared warning
 * when the feature is on but the permission is not. Pure — callers refresh
 * their cached permission state (or ask [MicAccess.isGranted] live) and then
 * consult this, so no call site hand-rolls the condition or the toast text.
 */
sealed class MicStartDecision {
    data object Proceed : MicStartDecision()
    data class Degrade(val warning: String) : MicStartDecision()
}

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

    fun startDecision(featureEnabled: Boolean, granted: Boolean, featureLabel: String): MicStartDecision =
        if (featureEnabled && !granted) {
            MicStartDecision.Degrade(degradedMessage(featureLabel))
        } else {
            MicStartDecision.Proceed
        }

    /** The ask-once gate behind the camera screen's auto permission prompt. */
    fun shouldAutoRequest(featureReady: Boolean, granted: Boolean, alreadyRequested: Boolean): Boolean =
        featureReady && !granted && !alreadyRequested
}
