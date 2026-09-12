package com.raulshma.lenscast.capture.model

/**
 * The in-dashboard deep-link values a detection event carries on every
 * outbound surface — the web feed's `url` field, the Web Push payload, and
 * the MQTT/webhook event bodies. The dashboard's hash router understands
 * `#/gallery/<mediaId>` (gallery open + the viewer on that media) and
 * `#/events` (the events feed scrolled into view), so a push notification or
 * an automation message can land the operator on the right screen.
 *
 * The clip link exists only once the event's bounded recording has finalized
 * and the clip was linked into the persisted event — the dispatch-moment
 * sinks (push, MQTT, webhook) always see [EVENTS]; the persisted feed (poll
 * GET + SSE) upgrades to [gallery] as soon as the clip links, because the url
 * is derived per serialization from the event's current `clipMediaId`.
 */
object DetectionEventDeepLink {

    /** The events feed deep link — the fallback whenever no clip is linked. */
    const val EVENTS = "#/events"

    /** The gallery-viewer deep link on the event's recorded clip. */
    fun gallery(clipMediaId: Long): String = "#/gallery/$clipMediaId"

    /**
     * The deep link for an event in the clip state [clipMediaId] expresses:
     * the gallery viewer on the clip when one is linked, else the events feed.
     * Pure and JVM-tested — the one builder every surface routes through.
     */
    fun forClip(clipMediaId: Long?): String = clipMediaId?.let(::gallery) ?: EVENTS
}
