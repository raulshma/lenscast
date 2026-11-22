package com.raulshma.lenscast.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * The one clipboard+toast path for every copyable stream/server URL — the
 * helper [CameraViewModel.copyUrlToClipboard] shape, extracted so the remote
 * console backend copies the DEVICE's URLs through the same rule instead of
 * re-rolling it (derived-truth value first, raw builder as the empty
 * fallback, per-URL label and message). The local backend keeps its private
 * method (frozen-touch constraint for the console wave); its body is
 * byte-for-byte this logic, so a later cleanup can rewire it here with no
 * behavior change.
 */
object UrlClipboard {

    /**
     * Copies [url] (or [fallback] when the derived value is empty — never an
     * empty clip) under [clipLabel], confirming with [copiedMessage]. A
     * value-less pair (both empty) copies nothing and stays silent — there is
     * no URL to share yet.
     */
    fun copy(
        context: Context,
        url: String,
        fallback: String,
        clipLabel: String,
        copiedMessage: String,
    ) {
        val value = url.ifEmpty { fallback }
        if (value.isNotEmpty()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, value))
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        }
    }
}
