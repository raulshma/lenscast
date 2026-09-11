package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

/**
 * The tolerant enum/wire-name parse helpers under hostile strings: a
 * deterministic corpus fuzzer.
 *
 * ── The contract ──
 * [parseEnum] answers the fallback and [parseEnumOrNull]/[parseWireNameOrNull]
 * answer null for any null, unknown, huge, or garbage input — never an
 * IllegalArgumentException from valueOf escaping, never an exception at all.
 */
class EnumParsingFuzzTest {

    private enum class Sample { ALPHA, BETA }

    private val wireMap = mapOf("h264" to Sample.ALPHA, "h265" to Sample.BETA)

    @Test(timeout = 10_000)
    fun `corpus - hostile names always fall back or yield null`() {
        val hostile = listOf(
            null, "", " ", "ALPHA ", " alpha", "alpha", "beta", "\u0000",
            "ALPHA\u0000", "infinity", "sample", "java.lang.Runtime", "{}", "-1", "1",
            "h264", "H264", "h265;version=3", "a".repeat(1_000_000),
        )
        for (name in hostile) {
            assertEquals(Sample.ALPHA, parseEnum(name, Sample.ALPHA))
            assertEquals(Sample.BETA, parseEnum(name, Sample.BETA))
        }
        // The skip-save variant only answers for exact enum names.
        assertNull(parseEnumOrNull<Sample>("alpha"))
        assertNull(parseEnumOrNull<Sample>("ALPHA "))
        assertEquals(Sample.ALPHA, parseEnumOrNull<Sample>("ALPHA"))
        assertEquals(Sample.BETA, parseEnumOrNull<Sample>("BETA"))
    }

    @Test(timeout = 10_000)
    fun `corpus - wire-name lookups over garbage stay null`() {
        for (name in listOf(null, "", " ", "h264", "H264", "h265 ", " h265", "mpeg2", "a".repeat(100_000))) {
            val parsed = parseWireNameOrNull(name, wireMap)
            // Only the exact trimmed wire names hit.
            assertEquals(
                when (name?.trim()) {
                    "h264" -> Sample.ALPHA
                    "h265" -> Sample.BETA
                    else -> null
                },
                parsed,
            )
        }
    }

    @Test(timeout = 10_000)
    fun `corpus - seeded garbage strings never escape the fallback`() {
        val random = Random(0x3A0)
        val alphabet = "ABELTahp_ \$\\\u0000{}"
        for (trial in 0 until 500) {
            val name = buildString {
                repeat(random.nextInt(64)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            assertEquals(Sample.BETA, parseEnum(name, Sample.BETA))
        }
    }
}
