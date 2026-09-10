package com.raulshma.lenscast.core

import com.squareup.moshi.Moshi

/**
 * The app's single Moshi instance. Every JSON surface — the Web API handlers,
 * the RecordingConfig intent payload, the update checker, the capture history
 * persistence — serializes through this one configuration, so the wire format
 * cannot drift between callers.
 *
 * Adapters are compile-time generated (moshi-kotlin-codegen, `@JsonClass` on
 * every wire type): no kotlin-reflect at runtime, which keeps the reflective
 * serialization library out of the APK entirely.
 */
object AppJson {
    val moshi: Moshi = Moshi.Builder()
        .build()
}
