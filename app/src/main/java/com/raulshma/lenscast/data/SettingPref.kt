package com.raulshma.lenscast.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/**
 * One persisted setting as a single declaration — the seam between "a
 * preference key" and "a typed setting": how a value decodes out of the
 * preference map (default folded in) and how it encodes back in (clamp
 * applied). The Settings Store declares one [SettingPref] per setting and
 * derives both its shared [kotlinx.coroutines.flow.StateFlow] and its suspend
 * saver from it, so a key's read convention, write convention, and bounds can
 * never drift apart. Everything here is a pure function over DataStore's
 * preference maps — JVM tests construct them with `preferencesOf` /
 * `emptyPreferences`, no Context or DataStore required.
 */
internal class SettingPref<T>(
    /** The flow's initial value, exactly as the pre-descriptor store passed it to `shared`. */
    val default: T,
    val decode: (Preferences) -> T,
    val encode: (MutablePreferences, T) -> Unit,
)

/**
 * The two stored-form boolean conventions, single-homed. Default-true keys
 * read `!= "false"` (absence and any other string mean on); default-false
 * keys read `== "true"` (absence and any other string mean off). Both
 * conventions write the same canonical `"true"`/`"false"`.
 */
internal fun readBool(prefs: Preferences, key: Preferences.Key<String>, defaultTrue: Boolean): Boolean =
    if (defaultTrue) prefs[key] != "false" else prefs[key] == "true"

internal fun writeBool(prefs: MutablePreferences, key: Preferences.Key<String>, value: Boolean) {
    prefs[key] = if (value) "true" else "false"
}

/**
 * Boolean setting stored as a `"true"`/`"false"` string. [defaultTrue] picks
 * the decode convention: absence means on for default-true keys, off for
 * default-false ones — each key keeps its historical runtime semantics.
 */
internal fun boolPref(key: Preferences.Key<String>, defaultTrue: Boolean): SettingPref<Boolean> =
    SettingPref(
        default = defaultTrue,
        decode = { prefs -> readBool(prefs, key, defaultTrue) },
        encode = { prefs, value -> writeBool(prefs, key, value) },
    )

/** The (min, max) pair a numeric saver coerces through before persisting. */
internal class IntBounds(val min: Int, val max: Int) {
    init {
        require(min <= max) { "Invalid bounds [$min, $max]" }
    }

    fun coerce(value: Int): Int = value.coerceIn(min, max)
}

/** Int setting: absent decodes to [default]; [bounds] coerce on save. */
internal fun intPref(
    key: Preferences.Key<Int>,
    default: Int,
    bounds: IntBounds? = null,
): SettingPref<Int> = SettingPref(
    default = default,
    decode = { prefs -> prefs[key] ?: default },
    encode = { prefs, value -> prefs[key] = bounds?.coerce(value) ?: value },
)

/** Long setting: absent decodes to [default]; stored raw. */
internal fun longPref(key: Preferences.Key<Long>, default: Long): SettingPref<Long> = SettingPref(
    default = default,
    decode = { prefs -> prefs[key] ?: default },
    encode = { prefs, value -> prefs[key] = value },
)

/** String setting: absent decodes to [default]; stored raw. */
internal fun stringPref(key: Preferences.Key<String>, default: String): SettingPref<String> = SettingPref(
    default = default,
    decode = { prefs -> prefs[key] ?: default },
    encode = { prefs, value -> prefs[key] = value },
)

/**
 * Enum setting stored as its name: a null or unknown string decodes to
 * [fallback] — the same tolerant parsing as `core/EnumParsing.parseEnum`,
 * expressed over the enum's declaring class so the factory stays usable as
 * a plain (non-inline) generic.
 */
internal fun <E : Enum<E>> enumPref(key: Preferences.Key<String>, fallback: E): SettingPref<E> {
    // Enum constants with bodies carry an anonymous runtime class, which
    // Enum.valueOf would reject; the declaring class is the enum type itself.
    @Suppress("UNCHECKED_CAST")
    val declaringClass: Class<E> =
        (fallback.javaClass.enclosingClass as? Class<E>)?.takeIf { it.isEnum } ?: fallback.javaClass
    return SettingPref(
        default = fallback,
        decode = { prefs ->
            val name = prefs[key]
            if (name == null) {
                fallback
            } else {
                runCatching { java.lang.Enum.valueOf(declaringClass, name) }.getOrDefault(fallback)
            }
        },
        encode = { prefs, value -> prefs[key] = value.name },
    )
}
