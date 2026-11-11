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
 *
 * The one registered custom adapter is the [Secret] wire type's
 * [SecretWireAdapter]: every `Secret` field in the codegen'd DTO graph
 * serializes blank through it.
 *
 * One carve-out: the automation rule vocabulary (`AutomationTriggerKind`,
 * `ConditionMode`, `MissedRunPolicy`) is NOT registered here — those enums
 * encode as their canonical string ids only through the derived
 * `AutomationRuleJson.moshi` (see its KDoc; plain `AppJson.moshi` would
 * emit enum names). Any surface that JSON-encodes `AutomationRule`, directly
 * or nested in a DTO, builds its adapters from that instance.
 */
object AppJson {
    val moshi: Moshi = Moshi.Builder()
        .add(Secret::class.java, SecretWireAdapter)
        .build()
}
