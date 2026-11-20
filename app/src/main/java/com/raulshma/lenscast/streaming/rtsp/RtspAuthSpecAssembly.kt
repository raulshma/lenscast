package com.raulshma.lenscast.streaming.rtsp

import androidx.datastore.preferences.core.Preferences
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.data.authSettingsPref
import com.raulshma.lenscast.data.rtspBasicAuthCompatPref

/**
 * The RTSP auth spec's assembly: its TWO stored inputs — the auth settings
 * and the Basic-compat flag — decode out of ONE preference map and fold into
 * a single [RtspAuthSpec] (or null = auth off). Pure and JVM-testable; the
 * production path is [com.raulshma.lenscast.data.SettingsDataStore.readFresh]
 * handing it the file's current state.
 *
 * Why one pass matters: the two inputs live in separate keys but must never
 * be answered from different instants. The shared StateFlows start at their
 * descriptor defaults and settle on the share-in collector's own beat, so
 * the old `.value` reads could pair NEW credentials with the OLD compat
 * flag or vice versa — a section PUT lands both in one atomic edit while
 * the auth and compat collectors fire separately, and a fresh process
 * serving RTSP before share-in completes would briefly challenge with
 * defaults against real disk credentials. Decoding both from one snapshot
 * makes the spec self-consistent by construction: whatever the disk held
 * at the pass, one coherent challenge scheme rides with one coherent
 * credential set — the same bug family commit 368515b fixed for automation
 * actions with its disk-truth merge base (see the domain glossary).
 */

/**
 * The pure inputs → spec fold: null when auth is off or incomplete (no
 * username / no password hash — the server treats null as "auth off"),
 * otherwise the spec pairing the credentials with the compat flag as they
 * stood together.
 */
internal fun assembleRtspAuthSpec(settings: StreamAuthSettings, basicAuthCompat: Boolean): RtspAuthSpec? {
    if (!settings.enabled || settings.username.isEmpty() || settings.passwordHash.isEmpty()) return null
    return RtspAuthSpec(
        settings.username,
        settings.passwordHash,
        settings.rtspDigestHa1,
        basicAuthCompat = basicAuthCompat,
    )
}

/**
 * The disk-pass body: decodes BOTH descriptors from the same [Preferences]
 * snapshot and folds them. This is the exact function the manager's auth
 * spec provider runs inside `readFresh` — one file pass, one coherent spec.
 */
internal fun rtspAuthSpecFromPrefs(prefs: Preferences): RtspAuthSpec? =
    assembleRtspAuthSpec(authSettingsPref.decode(prefs), rtspBasicAuthCompatPref.decode(prefs))
