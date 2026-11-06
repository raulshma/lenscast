package com.raulshma.lenscast.automation

import androidx.compose.runtime.saveable.Saver
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.core.AppJson

/**
 * Rotation/process-death proof for the open editor: the draft serializes
 * through the same Moshi adapter the store persists with
 * ([com.raulshma.lenscast.data.SettingsDataStore]'s AutomationRule codec
 * shape), so an open editor survives configuration changes and process
 * restore instead of silently discarding every in-progress edit. A corrupt
 * snapshot degrades to "editor closed" — the pre-saver behavior — never to a
 * half-valid draft.
 *
 * The pure halves ([encodeRuleDraftJson]/[decodeRuleDraftJson]) exist so the
 * wire round trip is JVM-testable; the Compose [RuleDraftSaver] is the thin
 * rememberSaveable wrapper around them. The saved bytes are the
 * [AutomationRule] projection of the draft, unchanged from the screen-local
 * version this module was extracted from — an in-flight snapshot written by
 * an older build restores identically.
 */
private val ruleDraftJsonAdapter by lazy { AppJson.moshi.adapter(AutomationRule::class.java) }

/** The saved snapshot for [draft]: its [RuleDraft.toRule] projection as Moshi JSON. */
fun encodeRuleDraftJson(draft: RuleDraft): String = ruleDraftJsonAdapter.toJson(draft.toRule())

/**
 * The draft for a saved [json] snapshot, or null when it cannot decode —
 * the "editor closed" degrade. Total on purpose: no snapshot shape may crash
 * the screen or restore a half-valid draft.
 */
fun decodeRuleDraftJson(json: String): RuleDraft? =
    try {
        ruleDraftJsonAdapter.fromJson(json)?.let(RuleDraft::from)
    } catch (_: Exception) {
        null
    }

/** The [rememberSaveable] state-saver delegating to the pure codec functions above. */
val RuleDraftSaver: Saver<RuleDraft?, String> = Saver(
    save = { draft -> draft?.let(::encodeRuleDraftJson) },
    restore = ::decodeRuleDraftJson,
)
