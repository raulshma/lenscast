package com.raulshma.lenscast.automation.schedule

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.parseWireNameOrNull
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi

/**
 * The shared JSON shape behind the three rule-vocabulary enums' wire
 * adapters ([AutomationTriggerKind.WIRE_ADAPTER],
 * [ConditionMode.WIRE_ADAPTER], [MissedRunPolicy.WIRE_ADAPTER]): one JSON
 * string in, one enum constant out, one wire id back. The id encoding is
 * what keeps the persisted-rule JSON and the web API byte-compatible across
 * the stringly→typed conversion — the adapters write `enum.id` (the exact
 * canonical string the String fields held) and decode through the enum's
 * tolerant [parse], so an unknown id folds to the same fallback the
 * sanitizer folds.
 *
 * Writers: every automation JSON surface (the store codec, the draft saver,
 * the web handler) serializes through [AutomationRuleJson]'s instance, which
 * has these adapters registered — serialization through plain
 * [AppJson.moshi] would emit enum NAMES ("TIME_OF_DAY"), a wire break, so no
 * rule graph adapter may be built off the bare instance. Readers: the same
 * surfaces decode through them; a JSON null stays null (the generated
 * AutomationRule adapter's constructor null-check rejects it exactly like it
 * rejected a null String field before the conversion).
 */
private class WireIdJsonAdapter<E>(
    private val idOf: (E) -> String,
    private val parse: (String?) -> E,
) : JsonAdapter<E>() {

    /** The wire id, or the fallback for anything the parse does not know. */
    override fun fromJson(reader: JsonReader): E = parse(reader.nextString())

    /** The constant's exact canonical wire id — the persisted string id. */
    override fun toJson(writer: JsonWriter, value: E?) {
        if (value == null) writer.nullValue() else writer.value(idOf(value))
    }
}

/**
 * Which cue fires an [AutomationRule] — the typed form of the rule's
 * `triggerType` field, one constant per [AutomationTriggerCatalog] id. The
 * enum is the decode/compare form (the store codec, the schedule policy and
 * the editor logic switch on it); the CATALOG is still the parameter-schema
 * authority and the published picker vocabulary, so its `typeFor(id)` lookups
 * keep taking the string id ([id]).
 *
 * States and the value flow: written by the JSON decode (unknown wire ids
 * fold to [TIME_OF_DAY] — the historical default and the sanitizer's reset
 * target, so a rule written by a newer app version degrades to a time
 * trigger instead of firing on the wrong cue or being dropped), by the
 * editor's trigger picker (through [RuleDraft]'s string id mirror) and by
 * nothing else — the sanitizer keeps a decoded constant as-is and can never
 * mint an out-of-catalog one. Read by the schedule policy (the time-tick gate
 * and the event matcher), the executor's engine loop, and the
 * GET-decoration mapper; nobody reads the raw field as a string anymore
 * except through [id] for catalog lookups.
 */
enum class AutomationTriggerKind(val id: String) {
    TIME_OF_DAY(AutomationTriggerCatalog.TIME_OF_DAY),
    MOTION_DETECTED(AutomationTriggerCatalog.MOTION_DETECTED),
    SOUND_DETECTED(AutomationTriggerCatalog.SOUND_DETECTED),
    TAMPER_DETECTED(AutomationTriggerCatalog.TAMPER_DETECTED),
    CHARGING_CHANGED(AutomationTriggerCatalog.CHARGING_CHANGED),
    BATTERY_LEVEL(AutomationTriggerCatalog.BATTERY_LEVEL),
    STREAMING_CHANGED(AutomationTriggerCatalog.STREAMING_CHANGED),
    VIEWERS_CHANGED(AutomationTriggerCatalog.VIEWERS_CHANGED),
    APP_STARTED(AutomationTriggerCatalog.APP_STARTED),
    ;

    companion object {

        /** The decode fallback — the historical first (and reset) trigger kind. */
        val DEFAULT: AutomationTriggerKind = TIME_OF_DAY

        private val byId: Map<String, AutomationTriggerKind> = entries.associateBy { it.id }

        /**
         * The tolerant wire-id decode: a null, blank, or unknown id folds to
         * [DEFAULT] — the same verdict [AutomationSchedulePolicy.sanitizeRule]
         * produced for an unknown trigger before the conversion, and
         * [parseWireNameOrNull]'s convention (the wire ids are not the enum
         * names, so plain `parseEnum`/`valueOf` cannot decode them).
         */
        fun parse(id: String?): AutomationTriggerKind =
            parseWireNameOrNull(id, byId) ?: DEFAULT

        /** The skip-fold variant: null, blank, or unknown yields null. */
        fun parseOrNull(id: String?): AutomationTriggerKind? =
            parseWireNameOrNull(id, byId)

        /**
         * The persisted/web JSON adapter for the rule's `triggerType` field —
         * registered on [AutomationRuleJson], the only Moshi instance the
         * rule graph may serialize through.
         */
        val WIRE_ADAPTER: JsonAdapter<AutomationTriggerKind> =
            WireIdJsonAdapter(AutomationTriggerKind::id, AutomationTriggerKind::parse).nullSafe()
    }
}

/**
 * How an [AutomationRule]'s multiple preconditions combine — the typed form
 * of the rule's `conditionMode` field. [ALL] (every condition must hold) is
 * the default and the fold target: the JSON decode maps anything that is not
 * exactly the [ANY] id to [ALL], the same fold the sanitizer made when the
 * field was a string, so a corrupt or future-policy id degrades to the
 * stricter semantics instead of widening fires.
 *
 * States and the value flow: written by the JSON decode and the editor's
 * match-any switch (through [RuleDraft]'s string mirror); read by
 * [AutomationConditionPolicy.conditionsMet] and the editor's card/preview
 * joiner ("and"/"or"). Not a free-form vocabulary — exactly two constants,
 * pinned by the fuzz test's round trip.
 */
enum class ConditionMode(val id: String) {
    /** Every [AutomationRule.conditions] entry must hold for the rule to fire. */
    ALL("all"),

    /** At least one [AutomationRule.conditions] entry must hold. */
    ANY("any"),
    ;

    companion object {

        /** The decode fallback — the stricter, historical default. */
        val DEFAULT: ConditionMode = ALL

        private val byId: Map<String, ConditionMode> = entries.associateBy { it.id }

        /**
         * The tolerant wire-id decode: a null, blank, or unknown id folds to
         * [DEFAULT]; only the exact [ANY] id decodes to [ANY] — the same
         * "exact-match ANY, everything else ALL" verdict the sanitizer made
         * for the string field, via [parseWireNameOrNull]'s convention.
         */
        fun parse(id: String?): ConditionMode = parseWireNameOrNull(id, byId) ?: DEFAULT

        /** The skip-fold variant: null, blank, or unknown yields null. */
        fun parseOrNull(id: String?): ConditionMode? = parseWireNameOrNull(id, byId)

        /**
         * The persisted/web JSON adapter for the rule's `conditionMode`
         * field — registered on [AutomationRuleJson], the only Moshi instance
         * the rule graph may serialize through.
         */
        val WIRE_ADAPTER: JsonAdapter<ConditionMode> =
            WireIdJsonAdapter(ConditionMode::id, ConditionMode::parse).nullSafe()
    }
}

/**
 * What happens when a time rule's scheduled moment passes without a firing —
 * the typed form of the rule's `missedRunPolicy` field, meaningful only for
 * [AutomationTriggerKind.TIME_OF_DAY] rules (events fire in the present and
 * cannot miss). [CATCH_UP] (the default) keeps the historical behavior:
 * stateful actions fire however late, one-shot actions within the global
 * grace; [SKIP] means "at the scheduled time or not at all"; [WINDOW] bounds
 * the catch-up to `missedWindowMinutes` past the scheduled minute and lets
 * every action ride inside it.
 *
 * States and the value flow: written by the JSON decode (unknown wire ids
 * fold to [CATCH_UP] — the historical behavior, so an unknown future policy
 * degrades to catching up instead of silently skipping or widening fires) and
 * the editor's policy chips (through [RuleDraft]'s string mirror); read by
 * the schedule policy's lateness gates and the editor's card/preview "IF
 * MISSED" line.
 */
enum class MissedRunPolicy(val id: String) {
    /** Fire however late the tick is (stateful; one-shots within the global grace). */
    CATCH_UP("catch_up"),

    /** At the scheduled time or not at all. */
    SKIP("skip"),

    /** Fire late only within the rule's missed-window span; every action rides. */
    WINDOW("window"),
    ;

    companion object {

        /** The decode fallback — the historical catch-up behavior. */
        val DEFAULT: MissedRunPolicy = CATCH_UP

        private val byId: Map<String, MissedRunPolicy> = entries.associateBy { it.id }

        /**
         * The tolerant wire-id decode: a null, blank, or unknown id folds to
         * [DEFAULT] — the same verdict the sanitizer made for an unknown
         * policy, via [parseWireNameOrNull]'s convention.
         */
        fun parse(id: String?): MissedRunPolicy = parseWireNameOrNull(id, byId) ?: DEFAULT

        /** The skip-fold variant: null, blank, or unknown yields null. */
        fun parseOrNull(id: String?): MissedRunPolicy? = parseWireNameOrNull(id, byId)

        /**
         * The persisted/web JSON adapter for the rule's `missedRunPolicy`
         * field — registered on [AutomationRuleJson], the only Moshi instance
         * the rule graph may serialize through.
         */
        val WIRE_ADAPTER: JsonAdapter<MissedRunPolicy> =
            WireIdJsonAdapter(MissedRunPolicy::id, MissedRunPolicy::parse).nullSafe()
    }
}

/**
 * The one Moshi instance for the automation rule JSON graph: [AppJson] plus
 * the three rule-vocabulary wire adapters ([AutomationTriggerKind.WIRE_ADAPTER],
 * [ConditionMode.WIRE_ADAPTER], [MissedRunPolicy.WIRE_ADAPTER]) that encode
 * the enums as their canonical string ids. Every surface that JSON-encodes
 * [AutomationRule] — directly or nested inside a web DTO — builds its
 * adapters from here: the store's persistence codec
 * (`SettingsDataStore.parseAutomationRules`/`serializeAutomationRules`), the
 * editor draft saver (`RuleDraftSaver`), and the `/api/automation` handler's
 * state/update adapters. Adapters built from plain [AppJson.moshi] would
 * fall through to Moshi's name-based enum encoding and emit "TIME_OF_DAY" —
 * a wire break against every persisted document and web contract — so this
 * instance is the structural guard, and a new automation JSON surface gets
 * the wire for free by building from it.
 *
 * The derivation keeps the app's single-Moshi discipline: it is [AppJson]'s
 * configuration extended by exactly the automation enums' id adapters
 * (`newBuilder().add(...)`), so every other registered adapter (the
 * `Secret` wire type and the codegen'd graph) behaves identically here.
 */
object AutomationRuleJson {
    val moshi: Moshi = AppJson.moshi.newBuilder()
        .add(AutomationTriggerKind::class.java, AutomationTriggerKind.WIRE_ADAPTER)
        .add(ConditionMode::class.java, ConditionMode.WIRE_ADAPTER)
        .add(MissedRunPolicy::class.java, MissedRunPolicy.WIRE_ADAPTER)
        .build()
}
