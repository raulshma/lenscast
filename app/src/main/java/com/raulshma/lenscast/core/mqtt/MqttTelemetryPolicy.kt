package com.raulshma.lenscast.core.mqtt

/**
 * The pure decisions behind the MQTT telemetry half of
 * [MqttAlertPublisher]: the client-event throttle and the telemetry-tick
 * gate. No Android types, no clock reads — every verdict takes its inputs as
 * values, so the wire-facing cadence is JVM-tested instead of living in the
 * publisher's threads.
 */
object MqttTelemetryPolicy {

    /**
     * The telemetry publish interval while connected, in ms: sensors ride a
     * slow cadence (battery, thermal, bitrate, and client counts move slowly
     * enough, and every publish costs a round trip to the broker).
     */
    const val TELEMETRY_INTERVAL_MS = 30_000L

    /**
     * The minimum spacing between two client events of the SAME kind: a
     * flapping client (or a snapshot-diff race) cannot flood the broker with
     * connect/disconnect chatter. Different kinds never throttle each other.
     */
    const val CLIENT_EVENT_MIN_INTERVAL_MS = 1_000L

    /**
     * Whether a telemetry tick should publish at all: the alert publish
     * gate ([MqttAlertPublisher.willDispatch] decides enabled + hosted)
     * AND the telemetry toggle on. Pure and tested so the tick's no-op cost
     * is one value comparison.
     */
    fun shouldPublishTelemetry(alertsEnabled: Boolean, hosted: Boolean, telemetryEnabled: Boolean): Boolean =
        alertsEnabled && hosted && telemetryEnabled

    /**
     * The per-kind client-event throttle, as a pure verdict over values: the
     * caller passes the kind's last-sent stamp (null = never sent) and the
     * clock; the answer is the stamp to record when the event may publish,
     * or null when the same kind fired again inside the minimum spacing (a
     * flapping client, or a snapshot-diff race, cannot flood the broker).
     * Keying by kind is the caller's bookkeeping — every kind shares this
     * one spacing rule.
     */
    fun clientEventVerdict(lastSentMs: Long?, nowMs: Long): Long? {
        if (lastSentMs != null && nowMs - lastSentMs < CLIENT_EVENT_MIN_INTERVAL_MS) return null
        return nowMs
    }
}
