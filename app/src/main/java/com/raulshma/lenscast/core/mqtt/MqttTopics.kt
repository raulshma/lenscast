package com.raulshma.lenscast.core.mqtt

import com.squareup.moshi.JsonClass

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
 *
 * The telemetry half rides the same base: `stream/<output>/state` (retained
 * `ON`/`OFF` per push output — [StreamOutput]), `<sensor>/state` (periodic
 * sensor readings — [TelemetrySensor], each with its `sensor` discovery
 * config), and `clients/<kind>/event` (non-retained client connect/disconnect
 * JSON — [ClientKind]).
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

            /**
             * Whether a dispatch of [kind] publishes at all: the three sensor
             * kinds pulse their `binary_sensor` and publish the event JSON;
             * the test kind has no sensor (nothing to arm, no off-delay to
             * simulate) but still publishes the event JSON only. Anything
             * else has no honest wire representation and is skipped.
             */
            fun isPublishable(kind: EventKind): Boolean =
                fromOrNull(kind) != null || kind == EventKind.TEST
        }
    }

    /**
     * The push outputs whose live state is published as a retained
     * `ON`/`OFF` on `stream/<wireName>/state`. The wire names match the
     * `/api/stream/<name>/start|stop` route segments.
     */
    enum class StreamOutput(val wireName: String) {
        WEB("web"),
        RTSP("rtsp"),
        RTMP("rtmp"),
        WHIP("whip"),
        SRT("srt"),
    }

    /**
     * The periodic telemetry sensors, one HA `sensor` discovery entity each.
     * `unit`/`deviceClass`/`stateClass`/`icon` are the discovery fields that
     * make the entity render well; null fields are omitted from the payload.
     */
    enum class TelemetrySensor(
        val wireName: String,
        val unit: String?,
        val deviceClass: String?,
        val stateClass: String?,
        val icon: String?,
    ) {
        BATTERY("battery", "%", "battery", "measurement", null),
        THERMAL("thermal", null, null, null, "mdi:thermometer"),
        ENCODED_BITRATE("bitrate", "bit/s", "data_rate", "measurement", null),
        CLIENTS("clients", "clients", null, "measurement", "mdi:account-multiple"),
    }

    /** The client populations whose connect/disconnect moments are published. */
    enum class ClientKind(val wireName: String) {
        MJPEG("mjpeg"),
        RTSP("rtsp"),
    }

    class EntityTopics(
        val availability: String,
        val event: String,
        private val stateTopics: Map<SensorKind, String>,
        private val discoveryTopics: Map<SensorKind, String>,
        private val streamStateTopics: Map<StreamOutput, String>,
        private val telemetryStateTopics: Map<TelemetrySensor, String>,
        private val telemetryDiscoveryTopics: Map<TelemetrySensor, String>,
        private val clientEventTopics: Map<ClientKind, String>,
    ) {
        /** The state topic a [kind] event publishes its ON pulse to. */
        fun stateTopicFor(kind: SensorKind): String = stateTopics.getValue(kind)

        /** The discovery config topic for [kind]'s binary_sensor. */
        fun discoveryTopicFor(kind: SensorKind): String = discoveryTopics.getValue(kind)

        /** The retained `ON`/`OFF` state topic for one push output. */
        fun streamStateTopicFor(output: StreamOutput): String = streamStateTopics.getValue(output)

        /** The state topic one telemetry sensor publishes its reading to. */
        fun telemetryStateTopicFor(sensor: TelemetrySensor): String = telemetryStateTopics.getValue(sensor)

        /** The discovery config topic for one telemetry sensor's `sensor` entity. */
        fun telemetryDiscoveryTopicFor(sensor: TelemetrySensor): String = telemetryDiscoveryTopics.getValue(sensor)

        /** The non-retained client-event topic for one client population. */
        fun clientEventTopicFor(kind: ClientKind): String = clientEventTopics.getValue(kind)
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
            streamStateTopics = StreamOutput.entries.associateWith { output -> "$base/stream/${output.wireName}/state" },
            telemetryStateTopics = TelemetrySensor.entries.associateWith { sensor -> "$base/${sensor.wireName}/state" },
            telemetryDiscoveryTopics = TelemetrySensor.entries.associateWith { sensor ->
                "$prefix/sensor/${entityId(deviceId, sensor.wireName)}/config"
            },
            clientEventTopics = ClientKind.entries.associateWith { kind -> "$base/clients/${kind.wireName}/event" },
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
                device = deviceInfo(deviceId, deviceName),
            ),
        )
    }

    /**
     * The HA discovery config for one telemetry sensor: a plain `sensor`
     * entity whose value class and unit come from the [sensor]'s own fields
     * (omitted when null, so a string sensor like `thermal` stays string).
     */
    fun telemetryDiscoveryPayload(
        topics: EntityTopics,
        deviceId: String,
        deviceName: String,
        sensor: TelemetrySensor,
    ): String {
        val segment = sensor.wireName
        return sensorAdapter.toJson(
            SensorDiscoveryPayload(
                name = "LensCast ${segment.replaceFirstChar { it.uppercase(Locale.US) }}",
                unique_id = entityId(deviceId, segment),
                state_topic = topics.telemetryStateTopicFor(sensor),
                availability_topic = topics.availability,
                device_class = sensor.deviceClass,
                state_class = sensor.stateClass,
                unit_of_measurement = sensor.unit,
                icon = sensor.icon,
                device = deviceInfo(deviceId, deviceName),
            ),
        )
    }

    /** The event JSON a client connect/disconnect moment publishes (non-retained). */
    fun clientEventPayload(
        kind: ClientKind,
        connected: Boolean,
        activeCount: Int,
        timestampMs: Long,
    ): ByteArray =
        clientEventAdapter.toJson(
            ClientEventWire(
                kind = kind.wireName,
                event = if (connected) EVENT_CONNECTED else EVENT_DISCONNECTED,
                count = activeCount,
                timestampMs = timestampMs,
            ),
        ).toByteArray(Charsets.UTF_8)

    const val EVENT_CONNECTED = "connected"
    const val EVENT_DISCONNECTED = "disconnected"

    private fun deviceInfo(deviceId: String, deviceName: String) = DeviceInfo(
        identifiers = listOf("lenscast_$deviceId"),
        name = deviceName,
        model = deviceName,
    )

    /** The HA discovery wire shape (snake_case keys are the broker contract). */
    @JsonClass(generateAdapter = true)
    internal data class DiscoveryPayload(
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

    /** The telemetry sensor's discovery wire shape; null fields are omitted. */
    @JsonClass(generateAdapter = true)
    internal data class SensorDiscoveryPayload(
        val name: String,
        val unique_id: String,
        val state_topic: String,
        val availability_topic: String,
        val device_class: String? = null,
        val state_class: String? = null,
        val unit_of_measurement: String? = null,
        val icon: String? = null,
        val device: DeviceInfo,
    )

    /** The client connect/disconnect event JSON (the `clients/<kind>/event` topic). */
    @JsonClass(generateAdapter = true)
    internal data class ClientEventWire(
        val kind: String,
        val event: String,
        val count: Int,
        val timestampMs: Long,
    )

    @JsonClass(generateAdapter = true)
    internal data class DeviceInfo(
        val identifiers: List<String>,
        val name: String,
        val model: String,
        val manufacturer: String = "LensCast",
    )

    private val adapter by lazy { AppJson.moshi.adapter(DiscoveryPayload::class.java) }
    private val sensorAdapter by lazy { AppJson.moshi.adapter(SensorDiscoveryPayload::class.java) }
    private val clientEventAdapter by lazy { AppJson.moshi.adapter(ClientEventWire::class.java) }
}
