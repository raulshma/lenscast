package com.raulshma.lenscast.companion.ui.pairing

import com.raulshma.lenscast.companion.discovery.NsdDiscoveryFilter
import com.raulshma.lenscast.companion.discovery.PairingPayload
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.DiscoveredService
import com.raulshma.lenscast.companion.model.StoredCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure pairing assembler's table: payload / discovery / form facts +
 * auth kind → the exact [CompanionDevice] + [StoredCredentials] shapes the
 * roster and credential stores persist. These pin the identity rules (uuid
 * and clock injected, name ladder, role only when revealed, fingerprint
 * normalization parity, one secret per kind) — the same shapes the pairing
 * flow test asserts end-to-end.
 */
class PairingAssemblerTest {

    private val fixedId = "pair-42"
    private val fixedNow = 1_700_000_000_000L

    // ── inputFromPayload ──

    @Test
    fun `https QR payload folds to TLS input with the canonical digest`() {
        val payload: PairingPayload = checkNotNull(
            NsdDiscoveryFilter.parsePairingPayload("https://192.168.1.5:8443/#fp=AB:CD:EF:01"),
        )
        val input = PairingAssembler.inputFromPayload(payload)

        assertTrue(input.useTls)
        assertEquals("192.168.1.5", input.host)
        assertEquals(8443, input.port)
        // The QR ships colon-separated uppercase; the parser normalizes —
        // the input must carry the same canonical form the pin compares.
        assertEquals("abcdef01", input.tlsFingerprint)
        assertNull(input.discoveredName)
        // Token is the default auth kind for a fresh payload.
        assertEquals(CompanionAuthKind.TOKEN, input.authKind)
    }

    @Test
    fun `plain-http QR payload folds to non-TLS input with no digest`() {
        val payload: PairingPayload = checkNotNull(
            NsdDiscoveryFilter.parsePairingPayload("http://10.0.0.2:8080"),
        )
        val input = PairingAssembler.inputFromPayload(payload)

        assertFalse(input.useTls)
        assertNull(input.tlsFingerprint)
        assertEquals(8080, input.port)
    }

    // ── inputFromDiscovery ──

    @Test
    fun `discovery hit folds to address + friendly name, TLS off`() {
        val input = PairingAssembler.inputFromDiscovery(
            DiscoveredService(name = "LensCast-Pixel8", host = "192.168.1.7", port = 8080),
        )
        assertEquals("192.168.1.7", input.host)
        assertEquals(8080, input.port)
        assertFalse(input.useTls)
        assertNull(input.tlsFingerprint)
        assertEquals("LensCast-Pixel8", input.discoveredName)
    }

    // ── inputFromForm ──

    @Test
    fun `submittable form folds with trims and canonical fingerprint`() {
        val input = PairingAssembler.inputFromForm(
            PairingForm(
                name = "  Garage  ",
                host = " 192.168.1.9 ",
                port = " 8443 ",
                useTls = true,
                tlsFingerprint = " AB:CD ",
                authKind = CompanionAuthKind.BASIC,
                username = " admin ",
                password = "p@ss word",
            ),
        )!!
        assertEquals("Garage", input.name)
        assertEquals("192.168.1.9", input.host)
        assertEquals(8443, input.port)
        assertEquals("abcd", input.tlsFingerprint)
        assertEquals("admin", input.username)
        // The password is the one field never trimmed.
        assertEquals("p@ss word", input.password)
    }

    @Test
    fun `blank fingerprint and blank secrets fold to nulls`() {
        val input = PairingAssembler.inputFromForm(
            PairingForm(host = "h", port = "1", tlsFingerprint = "   "),
        )!!
        assertNull(input.tlsFingerprint)
        assertNull(input.token)
        assertNull(input.username)
        assertNull(input.password)
    }

    @Test
    fun `invalid forms fold to null`() {
        assertNull(PairingAssembler.inputFromForm(PairingForm(host = "", port = "8080")))
        assertNull(PairingAssembler.inputFromForm(PairingForm(host = "h", port = "")))
        assertNull(PairingAssembler.inputFromForm(PairingForm(host = "h", port = "abc")))
        assertNull(PairingAssembler.inputFromForm(PairingForm(host = "h", port = "0")))
        assertNull(PairingAssembler.inputFromForm(PairingForm(host = "h", port = "70000")))
    }

    @Test
    fun `isSubmittable mirrors inputFromForm's gate`() {
        assertTrue(PairingForm(host = "h", port = "8080").isSubmittable)
        assertFalse(PairingForm(host = "", port = "8080").isSubmittable)
        assertFalse(PairingForm(host = "h", port = "0").isSubmittable)
        assertFalse(PairingForm(host = "h", port = "99999").isSubmittable)
    }

    // ── defaultName ladder ──

    @Test
    fun `name ladder user then discovered then host port`() {
        assertEquals(
            "Mine",
            PairingAssembler.defaultName(PairingInput(host = "h", port = 1, useTls = false, tlsFingerprint = null, authKind = CompanionAuthKind.TOKEN, token = null, username = null, password = null, name = "Mine", discoveredName = "Radar")),
        )
        assertEquals(
            "Radar",
            PairingAssembler.defaultName(PairingInput(host = "h", port = 1, useTls = false, tlsFingerprint = null, authKind = CompanionAuthKind.TOKEN, token = null, username = null, password = null, name = "", discoveredName = "Radar")),
        )
        assertEquals(
            "h:1",
            PairingAssembler.defaultName(PairingInput(host = "h", port = 1, useTls = false, tlsFingerprint = null, authKind = CompanionAuthKind.TOKEN, token = null, username = null, password = null, name = "", discoveredName = null)),
        )
    }

    // ── assemble ──

    @Test
    fun `token assembly shapes device and credentials`() {
        val assembled = PairingAssembler.assemble(
            input = PairingInput(
                host = "192.168.1.5", port = 8443, useTls = true,
                tlsFingerprint = "aabb", authKind = CompanionAuthKind.TOKEN, token = "tok",
                username = null, password = null, name = "Garage",
            ),
            newId = fixedId,
            nowMs = fixedNow,
        )
        assertEquals(
            CompanionDevice(
                id = fixedId,
                name = "Garage",
                host = "192.168.1.5",
                port = 8443,
                useTls = true,
                tlsFingerprint = "aabb",
                auth = CompanionAuthKind.TOKEN,
                role = null,
                addedAtMs = fixedNow,
                lastSeenMs = null,
            ),
            assembled.device,
        )
        assertEquals(
            StoredCredentials(kind = CompanionAuthKind.TOKEN, token = "tok"),
            assembled.credentials,
        )
    }

    @Test
    fun `basic assembly carries username and password and revealed role`() {
        val assembled = PairingAssembler.assemble(
            input = PairingInput(
                host = "h", port = 8080, useTls = false, tlsFingerprint = null,
                authKind = CompanionAuthKind.BASIC, token = null,
                username = "admin", password = "pw",
            ),
            newId = fixedId,
            nowMs = fixedNow,
            revealedRole = "viewer",
        )
        assertEquals("viewer", assembled.device.role)
        assertEquals(
            StoredCredentials(kind = CompanionAuthKind.BASIC, username = "admin", password = "pw"),
            assembled.credentials,
        )
        // Auth-off devices pair without a role reveal: unknown stays unknown.
        val unrevealed = PairingAssembler.assemble(
            input = PairingInput(
                host = "h", port = 8080, useTls = false, tlsFingerprint = null,
                authKind = CompanionAuthKind.BASIC, token = null,
                username = "admin", password = "pw",
            ),
            newId = fixedId,
            nowMs = fixedNow,
        )
        assertNull(unrevealed.device.role)
    }

    @Test
    fun `assembly normalizes a display-form fingerprint to the pin form`() {
        val assembled = PairingAssembler.assemble(
            input = PairingInput(
                host = "h", port = 8443, useTls = true,
                tlsFingerprint = "AA:BB:CC:DD", authKind = CompanionAuthKind.TOKEN, token = "t",
                username = null, password = null,
            ),
            newId = fixedId,
            nowMs = fixedNow,
        )
        assertEquals("aabbccdd", assembled.device.tlsFingerprint)
    }

    @Test
    fun `blank user name falls back to the discovered friendly name`() {
        val assembled = PairingAssembler.assemble(
            input = PairingInput(
                host = "h", port = 8080, useTls = false, tlsFingerprint = null,
                authKind = CompanionAuthKind.TOKEN, token = null,
                username = null, password = null,
                name = "",
                discoveredName = "LensCast-Pixel8",
            ),
            newId = fixedId,
            nowMs = fixedNow,
        )
        assertEquals("LensCast-Pixel8", assembled.device.name)
    }
}
