package com.raulshma.lenscast.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * The app's single Moshi instance. Every JSON surface — the Web API handlers,
 * the RecordingConfig intent payload, the update checker, the capture history
 * persistence — serializes through this one configuration
 * (KotlinJsonAdapterFactory), so the wire format cannot drift between callers.
 */
object AppJson {
    val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
}
