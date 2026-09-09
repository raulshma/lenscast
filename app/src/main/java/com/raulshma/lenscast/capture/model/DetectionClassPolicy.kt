package com.raulshma.lenscast.capture.model

/**
 * The pure COCO allow-list behind the ML object-detection gate: the motion
 * path fires on any pixel change, but only these classes count as an alert
 * worth sending — people, common pets/livestock, and road vehicles.
 * JVM-tested; no Android types.
 *
 * [filter] preserves this list's order (person first) so the wire payload's
 * `labels` array is deterministic regardless of the detector's own result
 * ordering.
 */
object DetectionClassPolicy {

    /**
     * COCO class names (lowercase, model-metadata spelling) the gate accepts.
     * Person; cat, dog, bird, horse, sheep, cow; bicycle, car, motorcycle,
     * bus, truck.
     */
    val ALLOWED_CLASSES: List<String> = listOf(
        "person",
        "cat", "dog", "bird", "horse", "sheep", "cow",
        "bicycle", "car", "motorcycle", "bus", "truck",
    )

    /** One detector output: the COCO class label and its 0..1 confidence. */
    data class Detection(val label: String, val score: Float)

    /**
     * The gate verdict: lowercase labels of allowed classes scoring at or
     * above [minScorePercent], deduped (one detection per class is enough to
     * attribute the event), in [ALLOWED_CLASSES] order — person first. An
     * empty result means "nothing of interest" and the caller (the
     * detection coordinator) suppresses the motion event.
     */
    fun filter(detections: List<Detection>, minScorePercent: Int): List<String> {
        val minScore = minScorePercent / 100f
        return ALLOWED_CLASSES.mapNotNull { allowed ->
            if (detections.any {
                    it.label.lowercase() == allowed && it.score >= minScore
                }
            ) {
                allowed
            } else {
                null
            }
        }
    }

    /** UI spelling of a COCO label: `person` → `Person`, `fire hydrant` → `Fire Hydrant`. */
    fun humanReadable(label: String): String =
        label.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
}
