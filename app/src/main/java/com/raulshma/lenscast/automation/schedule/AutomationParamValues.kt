package com.raulshma.lenscast.automation.schedule

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The one value-reading/canonicalizing ladder every automation catalog
 * (action, trigger, condition) funnels its string-typed parameters through,
 * so "unparseable or non-finite means null", "invalid folds to the default",
 * and "canonical rendering loses the trailing .0" are each stated exactly
 * once. Internal by design: the catalogs are the public seam, this is their
 * shared engine — extracted from [AutomationActionCatalog] when triggers and
 * conditions joined actions as param-bearing specs.
 */
internal object AutomationParamValues {

    /**
     * The canonical value for one parameter given its raw (possibly absent)
     * wire string, or null when the parameter should be absent.
     */
    fun canonicalValue(param: AutomationParamSpec, raw: String?): String? {
        if (raw == null) {
            // Absent: a required param still needs a value, so it rides the
            // documented default; an optional param's absence IS its
            // "leave unchanged" semantics.
            return if (param.optional) null else param.defaultValue
        }
        val parsed = when (param.kind) {
            ParamKind.BOOL -> parseBool(raw)
            ParamKind.INT -> parseNumber(raw, param)
            ParamKind.FLOAT -> parseNumber(raw, param)
        }
        // Invalid input folds to the default. For a param whose default is
        // the empty sentinel (optional, no persisted default) that renders
        // as absent — same treatment an absent value got.
        return parsed ?: param.defaultValue.takeIf { it.isNotEmpty() }
    }

    /**
     * One spec's effective value at evaluation time: the sanitized entry if
     * present, else the documented default. Evaluation reads through this
     * rather than trusting the persisted map, so a map sanitized to empty
     * (the unknown-key degrade) still evaluates on the documented defaults —
     * the same contract as an absent-required-param fold. Null only for an
     * absent optional param with no default (a legitimate "no opinion") or
     * an unknown name.
     */
    fun paramValue(specs: List<AutomationParamSpec>, params: Map<String, String>, name: String): String? {
        params[name]?.let { return it }
        return specs.firstOrNull { it.name == name }?.defaultValue?.takeIf { it.isNotEmpty() }
    }

    /** [paramValue] read as a BOOL: only the canonical "true" is true. */
    fun boolValue(specs: List<AutomationParamSpec>, params: Map<String, String>, name: String): Boolean =
        paramValue(specs, params, name) == "true"

    /** [paramValue] read as an INT, or null when absent/unparseable. */
    fun intValue(specs: List<AutomationParamSpec>, params: Map<String, String>, name: String): Int? =
        paramValue(specs, params, name)?.toIntOrNull()

    /**
     * Canonicalize one raw INT value into a whole number: parse (lenient
     * about surrounding whitespace and a fractional tail, matching the
     * editor slider's float input), coerce into Int range, round to the
     * nearest integer (ties toward positive infinity — `roundToInt`
     * semantics), return it. Null when the raw string is unparseable or
     * non-finite ("NaN"/"Infinity" parse as doubles but are not values a
     * slider produces). Bound-free on purpose — bounds are the sanitize
     * ladders' business; this is the one INT value-rendering convention the
     * editor and the catalogs share.
     */
    fun canonicalIntValue(raw: String): Int? {
        val value = parseFiniteDouble(raw) ?: return null
        return value
            .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
            .roundToInt()
    }

    /**
     * Canonicalize one raw FLOAT value into the canonical wire string:
     * parse, round to one decimal, render canonically — integral values
     * lose the trailing ".0" (a saved "1" matches the documented default
     * "1"). Null when the raw string is unparseable or non-finite.
     * Bound-free on purpose (see [canonicalIntValue]).
     */
    fun canonicalFloatValue(raw: String): String? {
        val value = parseFiniteDouble(raw) ?: return null
        val scaled = value * 10.0
        // Rounding to one decimal only means anything below the long range;
        // past that the value is already coarser than a tenth, so it rides
        // through unchanged.
        val rounded = if (abs(scaled) < 1e15) scaled.roundToLong() / 10.0 else value
        return rounded.canonicalNumber()
    }

    /** Lenient on case/whitespace, strict on output: "TRUE" → "true". */
    private fun parseBool(raw: String): String? = when (raw.trim().lowercase()) {
        "true" -> "true"
        "false" -> "false"
        else -> null
    }

    /**
     * Parse a numeric wire value and clamp it into [AutomationParamSpec.min]
     * / [max], or null when invalid. Invalid means: unparseable, non-finite
     * (the literals "NaN"/"Infinity" parse as doubles but would survive
     * every comparison-based clamp), or — for an INT param — fractional
     * ("2.5" must not sneak an integral-typed param a decimal). Parsing as a
     * double first keeps huge values like "99999999999999" clamped to the
     * bound instead of rejected, which is what a clamp is for.
     */
    private fun parseNumber(raw: String, param: AutomationParamSpec): String? {
        val value = parseFiniteDouble(raw) ?: return null
        if (param.kind == ParamKind.INT && value != floor(value)) return null
        var clamped = value
        param.min?.let { if (clamped < it) clamped = it }
        param.max?.let { if (clamped > it) clamped = it }
        return clamped.canonicalNumber()
    }

    /**
     * Parse one raw numeric string to a finite double, or null — the one
     * entry point every numeric parse in the catalogs funnels through, so
     * "unparseable or non-finite means null" is stated once.
     */
    private fun parseFiniteDouble(raw: String): Double? =
        raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    /**
     * Canonical decimal rendering: integral values lose the trailing ".0" so
     * a present "1.0" matches the documented default "1" byte for byte (the
     * round trip stays idempotent either way; the match keeps persisted
     * values and defaults comparable in tests and logs).
     */
    private fun Double.canonicalNumber(): String =
        if (this == floor(this) && abs(this) < 1e15) toLong().toString() else toString()
}
