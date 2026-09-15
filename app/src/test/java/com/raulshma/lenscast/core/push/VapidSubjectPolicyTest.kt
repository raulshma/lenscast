package com.raulshma.lenscast.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VapidSubjectPolicyTest {

    // ── problem: accepted shapes ──

    @Test
    fun `mailto subject is accepted`() {
        assertNull(VapidSubjectPolicy.problem("mailto:owner@example.com"))
    }

    @Test
    fun `https subject is accepted`() {
        assertNull(VapidSubjectPolicy.problem("https://example.com/push-contact"))
    }

    @Test
    fun `scheme check ignores case`() {
        assertNull(VapidSubjectPolicy.problem("MAILTO:owner@example.com"))
        assertNull(VapidSubjectPolicy.problem("HTTPS://example.com"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertNull(VapidSubjectPolicy.problem("  mailto:owner@example.com "))
    }

    // ── problem: rejected shapes ──

    @Test
    fun `blank subject is rejected as blank`() {
        assertEquals("blank", VapidSubjectPolicy.problem(""))
        assertEquals("blank", VapidSubjectPolicy.problem("   "))
    }

    @Test
    fun `non mailto or https scheme is rejected`() {
        assertEquals("scheme", VapidSubjectPolicy.problem("http://example.com"))
        assertEquals("scheme", VapidSubjectPolicy.problem("hello@example.com"))
    }

    @Test
    fun `bare scheme prefix without colon lookalike is rejected`() {
        assertEquals("scheme", VapidSubjectPolicy.problem("mailtoowner@example.com"))
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
