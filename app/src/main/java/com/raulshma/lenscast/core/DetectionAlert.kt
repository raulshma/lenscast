package com.raulshma.lenscast.core

/**
 * The detection-event vocabulary every producer and sink shares: detectors
 * raise one of these, and every consumer (webhook, MQTT, local notification,
 * event log) switches on it instead of re-matching wire strings. [wireName]
 * is the JSON/topic spelling, the one form that crosses a process boundary.
 */
enum class EventKind(val wireName: String) {
    MOTION("motion"),
    SOUND("sound"),
    TAMPER("tamper");
}

/**
 * One detection event as every alert sink receives it — webhook, MQTT, and
 * the local notification all consume this shape instead of carrying private
 * twins. [value] is the detector's own metric for the event: the motion
 * luma delta, the sound RMS percent, or the battery percent for a tamper
 * event (the metric the tamper monitor has at hand). [timestampMs] is the
 * event moment, stamped once by the coordinator — every sink (and the
 * persisted log) reads it, so webhook and MQTT bodies carry the identical
 * timestamp instead of each re-reading a clock at queue time.
 */
data class DetectionAlert(
    val kind: EventKind,
    val value: Double,
    val zones: List<String> = emptyList(),
    val batteryPercent: Int? = null,
    val snapshotJpegBase64: String? = null,
    val timestampMs: Long,
)

/**
 * The outbound JSON body both remote sinks (webhook and MQTT event topic)
 * publish: one wire shape, serialized through App Json, so an NVR or
 * automation consuming either channel sees identical field names.
 */
data class DetectionEventWire(
    val type: String,
    val value: Double,
    val timestampMs: Long,
    val source: String,
    val zones: List<String>,
    val batteryPercent: Int?,
    val snapshotJpeg: String?,
) {
    companion object {
        /** The `source` stamp every event carries. */
        const val SOURCE = "lenscast"

        private val adapter by lazy { AppJson.moshi.adapter(DetectionEventWire::class.java) }

        /** The alert→wire field mapping both sinks share. */
        fun of(alert: DetectionAlert): DetectionEventWire =
            DetectionEventWire(
                type = alert.kind.wireName,
                value = alert.value,
                timestampMs = alert.timestampMs,
                source = SOURCE,
                zones = alert.zones,
                batteryPercent = alert.batteryPercent,
                snapshotJpeg = alert.snapshotJpegBase64,
            )

        /**
         * The encoded body for [of]. Serialized through the one Moshi
         * instance: locale-independent number formatting and real string
         * escaping — hand-concatenation emitted invalid JSON under
         * comma-decimal locales.
         */
        fun encode(alert: DetectionAlert): ByteArray =
            adapter.toJson(of(alert)).toByteArray(Charsets.UTF_8)
    }
}
