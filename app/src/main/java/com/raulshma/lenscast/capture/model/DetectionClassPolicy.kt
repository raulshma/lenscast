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

    /** The three class groups behind the per-group toggles — the one source [ALLOWED_CLASSES] is built from. */
    private val PERSON_CLASSES = listOf("person")
    private val PET_CLASSES = listOf("cat", "dog", "bird", "horse", "sheep", "cow")
    private val VEHICLE_CLASSES = listOf("bicycle", "car", "motorcycle", "bus", "truck")

    /**
     * COCO class names (lowercase, model-metadata spelling) the gate accepts,
     * assembled from the person/pet/vehicle groups. Person; cat, dog, bird,
     * horse, sheep, cow; bicycle, car, motorcycle, bus, truck.
     */
    val ALLOWED_CLASSES: List<String> = PERSON_CLASSES + PET_CLASSES + VEHICLE_CLASSES

    /**
     * The allow-list restricted to the enabled class groups (the persisted
     * per-group toggles). Order stays [ALLOWED_CLASSES] order — person first
     * — whatever the combination, so the wire `labels` array stays
     * deterministic; at least one group must stay enabled (the settings
     * screen never offers an all-off state, and an empty allow-list would
     * suppress every gated event). A label added to a group is covered by
     * that group's toggle — no label lives outside the groups.
     */
    fun allowList(
        includePerson: Boolean,
        includePets: Boolean,
        includeVehicles: Boolean,
    ): List<String> = ALLOWED_CLASSES.filter { label ->
        when {
            label in PERSON_CLASSES -> includePerson
            label in PET_CLASSES -> includePets
            else -> includeVehicles
        }
    }

    /** One detector output: the COCO class label and its 0..1 confidence. */
    data class Detection(val label: String, val score: Float)

    /**
     * The gate verdict: lowercase labels of [allowedClasses] scoring at or
     * above [minScorePercent], deduped (one detection per class is enough to
     * attribute the event), in [ALLOWED_CLASSES] order — person first. An
     * empty result means "nothing of interest" and the caller (the
     * detection coordinator) suppresses the motion event. The full
     * [ALLOWED_CLASSES] list applies when [allowedClasses] is omitted.
     */
    fun filter(
        detections: List<Detection>,
        minScorePercent: Int,
        allowedClasses: List<String> = ALLOWED_CLASSES,
    ): List<String> {
        val minScore = minScorePercent / 100f
        return allowedClasses.mapNotNull { allowed ->
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
