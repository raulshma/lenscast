package com.raulshma.lenscast.core.mqtt

import android.util.Log
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.DetectionAlert
import com.raulshma.lenscast.core.DetectionEventWire
import com.raulshma.lenscast.core.EventKind

/**
 * The MQTT half of the detection-event dispatch, beside [com.raulshma.lenscast.core.WebhookNotifier]:
 * a JSON event POST-equivalent published to the user's broker, plus the
 * Home Assistant discovery + availability choreography that turns LensCast's
 * motion/sound/tamper events into first-class `binary_sensor` entities.
 *
 * Beyond the alerts it is also the device's telemetry publisher, reusing the
 * same connection, discovery, and availability patterns:
 *  - stream lifecycle: a retained `ON`/`OFF` on `stream/<output>/state` per
 *    push output (web, RTSP, RTMP, WHIP, SRT), republished from the owner's
 *    [streamStatesProvider] at every announce so a broker restart or device
 *    reconnect always replays the truth; gated by the alert enable only;
 *  - client events: non-retained, throttled (`MqttTelemetryPolicy`)
 *    connect/disconnect JSON on `clients/<kind>/event`;
 *  - telemetry sensors: battery %, thermal state, encoded video bitrate, and
 *    active client counts on a 30 s cadence, each an HA `sensor` discovery
 *    entity beside the binary_sensors.
 * Stream states follow the alert publisher's enable (the settings gate);
 * client events and the sensor tick additionally require the
 * `telemetryEnabled` toggle inside [Config].
 *
 * One daemon worker serializes dispatches. [start] — wired by the composition
 * root to the MQTT settings — connects and announces (discovery configs once
 * per connection, availability online, retained stream states) the moment
 * MQTT is enabled, so the HA entities exist before the first event; a
 * dispatch after a dead connection reconnects and re-announces the same way.
 * A config change closes the connection so the next connect runs under the
 * new endpoint and re-announces. A dead broker costs one connect attempt per
 * start/dispatch/tick and never blocks the detection path. The go/no-go
 * verdict ([willDispatch], claimed by the caller into the event log) mirrors
 * the webhook notifier's contract.
 *
 * Availability honesty: the online message and the last will are both
 * retained, so a broker restart replays the truth — a live device shows
 * online, a device that died without DISCONNECT shows the broker-published
 * will (offline), never a stale online.
 */
class MqttAlertPublisher(
    private val configProvider: () -> Config,
    private val deviceId: String,
    private val deviceName: String,
    /** The live per-output stream states, read at announce time (retained truth replay). */
    private val streamStatesProvider: () -> Map<MqttTopics.StreamOutput, Boolean> = { emptyMap() },
    /** The live telemetry readings; a null value omits that sensor's publish for the tick. */
    private val telemetrySnapshotProvider: () -> TelemetrySnapshot = { TelemetrySnapshot() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Broker address + credentials exactly as the settings store holds them. */
    data class Broker(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val tls: Boolean,
    ) {
        // The generated toString would carry the broker password into any log
        // line that includes the broker — the one redaction home both this and
        // [MqttClient.Endpoint]'s toString follow.
        override fun toString(): String =
            "Broker(host=$host, port=$port, username=$username, password=****, tls=$tls)"
    }

    /**
     * The live telemetry readings for one sensor tick. Null members are
     * honestly omitted from the wire (no reading — no publish), so a sensor
     * that cannot report never publishes an invented value.
     */
    data class TelemetrySnapshot(
        val batteryPercent: Int? = null,
        val thermal: String? = null,
        val encodedBitrateBps: Int? = null,
        val activeClients: Int? = null,
    )

    data class Config(
        val enabled: Boolean,
        val broker: Broker,
        val discoveryPrefix: String,
        /** Gates the client-event and sensor-telemetry publishes (never the alerts or the stream states). */
        val telemetryEnabled: Boolean = false,
    ) {
        override fun toString(): String =
            "Config(enabled=$enabled, broker=$broker, discoveryPrefix=$discoveryPrefix, telemetryEnabled=$telemetryEnabled)"
    }

    private val client = MqttClient()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MqttAlertPublisher").apply { isDaemon = true }
    }

    /** The slow telemetry cadence: sensors on the fixed [MqttTelemetryPolicy.TELEMETRY_INTERVAL_MS] tick. */
    private val telemetryExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MqttTelemetry").apply { isDaemon = true }
    }

    @Volatile private var lastConfig: Config? = null
    /** Whether discovery + availability went out on the current connection. */
    private var announced = false

    // What the last announce actually published under this connection — the
    // close path clears exactly that, so a disable never spams the broker
    // with retained empties for entities it never created.
    private var announcedTelemetry = false
    private var publishedStreamStates = false

    /** The client-event throttle state (kind → last-sent ms), pure-policed. */
    private val clientEventSentMs = HashMap<String, Long>()

    init {
        telemetryExecutor.scheduleWithFixedDelay(
            ::telemetryTick,
            MqttTelemetryPolicy.TELEMETRY_INTERVAL_MS,
            MqttTelemetryPolicy.TELEMETRY_INTERVAL_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
    }

    /**
     * Queue the event for publish. True when a publish would really go out —
     * the caller claims "mqtt" in the event log at dispatch time (the attempt
     * counts, like the webhook's contract); false when disabled or unhosted.
     * The JSON body is built here from the alert's own timestamp, so both
     * remote sinks carry the identical notify-moment stamp — the doc's "same
     * JSON payload as the webhook" holds for the timestamp too. [deepLink] is
     * the dashboard deep link riding the body's `url` field, so an automation
     * message can link straight into the events feed.
     */
    fun notifyEvent(alert: DetectionAlert, deepLink: String? = null): Boolean {
        val config = configProvider()
        if (!willDispatch(config)) return false
        val body = DetectionEventWire.encode(alert, deepLink)
        executor.execute { dispatch(alert, body, config) }
        return true
    }

    /**
     * Connects and announces now, without waiting for an event: the entities
     * must exist (and show available) as soon as MQTT is enabled, not at the
     * first motion. Idempotent — a live connection under the same config
     * no-ops; a changed config disconnects and re-announces under the new
     * endpoint. No-op when disabled or unhosted.
     */
    fun start() {
        val config = configProvider()
        if (!willDispatch(config)) return
        executor.execute {
            try {
                ensureConnection(config)
                announceOnce(config)
            } catch (e: Exception) {
                logFailed("connect", config, e)
            }
        }
    }

    /**
     * Closes the connection gracefully. Before the DISCONNECT goes out, a
     * live connection publishes a retained `offline` on the availability
     * topic (DISCONNECT suppresses the last will, so without this the
     * retained `online` would survive broker restarts while the feature is
     * off — exactly the stale "available" the will exists to prevent) and
     * then clears the retained discovery configs, so the HA entities never
     * outlive the setting. Idempotent; a no-op when nothing was ever
     * announced. Used when the alerting feature turns off.
     */
    fun close() {
        executor.execute {
            lastConfig?.let {
                publishRetainedOffline(it)
                clearDiscoveryConfigs(it)
            }
            client.close()
        }
    }

    /**
     * Publishes a retained `ON`/`OFF` for one push output's live state, and
     * republishes every output's current state so a fresh connection (broker
     * restart, device reconnect, enable flip) always replays the truth on
     * the retained topics. Follows the alert publisher's enable gate only —
     * the stream states are alert-class truth, not telemetry.
     */
    fun notifyStreamState(output: MqttTopics.StreamOutput, on: Boolean) {
        val config = configProvider()
        if (!willDispatch(config)) return
        executor.execute {
            try {
                ensureConnection(config)
                announceOnce(config)
                client.publish(
                    topicsFor(config).streamStateTopicFor(output),
                    if (on) STATE_ON else STATE_OFF,
                    qos = 1,
                    retain = true,
                )
            } catch (e: Exception) {
                logFailed("stream state", config, e)
            }
        }
    }

    /**
     * Publishes one client connect/disconnect moment as non-retained JSON on
     * the `clients/<kind>/event` topic, behind the telemetry toggle and the
     * per-kind throttle (a flapping client cannot flood the broker). True
     * when the event was queued — a throttled or gated call is a quiet
     * no-op, not an error.
     */
    fun notifyClientEvent(
        kind: MqttTopics.ClientKind,
        connected: Boolean,
        activeCount: Int,
    ): Boolean {
        val config = configProvider()
        if (!willDispatch(config) || !config.telemetryEnabled) return false
        val queued = synchronized(this) {
            val stamp = MqttTelemetryPolicy.clientEventVerdict(clientEventSentMs[kind.wireName], nowMs())
            if (stamp != null) clientEventSentMs[kind.wireName] = stamp
            stamp != null
        }
        if (!queued) return false
        val body = MqttTopics.clientEventPayload(kind, connected, activeCount, nowMs())
        executor.execute {
            try {
                ensureConnection(config)
                announceOnce(config)
                client.publish(topicsFor(config).clientEventTopicFor(kind), body, qos = 1)
            } catch (e: Exception) {
                logFailed("client event", config, e)
            }
        }
        return true
    }

    /** One slow sensor tick: the snapshot's live readings onto the sensor state topics. */
    private fun telemetryTick() {
        val config = try {
            configProvider()
        } catch (_: Exception) {
            return
        }
        if (!MqttTelemetryPolicy.shouldPublishTelemetry(config.enabled, config.broker.host.isNotBlank(), config.telemetryEnabled)) {
            return
        }
        val snapshot = telemetrySnapshotProvider()
        executor.execute {
            try {
                ensureConnection(config)
                announceOnce(config)
                val topics = topicsFor(config)
                snapshot.batteryPercent?.let {
                    client.publish(topics.telemetryStateTopicFor(MqttTopics.TelemetrySensor.BATTERY), it.toString().toByteArray(), qos = 1)
                }
                snapshot.thermal?.let {
                    client.publish(topics.telemetryStateTopicFor(MqttTopics.TelemetrySensor.THERMAL), it.toByteArray(), qos = 1)
                }
                snapshot.encodedBitrateBps?.let {
                    client.publish(topics.telemetryStateTopicFor(MqttTopics.TelemetrySensor.ENCODED_BITRATE), it.toString().toByteArray(), qos = 1)
                }
                snapshot.activeClients?.let {
                    client.publish(topics.telemetryStateTopicFor(MqttTopics.TelemetrySensor.CLIENTS), it.toString().toByteArray(), qos = 1)
                }
            } catch (e: Exception) {
                logFailed("telemetry", config, e)
            }
        }
    }

    private fun dispatch(alert: DetectionAlert, body: ByteArray, config: Config) {
        try {
            val kind = MqttTopics.SensorKind.fromOrNull(alert.kind)
            // The test alert has no binary_sensor entity by design (nothing to
            // arm and no off-delay to simulate), so it publishes the event
            // JSON only; a genuinely unknown kind is not publishable at all.
            if (!MqttTopics.SensorKind.isPublishable(alert.kind)) {
                Log.w(TAG, "MQTT dispatch skipped: unknown sensor kind ${alert.kind}")
                return
            }
            ensureConnection(config)
            announceOnce(config)
            val topics = topicsFor(config)
            kind?.let { client.publish(topics.stateTopicFor(it), STATE_ON, qos = 1) }
            client.publish(topics.event, body, qos = 1)
        } catch (e: Exception) {
            logFailed("dispatch", config, e)
        }
    }

    /**
     * Connects when down (config changes force a reconnect and re-announce).
     * A changed config closes any live connection under the OLD endpoint —
     * and the graceful DISCONNECT suppresses the will, so the old broker's
     * retained `online` must be flipped to `offline` first, or it would
     * outlive the setting exactly the way the disable path's stale online
     * would. The old config's discovery configs are cleared the same way, so
     * a changed discovery prefix never orphans the old prefix's entities.
     */
    private fun ensureConnection(config: Config) {
        val changed = lastConfig != config
        if (changed) {
            lastConfig?.let {
                publishRetainedOffline(it)
                clearDiscoveryConfigs(it)
            }
            client.close()
            announced = false
            announcedTelemetry = false
            publishedStreamStates = false
            lastConfig = config
        }
        if (!client.isConnected) {
            val topics = topicsFor(config)
            client.connect(endpointOf(config, topics, deviceId))
            announced = false
        }
    }

    /** Retained `offline` under [config]'s topics; a no-op when not connected. */
    private fun publishRetainedOffline(config: Config) {
        if (!client.isConnected) return
        try {
            val topics = topicsFor(config)
            client.publish(topics.availability, STATE_OFFLINE, qos = 1, retain = true)
        } catch (e: Exception) {
            Log.w(TAG, "Retained offline on close failed: ${e.message}")
        }
    }

    /**
     * Removes the retained discovery configs under [config]'s topics: an
     * empty retained payload is the MQTT convention for "drop the retained
     * message", which makes Home Assistant drop the entities. Best-effort
     * like [publishRetainedOffline]; a no-op when not connected. The
     * telemetry sensor configs and the retained stream/sensor states go the
     * same way, so nothing outlives the setting.
     */
    private fun clearDiscoveryConfigs(config: Config) {
        if (!client.isConnected) return
        try {
            val topics = topicsFor(config)
            for (kind in MqttTopics.SensorKind.entries) {
                client.publish(topics.discoveryTopicFor(kind), EMPTY_PAYLOAD, qos = 1, retain = true)
            }
            if (announcedTelemetry) {
                for (sensor in MqttTopics.TelemetrySensor.entries) {
                    client.publish(topics.telemetryDiscoveryTopicFor(sensor), EMPTY_PAYLOAD, qos = 1, retain = true)
                    client.publish(topics.telemetryStateTopicFor(sensor), EMPTY_PAYLOAD, qos = 1, retain = true)
                }
            }
            if (publishedStreamStates) {
                for (output in MqttTopics.StreamOutput.entries) {
                    client.publish(topics.streamStateTopicFor(output), EMPTY_PAYLOAD, qos = 1, retain = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Discovery clear on close failed: ${e.message}")
        }
    }

    /** Discovery + availability + the retained stream-state replay, once per connection. */
    private fun announceOnce(config: Config) {
        if (announced) return
        announce(topicsFor(config), config)
        announced = true
    }

    private fun announce(topics: MqttTopics.EntityTopics, config: Config) {
        for (kind in MqttTopics.SensorKind.entries) {
            client.publish(
                topics.discoveryTopicFor(kind),
                MqttTopics.discoveryPayload(topics, deviceId, deviceName, kind).toByteArray(),
                qos = 1,
                retain = true,
            )
        }
        // The telemetry sensor entities exist only while the telemetry
        // toggle is on: a flip re-runs the connection lifecycle (the config
        // value changed), so the configs are announced or cleared in step.
        announcedTelemetry = config.telemetryEnabled
        if (announcedTelemetry) {
            for (sensor in MqttTopics.TelemetrySensor.entries) {
                client.publish(
                    topics.telemetryDiscoveryTopicFor(sensor),
                    MqttTopics.telemetryDiscoveryPayload(topics, deviceId, deviceName, sensor).toByteArray(),
                    qos = 1,
                    retain = true,
                )
            }
        }
        client.publish(topics.availability, STATE_ONLINE, qos = 1, retain = true)
        // The retained truth replay: whatever is live right now, as the
        // broker (and a HA restart) will remember it.
        val streamStates = streamStatesProvider()
        publishedStreamStates = streamStates.isNotEmpty()
        for ((output, on) in streamStates) {
            client.publish(topics.streamStateTopicFor(output), if (on) STATE_ON else STATE_OFF, qos = 1, retain = true)
        }
    }

    /** The entity topic set for [config]'s prefix — the one binding of [deviceId] every publish path resolves through. */
    private fun topicsFor(config: Config): MqttTopics.EntityTopics =
        MqttTopics.entityTopics(config.discoveryPrefix, deviceId)

    private fun logFailed(stage: String, config: Config, e: Exception) {
        Log.w(TAG, "MQTT $stage to ${config.broker.host}:${config.broker.port} failed: ${e.message}")
    }

    companion object {
        private const val TAG = "MqttAlertPublisher"
        private val STATE_ON = "ON".toByteArray()
        private val STATE_OFF = "OFF".toByteArray()
        private val STATE_ONLINE = "online".toByteArray()
        private val STATE_OFFLINE = "offline".toByteArray()

        /** A zero-byte payload — the retained-message delete on the broker. */
        private val EMPTY_PAYLOAD = ByteArray(0)

        /**
         * The broker-side endpoint derivation, pure over the config: MQTT
         * 3.1.2-22 (a password flag without a username flag is invalid —
         * spec-compliant brokers reject the CONNECT) means credentials travel
         * as a pair or not at all, and the will is the retained offline on
         * the availability topic. Companion-level so the pair rule is
         * JVM-tested.
         */
        fun endpointOf(
            config: Config,
            topics: MqttTopics.EntityTopics,
            deviceId: String,
        ): MqttClient.Endpoint {
            val username = config.broker.username.trim().takeIf { it.isNotEmpty() }
            return MqttClient.Endpoint(
                host = config.broker.host,
                port = config.broker.port,
                tls = config.broker.tls,
                username = username,
                password = config.broker.password.takeIf { !username.isNullOrEmpty() && it.isNotEmpty() },
                clientId = "lenscast_$deviceId",
                willTopic = topics.availability,
                willMessage = STATE_OFFLINE,
                willRetain = true,
            )
        }

        /**
         * The would-this-actually-publish decision behind [notifyEvent]:
         * enabled with a non-blank host. Kept separate and tested so the
         * event log never claims an action the publisher silently no-ops.
         */
        fun willDispatch(config: Config?): Boolean =
            config != null && config.enabled && config.broker.host.isNotBlank()
    }
}
