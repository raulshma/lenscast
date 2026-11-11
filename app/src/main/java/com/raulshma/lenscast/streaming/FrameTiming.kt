package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.core.StreamDefaults

/**
 * Pure fps ↔ interval math for the frame path. The
 * "non-positive fps falls back to [StreamDefaults.STREAM_FPS]" rule lived
 * re-derived at every consumer; it lives here once.
 */
object FrameTiming {

    /** The effective fps: non-positive settings fall back to the stream default. */
    fun effectiveFps(fps: Int): Int = if (fps > 0) fps else StreamDefaults.STREAM_FPS

    /** Wall-clock frame interval in milliseconds. */
    fun frameIntervalMs(fps: Int): Long = 1_000L / effectiveFps(fps)
}
