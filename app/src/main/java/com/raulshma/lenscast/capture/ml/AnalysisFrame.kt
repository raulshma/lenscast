package com.raulshma.lenscast.capture.ml

/**
 * One camera frame retained for object-detection analysis: the NV21 bytes as
 * delivered by the camera frame listener plus their dimensions. Producers
 * keep only the live buffer reference; the ML gate copies defensively before
 * handing the bytes to its inference executor.
 */
class AnalysisFrame(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
)
