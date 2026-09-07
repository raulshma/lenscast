package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EnumParsingTest {

    private enum class Sample { ALPHA, BETA }

    @Test
    fun `valid name parses`() {
        assertEquals(Sample.ALPHA, parseEnum("ALPHA", Sample.BETA))
        assertEquals(Sample.BETA, parseEnum("BETA", Sample.ALPHA))
    }

    @Test
    fun `null name falls back`() {
        assertEquals(Sample.BETA, parseEnum(null, Sample.BETA))
    }

    @Test
    fun `unknown name falls back`() {
        assertEquals(Sample.ALPHA, parseEnum("GAMMA", Sample.ALPHA))
        assertEquals(Sample.ALPHA, parseEnum("", Sample.ALPHA))
    }

    @Test
    fun `parsing is case sensitive per valueOf`() {
        assertEquals(Sample.BETA, parseEnum("alpha", Sample.BETA))
    }

    @Test
    fun `orNull variant parses valid names`() {
        assertEquals(Sample.ALPHA, parseEnumOrNull<Sample>("ALPHA"))
        assertEquals(Sample.BETA, parseEnumOrNull<Sample>("BETA"))
    }

    @Test
    fun `orNull variant yields null for null blank or unknown`() {
        assertEquals(null, parseEnumOrNull<Sample>(null))
        assertEquals(null, parseEnumOrNull<Sample>(""))
        assertEquals(null, parseEnumOrNull<Sample>("GAMMA"))
    }
}
