package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.AppJson
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted-session role contract: records from before roles existed
 * (no `r` field — the legacy `{"t","e"}` wire) decode back-compatibly as
 * ADMIN, and the live gate treats them exactly like freshly-minted admin
 * sessions. The Moshi decode is the same production path AuthSessionStore
 * uses; the fake persistence stands in for the Android file layer, which the
 * JVM cannot host.
 */
class AuthSessionStoreRoleTest {

    private val listAdapter by lazy {
        AppJson.moshi.adapter<List<AuthStoredSession>>(
            Types.newParameterizedType(List::class.java, AuthStoredSession::class.java),
        )
    }

    private var nowMs = 1_000_000L

    /** A gate over an in-memory persistence seeded with [json] (the raw store file's contents). */
    private fun gateOverStore(json: String): Pair<WebAuthGate, CapturingPersistence> {
        val records = listAdapter.fromJson(json).orEmpty()
        val persistence = CapturingPersistence().apply {
            loaded = records.associate {
                it.t to WebAuthGate.StoredSession(
                    expiresAtMs = it.e,
                    role = SessionRole.fromWireName(it.r) ?: SessionRole.ADMIN,
                )
            }
        }
        return WebAuthGate(clock = { nowMs }, sessionPersistence = persistence) to persistence
    }

    private class CapturingPersistence : WebAuthGate.SessionPersistence {
        var loaded: Map<String, WebAuthGate.StoredSession> = emptyMap()
        var saved: Map<String, WebAuthGate.StoredSession>? = null

        override fun loadSessions(): Map<String, WebAuthGate.StoredSession> = loaded
        override fun saveSessions(sessions: Map<String, WebAuthGate.StoredSession>) {
            saved = sessions
        }
    }

    @Test
    fun `a legacy record without the role field decodes as admin`() {
        val legacy = """[{"t":"abc123","e":2000000}]"""
        val parsed = listAdapter.fromJson(legacy)!!.single()
        assertEquals("abc123", parsed.t)
        assertEquals(2_000_000L, parsed.e)
        assertEquals("admin", parsed.r)
    }

    @Test
    fun `a legacy restored session authenticates as admin in the live gate`() {
        val (gate, _) = gateOverStore("""[{"t":"legacy-token","e":2000000}]""")
        gate.setCredentials("admin", com.raulshma.lenscast.core.StreamAuthCrypto.hashPassword("pw"))
        val cookie = "${WebAuthGate.COOKIE_NAME}=legacy-token"
        assertTrue(gate.authenticate(cookie))
        assertEquals(SessionRole.ADMIN, gate.sessionRoleFor(cookie))
    }

    @Test
    fun `a viewer record survives a round trip through the store`() {
        val json = listAdapter.toJson(
            listOf(
                AuthStoredSession("viewer-token", 2_000_000L, SessionRole.VIEWER.wireName),
                AuthStoredSession("admin-token", 2_000_000L, SessionRole.ADMIN.wireName),
            ),
        )
        val (gate, _) = gateOverStore(json)
        gate.setCredentials("admin", com.raulshma.lenscast.core.StreamAuthCrypto.hashPassword("pw"))
        assertEquals(
            SessionRole.VIEWER,
            gate.sessionRoleFor("${WebAuthGate.COOKIE_NAME}=viewer-token"),
        )
        assertEquals(
            SessionRole.ADMIN,
            gate.sessionRoleFor("${WebAuthGate.COOKIE_NAME}=admin-token"),
        )
    }

    @Test
    fun `an expired legacy record is dropped on restore`() {
        val (gate, _) = gateOverStore("""[{"t":"dead-token","e":500}]""")
        // authenticate is auth-off until credentials exist; arm the gate so
        // the session ladder (not the auth-off short-circuit) answers.
        gate.setCredentials("admin", com.raulshma.lenscast.core.StreamAuthCrypto.hashPassword("pw"))
        assertFalse(gate.authenticate("${WebAuthGate.COOKIE_NAME}=dead-token"))
    }

    @Test
    fun `saving after restore persists roles alongside legacy entries`() {
        val (gate, persistence) = gateOverStore("""[{"t":"legacy-token","e":2000000}]""")
        // A fresh viewer login mutates the map and mirrors it back.
        nowMs = 1_500_000L
        gate.setCredentials("admin", com.raulshma.lenscast.core.StreamAuthCrypto.hashPassword("pw"))
        gate.setViewerCredentials("door", com.raulshma.lenscast.core.StreamAuthCrypto.hashPassword("pw2"))
        val viewerToken = gate.login("1.2.3.4", "door", "pw2").token!!
        val saved = persistence.saved!!
        assertEquals(
            SessionRole.ADMIN,
            saved.getValue("legacy-token").role,
        )
        assertEquals(
            SessionRole.VIEWER,
            saved.getValue(viewerToken).role,
        )
    }
}
