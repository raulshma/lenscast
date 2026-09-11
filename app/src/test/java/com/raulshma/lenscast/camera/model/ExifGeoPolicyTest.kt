package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifGeoPolicyTest {

    // ── the tag values ──

    @Test
    fun `the artist tag is the app name itself`() {
        assertEquals("LensCast", ExifGeoPolicy.artistTag("LensCast"))
    }

    @Test
    fun `the comment tag credits the app`() {
        assertEquals("Captured with LensCast", ExifGeoPolicy.commentTag("LensCast"))
    }

    // ── the taggable verdict ──

    @Test
    fun `a valid fix is taggable`() {
        assertTrue(ExifGeoPolicy.isTaggable(37.7749, -122.4194))
        assertTrue(ExifGeoPolicy.isTaggable(0.0, 0.0))
        assertTrue(ExifGeoPolicy.isTaggable(ExifGeoPolicy.LATITUDE_MAX, ExifGeoPolicy.LONGITUDE_MAX))
    }

    @Test
    fun `a missing or broken fix is never taggable`() {
        assertFalse(ExifGeoPolicy.isTaggable(null, 0.0))
        assertFalse(ExifGeoPolicy.isTaggable(0.0, null))
        assertFalse(ExifGeoPolicy.isTaggable(Double.NaN, 0.0))
        assertFalse(ExifGeoPolicy.isTaggable(0.0, Double.NaN))
    }

    @Test
    fun `an out-of-range fix is never taggable`() {
        assertFalse(ExifGeoPolicy.isTaggable(ExifGeoPolicy.LATITUDE_MAX + 0.1, 0.0))
        assertFalse(ExifGeoPolicy.isTaggable(ExifGeoPolicy.LATITUDE_MIN - 0.1, 0.0))
        assertFalse(ExifGeoPolicy.isTaggable(0.0, ExifGeoPolicy.LONGITUDE_MAX + 0.1))
        assertFalse(ExifGeoPolicy.isTaggable(0.0, ExifGeoPolicy.LONGITUDE_MIN - 0.1))
    }

    // ── the hemisphere refs ──

    @Test
    fun `negative latitudes are south and longitudes west`() {
        assertEquals("N", ExifGeoPolicy.latitudeRef(37.7749))
        assertEquals("S", ExifGeoPolicy.latitudeRef(-33.8688))
        assertEquals("E", ExifGeoPolicy.longitudeRef(151.2093))
        assertEquals("W", ExifGeoPolicy.longitudeRef(-1.0))
    }

    // ── the DMS rationals ──

    @Test
    fun `a clean coordinate splits into whole degree-minute-second rationals`() {
        assertEquals("37/1,30/1,0/1000", ExifGeoPolicy.dmsRationals(37.5))
        assertEquals("90/1,0/1,0/1000", ExifGeoPolicy.dmsRationals(90.0))
        assertEquals("0/1,0/1,0/1000", ExifGeoPolicy.dmsRationals(0.0))
    }

    @Test
    fun `fractional seconds scale to exact milliseconds`() {
        assertEquals("37/1,46/1,29640/1000", ExifGeoPolicy.dmsRationals(37.7749))
        assertEquals("122/1,15/1,0/1000", ExifGeoPolicy.dmsRationals(-122.25))
    }

    @Test
    fun `the sign never reaches the DMS form`() {
        assertEquals("33/1,30/1,0/1000", ExifGeoPolicy.dmsRationals(-33.5))
    }

    // ── the tag set ──

    @Test
    fun `an untaggable fix produces no tags`() {
        assertNull(ExifGeoPolicy.geotagTags(null, null))
        assertNull(ExifGeoPolicy.geotagTags(Double.NaN, 0.0))
        assertNull(ExifGeoPolicy.geotagTags(91.0, 0.0))
    }

    @Test
    fun `a valid fix carries the absolute DMS with the sign in the refs`() {
        val tags = ExifGeoPolicy.geotagTags(-33.5, 151.5)!!

        assertEquals("33/1,30/1,0/1000", tags.latitudeDms)
        assertEquals("S", tags.latitudeRef)
        assertEquals("151/1,30/1,0/1000", tags.longitudeDms)
        assertEquals("E", tags.longitudeRef)
    }
}
