package com.raulshma.lenscast.capture.model

import com.raulshma.lenscast.capture.TimelapseAssembler

/**
 * The pure completion→assemble handoff verdict for interval capture: when a
 * series finishes, should a timelapse be assembled automatically, and from
 * how many photos? The assembly itself (MediaCodec/MediaMuxer) is
 * [com.raulshma.lenscast.capture.TimelapseAssembler]'s; this policy only
 * decides the handoff so the worker and the manual path share one ladder:
 *
 * - the persisted auto-timelapse toggle must be on (default on — a finished
 *   series that leaves the user with only loose photos is the worse
 *   default), and
 * - the photo history must hold at least [TimelapseAssembler.MIN_SOURCES]
 *   interval photos (the assembler's own floor — fewer frames cannot make a
 *   video worth storing).
 *
 * A photo count at/above the floor answers the exact count to select; the
 * caller clamps through `TimelapseAssembler.selectSources` like every
 * consumer.
 */
object TimelapseCompletionPolicy {

    /** The handoff verdict: assemble (with the source count) or skip. */
    sealed interface Verdict {
        /** Assemble from the newest [photoCount] photos. */
        data class Assemble(val photoCount: Int) : Verdict

        /** No assembly — feature off or not enough photos. */
        data object Skip : Verdict
    }

    fun onSeriesComplete(autoTimelapseEnabled: Boolean, availablePhotos: Int): Verdict = when {
        !autoTimelapseEnabled -> Verdict.Skip
        availablePhotos < TimelapseAssembler.MIN_SOURCES -> Verdict.Skip
        else -> Verdict.Assemble(availablePhotos)
    }
}
