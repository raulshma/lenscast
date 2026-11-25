package com.raulshma.lenscast.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the capability lists' one rule — null means unknown means
 * unrestricted — over the logical-camera / physical-lens intersection the
 * service folds at bind: a side that did not advertise a list never narrows
 * the other side, and two known lists intersect.
 */
class CaptureSupportTest {

    private val a = setOf(1, 2, 3)
    private val b = setOf(2, 3, 4)

    @Test
    fun `the unrestricted default knows no list`() {
        val support = CaptureSupport.UNRESTRICTED

        assertNull(support.sceneModes)
        assertNull(support.aeModes)
        assertNull(support.awbModes)
        assertNull(support.afModes)
        assertNull(support.opticalStabilizationModes)
        assertNull(support.videoStabilizationModes)
        assertNull(support.manualPostProcessing)
        assertNull(support.targetFpsRanges)
    }

    @Test
    fun `intersecting with the unrestricted default is the other side unchanged`() {
        val known = CaptureSupport(
            sceneModes = a,
            aeModes = b,
            awbModes = a,
            afModes = b,
            opticalStabilizationModes = a,
            videoStabilizationModes = b,
            manualPostProcessing = false,
            targetFpsRanges = setOf(CaptureSupport.FpsRange(30, 30), CaptureSupport.FpsRange(30, 60)),
        )

        assertEquals(known, CaptureSupport.UNRESTRICTED.intersect(known))
        assertEquals(known, known.intersect(CaptureSupport.UNRESTRICTED))
    }

    @Test
    fun `a null list on either side leaves that list unrestricted`() {
        // The logical camera did not advertise scene modes, the physical lens
        // did not advertise AF modes: each stays exactly what the other side
        // said, in both argument orders.
        assertEquals(
            CaptureSupport(sceneModes = a, afModes = b),
            CaptureSupport(sceneModes = null, afModes = b)
                .intersect(CaptureSupport(sceneModes = a, afModes = null)),
        )
        assertEquals(
            CaptureSupport(sceneModes = a, afModes = b),
            CaptureSupport(sceneModes = a, afModes = null)
                .intersect(CaptureSupport(sceneModes = null, afModes = b)),
        )
    }

    @Test
    fun `two unknown lists stay unknown`() {
        assertNull(CaptureSupport(sceneModes = null).intersect(CaptureSupport(sceneModes = null)).sceneModes)
    }

    @Test
    fun `two known lists intersect on every field`() {
        val support = CaptureSupport(
            sceneModes = a,
            aeModes = a,
            awbModes = a,
            afModes = a,
            opticalStabilizationModes = a,
            videoStabilizationModes = a,
            manualPostProcessing = true,
        ).intersect(
            CaptureSupport(
                sceneModes = b,
                aeModes = b,
                awbModes = b,
                afModes = b,
                opticalStabilizationModes = b,
                videoStabilizationModes = b,
                manualPostProcessing = true,
            ),
        )

        val both = setOf(2, 3)
        assertEquals(
            CaptureSupport(
                sceneModes = both,
                aeModes = both,
                awbModes = both,
                afModes = both,
                opticalStabilizationModes = both,
                videoStabilizationModes = both,
                manualPostProcessing = true,
            ),
            support,
        )
    }

    @Test
    fun `manual post-processing needs both sides`() {
        // The capability is a single flag: a lens without it cannot apply the
        // Kelvin gains no matter what the logical camera says.
        assertEquals(true, CaptureSupport(manualPostProcessing = true).intersect(CaptureSupport(manualPostProcessing = true)).manualPostProcessing)
        assertEquals(false, CaptureSupport(manualPostProcessing = true).intersect(CaptureSupport(manualPostProcessing = false)).manualPostProcessing)
        assertEquals(false, CaptureSupport(manualPostProcessing = false).intersect(CaptureSupport(manualPostProcessing = true)).manualPostProcessing)
    }

    @Test
    fun `a lens without optical stabilization narrows a logical camera that has it`() {
        // The case the intersection exists for: the logical multi-camera
        // advertises OIS (the zoom lens has it), the selected wide lens does
        // not — the session drives the wide lens, so OIS-on is unadvertised.
        val support = CaptureSupport(opticalStabilizationModes = setOf(0, 1))
            .intersect(CaptureSupport(opticalStabilizationModes = setOf(0)))

        assertEquals(setOf(0), support.opticalStabilizationModes)
    }

    @Test
    fun `target fps ranges intersect when both sides are known and keep known when one is null`() {
        val r30 = CaptureSupport.FpsRange(30, 30)
        val r60 = CaptureSupport.FpsRange(30, 60)
        val r15 = CaptureSupport.FpsRange(15, 30)

        assertEquals(
            setOf(r30),
            CaptureSupport(targetFpsRanges = setOf(r30, r60))
                .intersect(CaptureSupport(targetFpsRanges = setOf(r15, r30)))
                .targetFpsRanges,
        )
        assertEquals(
            setOf(r30, r60),
            CaptureSupport(targetFpsRanges = setOf(r30, r60))
                .intersect(CaptureSupport(targetFpsRanges = null))
                .targetFpsRanges,
        )
    }

    @Test
    fun `intersect builds a new value and leaves both operands untouched`() {
        val left = CaptureSupport(sceneModes = a)
        val right = CaptureSupport(sceneModes = b)

        val merged = left.intersect(right)

        assertEquals(setOf(2, 3), merged.sceneModes)
        assertEquals(a, left.sceneModes)
        assertEquals(b, right.sceneModes)
        assertNotSame(left, merged)
    }
}
