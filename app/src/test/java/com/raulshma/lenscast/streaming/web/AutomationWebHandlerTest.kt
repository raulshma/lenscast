package com.raulshma.lenscast.streaming.web

import android.app.Application
import com.raulshma.lenscast.automation.schedule.AutomationFireJournal
import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.raulshma.lenscast.automation.schedule.AutomationSchedulePolicy
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.model.AutomationStateDto
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric pass over the REAL [AutomationWebHandler] against the REAL
 * [SettingsDataStore] (Preferences DataStore on a per-test temp dir): the PUT
 * contract the web dashboard depends on.
 *
 * **The regression this pins:** a rule saved through the API used to persist
 * without an id — the store decode minted an ephemeral identity on every
 * read, so the fire journal keyed entries on ids that vanished at the next
 * decode, the dedupe/cooldown guarantees collapsed, and the PUT response
 * could echo the pre-write rules (the flows re-decode on a collector's beat
 * after the save lands). The handler now mints blank wire ids at the
 * boundary (the in-app save path's convention) and both routes answer from a
 * fresh disk read.
 *
 * **Covered here** (all real code paths): blank-id minting, the sanitizer's
 * clamps as the client sees them, PUT answering with exactly what a
 * subsequent GET reads (same ids — the freshness contract), explicit ids
 * surviving a re-save, and the persisted JSON carrying an id per rule (the
 * store invariant, asserted through the handler here and at the codec level
 * in SettingsDataStoreAutomationPrefsTest), and GET exposing the fire
 * journal as a decoded `lastFiredAt` per rule (null until a fire is
 * recorded; both journal value kinds decode through
 * AutomationSchedulePolicy.decodeOccurrenceKey).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AutomationWebHandlerTest.PlainApplication::class)
class AutomationWebHandlerTest {

    class PlainApplication : Application()

    private lateinit var handler: AutomationWebHandler

    private lateinit var journal: AutomationFireJournal

    private val stateAdapter = AppJson.moshi.adapter(AutomationStateDto::class.java)

    @Before
    fun setUp() {
        journal = AutomationFireJournal(RuntimeEnvironment.getApplication())
        handler = AutomationWebHandler(SettingsDataStore(RuntimeEnvironment.getApplication()), journal)
    }

    private fun putOneRule(id: String = ""): AutomationStateDto = runBlocking {
        stateAdapter.fromJson(
            handler.put(
                // One deliberately invalid rule: blank id, unknown trigger,
                // unknown condition/action, out-of-bounds fields — and an
                // unknown missed-run policy with an out-of-span window.
                """{"enabled":true,"rules":[{"id":"$id","name":"Probe","enabled":true,""" +
                    """"triggerType":"not_a_trigger","minuteOfDay":2500,"daysMask":0,""" +
                    """"conditions":[{"type":"nope"}],"cooldownSeconds":-5,""" +
                    """"missedRunPolicy":"fire_anyway","missedWindowMinutes":99999,"actions":[""" +
                    """{"type":"torch","params":{"enabled":"true","strength":"99"}},{"type":"nope"}]}]}""",
            ),
        )!!
    }

    @Test
    fun `put mints ids clamps fields and answers with the persisted truth`() = runBlocking {
        val response = putOneRule()
        val rule = response.rules.single()

        // The wire's blank id minted into a real UUID...
        UUID.fromString(rule.id)
        // ...the clamps applied as the client sees them...
        assertEquals(1_439, rule.minuteOfDay)
        assertEquals(1, rule.daysMask)
        assertEquals(0, rule.cooldownSeconds)
        // ...the unknown missed-run policy folded to the historical default
        // and the window clamped into its span...
        assertEquals("catch_up", rule.missedRunPolicy)
        assertEquals(1_440, rule.missedWindowMinutes)
        assertEquals(listOf("torch"), rule.actions.map { it.type })
        assertEquals("8", rule.actions.single().params["strength"])
        assertTrue(response.enabled)
        // ...and the answer IS the disk state: a fresh GET reads the same id.
        assertEquals(listOf(rule.id), stateAdapter.fromJson(handler.get())!!.rules.map { it.id })
    }

    @Test
    fun `put round-trips a valid missed-run policy through the disk`() = runBlocking {
        val body = """{"enabled":true,"rules":[{"id":"","name":"Strict","enabled":true,""" +
            """"triggerType":"time_of_day","minuteOfDay":420,"daysMask":127,""" +
            """"missedRunPolicy":"window","missedWindowMinutes":45,""" +
            """"actions":[{"type":"torch","params":{"enabled":"true"}}]}]}"""
        val response = stateAdapter.fromJson(handler.put(body))!!
        assertEquals("window", response.rules.single().missedRunPolicy)
        assertEquals(45, response.rules.single().missedWindowMinutes)
        // The GET agrees — the persisted shape, not just the response.
        val fresh = stateAdapter.fromJson(handler.get())!!.rules.single()
        assertEquals("window", fresh.missedRunPolicy)
        assertEquals(45, fresh.missedWindowMinutes)
    }

    @Test
    fun `put with a blank id keeps the identity stable across re-saves and reads`() = runBlocking {
        val first = putOneRule().rules.single().id
        // The dashboard's edit flow: echo the id the device minted back on
        // the next save. The id must survive — a new one would orphan the
        // rule's fire-journal entries.
        val second = putOneRule(id = first).rules.single().id
        assertEquals(first, second)
        assertEquals(first, stateAdapter.fromJson(handler.get())!!.rules.single().id)
    }

    @Test
    fun `put answers with the new rules even when the flows lag the write`() = runBlocking {
        // Prime the flows with one rule, then replace the list wholesale.
        val first = putOneRule().rules.single().id
        val body = """{"enabled":true,"rules":[{"id":"","name":"Second","enabled":true,""" +
            """"actions":[{"type":"torch","params":{"enabled":"true"}}]}]}"""
        val response = stateAdapter.fromJson(handler.put(body))!!
        // The response is a disk read taken after the save: the OLD rule is
        // gone (the wholesale convention) and the new id is minted — no
        // echo of the pre-write list.
        assertEquals(listOf("Second"), response.rules.map { it.name })
        val newId = response.rules.single().id
        UUID.fromString(newId)
        assertNotEquals(first, newId)
        assertEquals(listOf(newId), stateAdapter.fromJson(handler.get())!!.rules.map { it.id })
    }

    @Test
    fun `get exposes the decoded fire journal as lastFiredAt`() = runBlocking {
        // No journal record yet: the field reads null ("never fired"), which
        // Moshi keeps off the wire.
        val ruleId = putOneRule().rules.single().id
        assertNull(stateAdapter.fromJson(handler.get())!!.rules.single().lastFiredAt)

        // An event-kind journal value (epoch millis, ≥ the threshold) passes
        // through unchanged.
        val eventMs = 1_700_000_012_345L
        journal.record(ruleId, eventMs)
        assertEquals(eventMs, stateAdapter.fromJson(handler.get())!!.rules.single().lastFiredAt)

        // A time-occurrence key decodes to its local wall-clock instant —
        // the same decode the UI and the web client see.
        val key = AutomationSchedulePolicy.occurrenceKey(
            AutomationRule(id = ruleId, minuteOfDay = 8 * 60),
            epochDay = 20_000L,
        )
        journal.record(ruleId, key)
        assertEquals(
            AutomationSchedulePolicy.decodeOccurrenceKey(key, TimeZone.getDefault()),
            stateAdapter.fromJson(handler.get())!!.rules.single().lastFiredAt,
        )
    }
}
