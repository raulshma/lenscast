package com.raulshma.lenscast.core

/**
 * The one idiom for tolerant enum parsing: a null or unknown name falls back,
 * a valid name parses (case-sensitively, per `valueOf`). Every
 * `try { X.valueOf(s) } catch { default }` / `runCatching { ... }` site in the
 * store, the Web API handlers, and the ViewModels routes through one of these
 * two functions so the fallback is an explicit, greppable argument.
 */
inline fun <reified T : Enum<T>> parseEnum(name: String?, fallback: T): T =
    parseEnumOrNull<T>(name) ?: fallback

/** The skip-save variant: null or unknown yields null, and the caller skips. */
inline fun <reified T : Enum<T>> parseEnumOrNull(name: String?): T? =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
