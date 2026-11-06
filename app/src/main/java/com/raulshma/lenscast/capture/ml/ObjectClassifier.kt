package com.raulshma.lenscast.capture.ml

/**
 * The classifier seam behind the ML motion gate: what
 * [com.raulshma.lenscast.capture.DetectionCoordinator]'s submit ladder
 * (throttle, single-flight, fail-open) actually calls. The production
 * adapter is the MediaPipe-backed [ObjectDetectionEngine]; JVM tests inject
 * fakes to pin the ladder without a model or a frame decode.
 *
 * The returned verdict vocabulary is the engine's own
 * [ObjectDetectionEngine.Classification] — [ObjectDetectionEngine.Classification.Success]
 * means inference ran (its detections may still be empty;
 * [com.raulshma.lenscast.capture.model.DetectionClassPolicy] filters them),
 * [ObjectDetectionEngine.Classification.Unavailable] means no verdict exists
 * and the event must pass through ungated.
 */
interface ObjectClassifier {

    /**
     * Classify one frame synchronously. The caller owns rate limiting and
     * the inference worker; this method is intentionally blocking and must
     * not run on the frame path.
     */
    fun classify(frame: AnalysisFrame): ObjectDetectionEngine.Classification
}
