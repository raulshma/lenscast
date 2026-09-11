package com.raulshma.lenscast.streaming.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The settings-import body classifier under hostile JSON: a deterministic
 * corpus fuzzer over [SettingsImportParser.parse].
 *
 * ── The contract ──
 * parse() answers a [SettingsImportParser.Parsed] or throws ONLY
 * [IllegalArgumentException] (the router's declared error type) — never a
 * StackOverflowError (Moshi's reader is iterative over containers and the
 * DTO schema bounds adapter recursion), never a Moshi internals leak. The
 * transport caps the body at 1 MB upstream ([StreamingServer.MAX_BODY_BYTES]).
 *
 * ── The corpus ──
 * valid envelopes and bare updates (positive controls), truncations at every
 * prefix, containers nested 100k deep, 1 MB string values, wrong-typed every
 * field, huge/NaN-ish numbers, duplicate keys, lone surrogates, BOMs, and
 * seeded garbage JSON.
 */
class SettingsImportParserFuzzTest {

    // ── the contract ──

    private fun parseHostile(body: String): SettingsImportParser.Parsed? {
        try {
            return SettingsImportParser.parse(body)
        } catch (t: Throwable) {
            if (t !is IllegalArgumentException) {
                throw AssertionError("parse leaked ${t.javaClass.simpleName}: $t", t)
            }
            return null
        }
    }

    // ── positive controls ──

    @Test(timeout = 10_000)
    fun `a valid envelope and a valid bare update both parse`() {
        // SettingsResponseDto requires both sections; the DTOs are all-default
        // so empty objects construct.
        val envelope =
            """{"schemaVersion":1,"exportedAtMs":1788825600000,"app":"lenscast","settings":{"camera":{},"streaming":{}}}"""
        assertTrue(parseHostile(envelope) is SettingsImportParser.Parsed.Envelope)
        val bare = """{"camera":{"webPort":8080},"streaming":null}"""
        assertTrue(parseHostile(bare) is SettingsImportParser.Parsed.RawUpdate)
    }

    // ── the deterministic corpus ──

    @Test(timeout = 10_000)
    fun `corpus - truncations at every prefix of a valid envelope are IAE or a parse`() {
        val envelope = """{"schemaVersion":1,"exportedAtMs":1788825600000,"app":"lenscast","settings":{"camera":{"webPort":8080}}}"""
        for (cut in envelope.indices) {
            parseHostile(envelope.substring(0, cut))
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - 100k-deep nesting is rejected, never a StackOverflowError`() {
        // Containers nested past the reader's patience fail the type gate
        // (the DTO fields are typed, not Maps) or are skipped iteratively by
        // Moshi — either way no adapter recursion can follow the depth.
        val deepArrays = "[".repeat(100_000) + "]".repeat(100_000)
        parseHostile(deepArrays)
        parseHostile("""{"schemaVersion":$deepArrays}""")
        val deepObjects = buildString {
            repeat(100_000) { append("""{"a":""") }
            append("1")
            repeat(100_000) { append("}") }
        }
        parseHostile(deepObjects)
        parseHostile("""{"settings":$deepObjects}""")
        // Mixed shapes under a known field name.
        parseHostile("""{"settings":{"camera":$deepArrays}}""")
    }

    @Test(timeout = 10_000)
    fun `corpus - huge strings and numbers stay inside the declared outcome set`() {
        val bigString = "a".repeat(1_000_000)
        parseHostile("""{"app":"$bigString"}""")
        parseHostile("""{"schemaVersion":"$bigString"}""")
        for (number in listOf("1e400", "-1e400", "1e-400", "NaN", "Infinity", "-Infinity", "0.0000000001", "9".repeat(400))) {
            parseHostile("""{"schemaVersion":$number,"exportedAtMs":$number}""")
            parseHostile("""{"camera":{"webPort":$number}}""")
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - wrong types, duplicate keys and unicode garbage are IAE or a parse`() {
        val hostile = listOf(
            """{"schemaVersion":"one"}""",
            """{"schemaVersion":null,"settings":null,"app":null}""",
            """{"settings":42}""",
            """{"camera":"not-an-object"}""",
            """{"camera":{"maskingZones":[{"x":"NaN","width":true}]}}""",
            """{"camera":{"webPort":99999999999999999999999}}""",
            """{"schemaVersion":1,"schemaVersion":2,"app":"lenscast"}""",
            """{"camera":{"webPort":1},"camera":{"webPort":2}}""",
            """{"app":"${"\uD800"}"}""", // lone surrogate escape
            "\uFEFF{}", // BOM prefix
            """{"app":"\u0000\uFFFF"}""",
            "null", "true", "42", "\"a string\"", "[]", "{}",
        )
        for (body in hostile) {
            parseHostile(body)
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - seeded garbage JSON never leaks a non-IAE`() {
        val random = Random(0x5E77196)
        val alphabet = """{}[]",:0123456789.eE+-truefalsn\/ ]]"""
        for (trial in 0 until 500) {
            val body = buildString {
                repeat(random.nextInt(128)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            parseHostile(body)
        }
    }
}
