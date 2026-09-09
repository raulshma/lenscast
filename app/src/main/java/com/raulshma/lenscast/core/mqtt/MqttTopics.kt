package com.raulshma.lenscast.core.mqtt

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.EventKind
import java.util.Locale

/**
 * The pure Home Assistant topic + discovery-payload layout behind the MQTT
 * alert publisher: every topic string and every discovery config is derived
 * here from (discoveryPrefix, deviceId, deviceName) alone, so the wire
 * contract is JVM-testable and the publisher carries no string assembly.
 *
 * Entity layout under `<prefix>/lenscast/<deviceId>/`:
 * `status` (availability, retained, LWT), `motion|sound|tamper/state`
 * (binary_sensor ON pulses; HA's `off_delay` auto-resets them), and `event`
 * (the full detection JSON, snapshot included). The sensor-kind vocabulary is
 * [SensorKind] — the one home mapping an event type onto its state topic,
 * discovery topic, and discovery payload.
 */
object MqttTopics {

    private const val BASE_PREFIX = "lenscast"

    /** Seconds before HA auto-resets a pulsed binary_sensor to OFF. */
    const val MOTION_OFF_DELAY_SECONDS = 30
    const val SOUND_OFF_DELAY_SECONDS = 5
    const val TAMPER_OFF_DELAY_SECONDS = 60

    /**
     * The one home of the MQTT-side sensor vocabulary: each [EventKind]'s
     * `off_delay` and topic segment (the kind's own wire name). Every
     * kind-keyed lookup (state topic, discovery topic, discovery payload)
     * resolves through this enum, so a new kind is one entry here — plus its
     * [EventKind].
     */
    enum class SensorKind(val eventKind: EventKind, val offDelaySeconds: Int) {
        MOTION(EventKind.MOTION, MOTION_OFF_DELAY_SECONDS),
        SOUND(EventKind.SOUND, SOUND_OFF_DELAY_SECONDS),
        TAMPER(EventKind.TAMPER, TAMPER_OFF_DELAY_SECONDS);

        companion object {
            /** The sensor an event of [kind] publishes to; null when the kind has no sensor. */
            fun fromOrNull(kind: EventKind): SensorKind? =
                entries.firstOrNull { it.eventKind == kind }
        }
    }

    class EntityTopics(
        val availability: String,
        val event: String,
        private val stateTopics: Map<SensorKind, String>,
        private val discoveryTopics: Map<SensorKind, String>,
    ) {
        /** The state topic a [kind] event publishes its ON pulse to. */
        fun stateTopicFor(kind: SensorKind): String = stateTopics.getValue(kind)

        /** The discovery config topic for [kind]'s binary_sensor. */
        fun discoveryTopicFor(kind: SensorKind): String = discoveryTopics.getValue(kind)
    }

    fun entityTopics(discoveryPrefix: String, deviceId: String): EntityTopics {
        // Whitespace normalization is the Settings Store's (on save); only
        // the trailing slash is cleaned here, at the string-assembly seam.
        val prefix = discoveryPrefix.trimEnd('/')
        val base = "$prefix/$BASE_PREFIX/$deviceId"
        return EntityTopics(
            availability = "$base/status",
            event = "$base/event",
            stateTopics = SensorKind.entries.associateWith { kind -> "$base/${kind.eventKind.wireName}/state" },
            discoveryTopics = SensorKind.entries.associateWith { kind ->
                discoveryConfigTopic(prefix, deviceId, kind.eventKind.wireName)
            },
        )
    }

    private fun discoveryConfigTopic(prefix: String, deviceId: String, sensor: String): String =
        "$prefix/binary_sensor/${entityId(deviceId, sensor)}/config"

    /** HA entity id stem: sanitized device id + sensor kind. */
    private fun entityId(deviceId: String, sensor: String): String =
        "lenscast_${deviceId.replace(Regex("[^A-Za-z0-9_-]"), "_")}_$sensor"

    /**
     * The HA discovery config for one binary_sensor entity: `off_delay` makes
     * a single ON pulse self-reset, so the device only ever publishes ON.
     */
    fun discoveryPayload(
        topics: EntityTopics,
        deviceId: String,
        deviceName: String,
        kind: SensorKind,
    ): String {
        val segment = kind.eventKind.wireName
        return adapter.toJson(
            DiscoveryPayload(
                name = "LensCast ${segment.replaceFirstChar { it.uppercase(Locale.US) }}",
                unique_id = entityId(deviceId, segment),
                state_topic = topics.stateTopicFor(kind),
                availability_topic = topics.availability,
                device_class = segment,
                off_delay = kind.offDelaySeconds,
                device = DeviceInfo(
                    identifiers = listOf("lenscast_$deviceId"),
                    name = deviceName,
                    model = deviceName,
                ),
            ),
        )
    }

    /** The HA discovery wire shape (snake_case keys are the broker contract). */
    private data class DiscoveryPayload(
        val name: String,
        val unique_id: String,
        val state_topic: String,
        val availability_topic: String,
        val payload_on: String = "ON",
        val payload_off: String = "OFF",
        val device_class: String,
        val off_delay: Int,
        val device: DeviceInfo,
    )

    private data class DeviceInfo(
        val identifiers: List<String>,
        val name: String,
        val model: String,
        val manufacturer: String = "LensCast",
    )

    private val adapter by lazy { AppJson.moshi.adapter(DiscoveryPayload::class.java) }
}
