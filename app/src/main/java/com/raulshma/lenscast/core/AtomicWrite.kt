package com.raulshma.lenscast.core

import android.util.Log
import com.squareup.moshi.JsonAdapter
import java.io.File

/**
 * The write half of the file-backed-store shape (AuthSessionStore,
 * DetectionEventStore, StreamStateJournal): create the parent directory,
 * write a `.tmp` sibling, then rename over the target — deleting the target
 * first when the rename is refused, so a crash mid-write never leaves a
 * half-written store file. Throws on failure; the caller owns the catch and
 * the "what does losing this copy cost" log line.
 */
fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        delete()
        tmp.renameTo(this)
    }
}

/**
 * The write half plus the catch-and-warn, for stores where losing the
 * persisted copy costs history or a re-login — never live data — so the
 * failure is one warning line carrying the store-specific cost and the
 * caller moves on. False when the save was lost.
 */
fun File.writeAtomicallyOrWarn(text: String, warn: String): Boolean = try {
    writeAtomically(text)
    true
} catch (e: Exception) {
    Log.w(TAG, warn, e)
    false
}

/**
 * The read half of the same shape: [default] when the file does not exist
 * yet or its JSON does not parse — a corrupt or half-written store starts
 * clean, with one warning line carrying the store-specific cost.
 */
fun <T> File.readJsonOrDefault(adapter: JsonAdapter<T>, default: T, warn: String): T = try {
    if (!exists()) {
        default
    } else {
        adapter.fromJson(readText()) ?: default
    }
} catch (e: Exception) {
    Log.w(TAG, warn, e)
    default
}

private const val TAG = "AtomicWrite"
