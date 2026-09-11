package com.raulshma.lenscast.capture.model

import com.raulshma.lenscast.capture.ml.YamnetLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Threshold, allow-list, normalization, and the label-stability ladder of the
 * YAMNet sound-classification gate — the pure half of the annotate-only
 * decision. The curated default list is cross-checked against the generated
 * [YamnetLabels] map so a renamed or misspelled class can never silently
 * leave the gate.
 */
class SoundClassPolicyTest {

    // ── windowLabel — the per-window threshold + allow-list gate ──

    @Test
    fun `allowed label at threshold passes`() {
        // Exactly at the threshold counts (at/above, not above).
        assertEquals(
            "Siren",
            SoundClassPolicy.windowLabel("Siren", 60f, minConfidencePercent = 60),
        )
    }

    @Test
    fun `allowed label below threshold does not pass`() {
        assertNull(SoundClassPolicy.windowLabel("Siren", 59.9f, minConfidencePercent = 60))
    }

    @Test
    fun `disallowed yamnet classes never pass`() {
        assertNull(SoundClassPolicy.windowLabel("Music", 99f, minConfidencePercent = 10))
        assertNull(SoundClassPolicy.windowLabel("Silence", 99f, minConfidencePercent = 10))
        assertNull(SoundClassPolicy.windowLabel("Clapping", 99f, minConfidencePercent = 10))
    }

    @Test
    fun `blank and null labels never pass`() {
        assertNull(SoundClassPolicy.windowLabel(null, 99f, minConfidencePercent = 10))
        assertNull(SoundClassPolicy.windowLabel("", 99f, minConfidencePercent = 10))
        assertNull(SoundClassPolicy.windowLabel("  ", 99f, minConfidencePercent = 10))
    }

    @Test
    fun `a narrowed allow-list passes its members and drops the rest`() {
        val allowed = setOf("Bark", "Dog")
        assertEquals("Bark", SoundClassPolicy.windowLabel("Bark", 80f, 10, allowed))
        assertNull(SoundClassPolicy.windowLabel("Siren", 99f, 10, allowed))
    }

    @Test
    fun `the gate compares exact yamnet spellings`() {
        // A near-miss spelling must not pass as its look-alike.
        assertNull(SoundClassPolicy.windowLabel("smoke detector", 99f, 10))
        assertNull(SoundClassPolicy.windowLabel("Smoke Detector", 99f, 10))
        assertEquals(
            "Smoke detector, smoke alarm",
            SoundClassPolicy.windowLabel("Smoke detector, smoke alarm", 99f, 10),
        )
    }

    // ── normalizeAllowed — the persisted set made safe ──

    @Test
    fun `unknown spellings drop out of a persisted allow-list`() {
        val normalized = SoundClassPolicy.normalizeAllowed(
            setOf("Siren", "Laser gun", "not a class"),
        )
        assertEquals(setOf("Siren"), normalized)
    }

    @Test
    fun `an emptied allow-list folds back to the curated default`() {
        // Toggling every chip off must not disarm the gate silently — the
        // same convention as the arm-schedule's day mask.
        assertEquals(
            SoundClassPolicy.DEFAULT_ALLOWED_CLASSES,
            SoundClassPolicy.normalizeAllowed(emptySet()),
        )
        assertEquals(
            SoundClassPolicy.DEFAULT_ALLOWED_CLASSES,
            SoundClassPolicy.normalizeAllowed(setOf("bogus")),
        )
    }

    @Test
    fun `the curated default list is real yamnet classes`() {
        // Every default entry must exist verbatim in the generated 521-class
        // map — the engine's labels come from that map, so a misspelling here
        // would make the default gate dead.
        SoundClassPolicy.SECURITY_CLASSES.forEach { label ->
            assertTrue(
                "curated class not a YAMNet label: $label",
                YamnetLabels.CLASSES.contains(label),
            )
        }
        assertTrue(SoundClassPolicy.SECURITY_CLASSES.size >= 15)
        assertEquals(
            SoundClassPolicy.SECURITY_CLASSES.size,
            SoundClassPolicy.DEFAULT_ALLOWED_CLASSES.size,
        )
    }

    @Test
    fun `humanReadable title-cases words without touching the stored spelling`() {
        assertEquals("Wail, Moan", SoundClassPolicy.humanReadable("Wail, moan"))
        assertEquals("Gunshot, Gunfire", SoundClassPolicy.humanReadable("Gunshot, gunfire"))
        assertEquals("Speech", SoundClassPolicy.humanReadable("Speech"))
    }

    // ── SoundLabelTracker — the stability ladder ──

    @Test
    fun `no history wins nothing`() {
        assertTrue(SoundLabelTracker().winningLabels(nowMs = 1_000).isEmpty())
    }

    @Test
    fun `a single fresh window wins its label`() {
        // Ladder 2: one chirp still annotates its event.
        val tracker = SoundLabelTracker()
        tracker.onWindow("Smoke detector, smoke alarm", nowMs = 1_000)
        assertEquals(
            listOf("Smoke detector, smoke alarm"),
            tracker.winningLabels(nowMs = 2_000),
        )
    }

    @Test
    fun `two agreeing fresh windows are stable and beat a newer one-hit label`() {
        // Ladder 1: stability (count) beats recency — a sounding alarm with
        // one misread window newer still wins.
        val tracker = SoundLabelTracker()
        tracker.onWindow("Siren", nowMs = 1_000)
        tracker.onWindow("Siren", nowMs = 1_960)
        tracker.onWindow("Truck", nowMs = 2_920)
        assertEquals(listOf("Siren"), tracker.winningLabels(nowMs = 3_500))
    }

    @Test
    fun `stable labels order by count then recency and cap at the event maximum`() {
        val tracker = SoundLabelTracker()
        repeat(3) { tracker.onWindow("Bark", nowMs = 1_000L + it * 100) }
        tracker.onWindow("Speech", nowMs = 1_500)
        tracker.onWindow("Speech", nowMs = 1_600)
        tracker.onWindow("Knock", nowMs = 1_700)
        assertEquals(listOf("Bark", "Speech"), tracker.winningLabels(nowMs = 2_000))
    }

    @Test
    fun `windows older than freshness never label an event`() {
        // An ancient label never annotates a new event — the tracker's
        // history ages out even before the grace ladder is consulted.
        val tracker = SoundLabelTracker()
        tracker.onWindow("Siren", nowMs = 0)
        assertTrue(tracker.winningLabels(nowMs = 10_000).isEmpty())
    }

    @Test
    fun `a label-less window does not erase prior fresh windows`() {
        // The silence between a smoke alarm's chirps must not unlabel the
        // next chirp: null windows record nothing but the passage of time.
        val tracker = SoundLabelTracker()
        tracker.onWindow("Smoke detector, smoke alarm", nowMs = 1_000)
        tracker.onWindow(null, nowMs = 1_960)
        tracker.onWindow(null, nowMs = 2_920)
        assertEquals(
            listOf("Smoke detector, smoke alarm"),
            tracker.winningLabels(nowMs = 3_000),
        )
    }

    @Test
    fun `a label that won keeps re-winning through the grace horizon`() {
        // The chirping smoke alarm: chirp windows ~40 s apart, each chirp's
        // RMS event fires before its own window completes, so the label must
        // survive on grace between fresh windows.
        val tracker = SoundLabelTracker(labelCooldownMs = 120_000)
        tracker.onWindow("Smoke detector, smoke alarm", nowMs = 1_000)
        assertEquals(
            listOf("Smoke detector, smoke alarm"),
            tracker.winningLabels(nowMs = 2_000),
        )
        // 40 s later: nothing fresh, grace still holds.
        assertEquals(
            listOf("Smoke detector, smoke alarm"),
            tracker.winningLabels(nowMs = 42_000),
        )
        // And again 40 s after that — still inside the 120 s horizon stamped
        // by the previous win.
        assertEquals(
            listOf("Smoke detector, smoke alarm"),
            tracker.winningLabels(nowMs = 82_000),
        )
    }

    @Test
    fun `grace expires and the old label is gone for good`() {
        val tracker = SoundLabelTracker(labelCooldownMs = 120_000)
        tracker.onWindow("Siren", nowMs = 1_000)
        tracker.winningLabels(nowMs = 2_000)
        assertTrue(tracker.winningLabels(nowMs = 200_000).isEmpty())
    }

    @Test
    fun `a fresh different label outranks stale grace`() {
        // New evidence beats the grace ladder: a door knock window completing
        // right before the event annotates the knock, not the alarm that
        // stopped sounding minutes ago.
        val tracker = SoundLabelTracker()
        tracker.onWindow("Siren", nowMs = 1_000)
        tracker.winningLabels(nowMs = 2_000)
        tracker.onWindow("Knock", nowMs = 100_000)
        assertEquals(listOf("Knock"), tracker.winningLabels(nowMs = 101_000))
    }

    @Test
    fun `reset clears history and grace`() {
        val tracker = SoundLabelTracker()
        tracker.onWindow("Siren", nowMs = 1_000)
        tracker.winningLabels(nowMs = 2_000)
        tracker.reset()
        assertTrue(tracker.winningLabels(nowMs = 3_000).isEmpty())
        // Grace is gone too, not just the history.
        assertTrue(tracker.winningLabels(nowMs = 4_000).isEmpty())
    }

    @Test
    fun `history stays bounded no matter how many windows arrive`() {
        val tracker = SoundLabelTracker(maxHistory = 3)
        repeat(50) { tracker.onWindow("Speech", nowMs = it.toLong()) }
        // The three newest survive; the verdict is still just the one label.
        assertEquals(listOf("Speech"), tracker.winningLabels(nowMs = 51))
    }
}
