package com.raulshma.lenscast.streaming.rtsp

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.emptyPreferences
import com.raulshma.lenscast.data.SettingEntry
import com.raulshma.lenscast.data.StreamAuthSettings
import com.raulshma.lenscast.data.authSettingsPref
import com.raulshma.lenscast.data.rtspBasicAuthCompatPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The RTSP auth spec's pure assembly ([assembleRtspAuthSpec] /
 * [rtspAuthSpecFromPrefs]): null = auth off in every incomplete shape, and
 * otherwise ONE spec whose challenge scheme rides with the credential set it
 * was decoded beside — both inputs fold from the same preference snapshot,
 * so a mixed new/old pairing is unrepresentable by construction. Same pure
 * idiom as SettingEntryTest / SettingPrefTest — no Context or DataStore.
 */
class RtspAuthSpecAssemblyTest {

    /** Encodes the entries into one map — the section PUT's atomic write, miniaturized. */
    private fun assembledSpec(vararg entries: SettingEntry<*>): RtspAuthSpec? {
        val prefs: MutablePreferences = emptyPreferences().toMutablePreferences()
        entries.forEach { it.encode(prefs) }
        return rtspAuthSpecFromPrefs(prefs)
    }

    @Test
    fun `auth off assembles null — the server treats null as auth off`() {
        assertNull(assembleRtspAuthSpec(StreamAuthSettings(enabled = false, username = "u", passwordHash = "h"), false))
    }

    @Test
    fun `incomplete credentials assemble null even when auth is on`() {
        assertNull(assembleRtspAuthSpec(StreamAuthSettings(enabled = true, username = "", passwordHash = "h"), false))
        assertNull(assembleRtspAuthSpec(StreamAuthSettings(enabled = true, username = "u", passwordHash = ""), false))
    }

    @Test
    fun `a complete spec pairs the credentials with the compat flag and lowercases the ha1`() {
        val spec = assembleRtspAuthSpec(
            StreamAuthSettings(enabled = true, username = "cam", passwordHash = "hash", rtspDigestHa1 = "ABCDEF"),
            basicAuthCompat = true,
        )
        assertNotNull(spec)
        assertEquals("cam", spec?.username)
        assertEquals("hash", spec?.passwordHash)
        assertEquals("abcdef", spec?.digestHa1)
        assertEquals(true, spec?.basicAuthCompat)
    }

    @Test
    fun `empty prefs decode to null — a fresh disk means auth off`() {
        // Nothing stored: the descriptor defaults fold to disabled auth. This
        // is the fresh-process shape the disk-truth read fixes — the old
        // flow-default reads answered the same null for a DIFFERENT reason
        // (lagging flows instead of a genuinely empty disk).
        assertNull(rtspAuthSpecFromPrefs(emptyPreferences()))
    }

    @Test
    fun `one snapshot decodes both inputs — new credentials ride with the new compat flag`() {
        // Stored truth: auth on with a Digest HA1 AND Basic compat on — the
        // section-PUT shape that used to race the two lagging flows.
        val spec = assembledSpec(
            SettingEntry(
                authSettingsPref,
                StreamAuthSettings(enabled = true, username = "cam", passwordHash = "hash", rtspDigestHa1 = "ABC"),
            ),
            SettingEntry(rtspBasicAuthCompatPref, true),
        )
        assertEquals(true, spec?.basicAuthCompat)
        assertEquals("abc", spec?.digestHa1)

        // Compat off decodes as off from the same pass: the challenge scheme
        // follows the flag it was stored beside, never a lagging mirror.
        val digestSpec = assembledSpec(
            SettingEntry(
                authSettingsPref,
                StreamAuthSettings(enabled = true, username = "cam", passwordHash = "hash", rtspDigestHa1 = "ABC"),
            ),
        )
        assertEquals(false, digestSpec?.basicAuthCompat)
    }

    @Test
    fun `a disabled stored auth yields null even beside a compat write`() {
        assertNull(
            assembledSpec(
                SettingEntry(authSettingsPref, StreamAuthSettings(enabled = false, username = "u", passwordHash = "h")),
                SettingEntry(rtspBasicAuthCompatPref, true),
            ),
        )
        // Sanity: the compat key WAS written and decodes back.
        val prefs = emptyPreferences().toMutablePreferences()
        SettingEntry(rtspBasicAuthCompatPref, true).encode(prefs)
        assertEquals(true, rtspBasicAuthCompatPref.decode(prefs))
    }
}
