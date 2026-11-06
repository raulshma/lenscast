package com.raulshma.lenscast.capture.ml

/**
 * The classifier seam behind the coordinator's sound-classification feed:
 * what [com.raulshma.lenscast.capture.DetectionCoordinator] hands the
 * captured PCM to when `soundClassificationEnabled` is on. The production
 * adapter is the YAMNet-backed [SoundClassificationEngine] (whose window
 * listener routes the raw top-1 back into the coordinator's
 * policy-gated tracker); JVM tests inject fakes to pin the feed gating
 * without a model.
 *
 * Annotate-only by contract: nothing here may throw onto the audio path or
 * delay it, and a dropped window never suppresses or delays an RMS event.
 */
interface SoundClassifier {

    /**
     * Feed one PCM16 chunk off the audio reader thread. Never blocks, never
     * throws; window assembly and inference happen on the adapter's own
     * worker.
     */
    fun feed(pcm16: ByteArray, sampleRateHz: Int, channelCount: Int)
}
