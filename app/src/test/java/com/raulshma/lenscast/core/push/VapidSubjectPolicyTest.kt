package com.raulshma.lenscast.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VapidSubjectPolicyTest {

    // ── isUsable: accepted shapes ──

    @Test
    fun `mailto subject is usable`() {
        assertTrue(VapidSubjectPolicy.isUsable("mailto:owner@example.com"))
    }

    @Test
    fun `https subject is usable`() {
        assertTrue(VapidSubjectPolicy.isUsable("https://example.com/push-contact"))
    }

    @Test
    fun `scheme check ignores case`() {
        assertTrue(VapidSubjectPolicy.isUsable("MAILTO:owner@example.com"))
        assertTrue(VapidSubjectPolicy.isUsable("HTTPS://example.com"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertTrue(VapidSubjectPolicy.isUsable("  mailto:owner@example.com "))
    }

    // ── isUsable: rejected shapes ──

    @Test
    fun `blank subject is not usable`() {
        assertFalse(VapidSubjectPolicy.isUsable(""))
        assertFalse(VapidSubjectPolicy.isUsable("   "))
    }

    @Test
    fun `non mailto or https scheme is not usable`() {
        assertFalse(VapidSubjectPolicy.isUsable("http://example.com"))
        assertFalse(VapidSubjectPolicy.isUsable("hello@example.com"))
    }

    @Test
    fun `bare scheme prefix without colon lookalike is not usable`() {
        assertFalse(VapidSubjectPolicy.isUsable("mailtoowner@example.com"))
    }

    // ── orDefault: the sender's fail-open path ──

    @Test
    fun `valid subject passes through unchanged`() {
        assertEquals(
            "mailto:me@example.com",
            VapidSubjectPolicy.orDefault("mailto:me@example.com", "mailto:fallback@example.com"),
        )
    }

    @Test
    fun `invalid subject falls back to the default contact`() {
        assertEquals(
            "mailto:fallback@example.com",
            VapidSubjectPolicy.orDefault("not-a-contact", "mailto:fallback@example.com"),
        )
        assertEquals(
            "mailto:fallback@example.com",
            VapidSubjectPolicy.orDefault("", "mailto:fallback@example.com"),
        )
    }
}
