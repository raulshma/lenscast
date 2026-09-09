package com.raulshma.lenscast.core.mqtt

import android.util.Log
import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.core.DetectionAlert
import com.raulshma.lenscast.core.DetectionEventWire

/**
 * The MQTT half of the detection-event dispatch, beside [com.raulshma.lenscast.core.WebhookNotifier]:
 * a JSON event POST-equivalent published to the user's broker, plus the
 * Home Assistant discovery + availability choreography that turns LensCast's
 * motion/sound/tamper events into first-class `binary_sensor` entities.
 *
 * One daemon worker serializes dispatches. [start] — wired by the composition
 * root to the MQTT settings — connects and announces (discovery configs once
 * per connection, availability online) the moment MQTT is enabled, so the HA
 * entities exist before the first event; a dispatch after a dead connection
 * reconnects and re-announces the same way. A config change closes the
 * connection so the next connect runs under the new endpoint and re-announces.
 * A dead broker costs one connect attempt per start/dispatch and never blocks
 * the detection path. The go/no-go verdict ([willDispatch], claimed by the
 * caller into the event log) mirrors the webhook notifier's contract.
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

    data class Config(
        val enabled: Boolean,
        val broker: Broker,
        val discoveryPrefix: String,
    ) {
        override fun toString(): String =
            "Config(enabled=$enabled, broker=$broker, discoveryPrefix=$discoveryPrefix)"
    }

    private val client = MqttClient()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MqttAlertPublisher").apply { isDaemon = true }
    }

    @Volatile private var lastConfig: Config? = null
    /** Whether discovery + availability went out on the current connection. */
    private var announced = false

    /**
     * Queue the event for publish. True when a publish would really go out —
     * the caller claims "mqtt" in the event log at dispatch time (the attempt
     * counts, like the webhook's contract); false when disabled or unhosted.
     * The JSON body is built here from the alert's own timestamp, so both
     * remote sinks carry the identical notify-moment stamp — the doc's "same
     * JSON payload as the webhook" holds for the timestamp too.
     */
    fun notifyEvent(alert: DetectionAlert): Boolean {
        val config = configProvider()
        if (!willDispatch(config)) return false
        val body = DetectionEventWire.encode(alert)
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

    private fun dispatch(alert: DetectionAlert, body: ByteArray, config: Config) {
        try {
            val kind = MqttTopics.SensorKind.fromOrNull(alert.kind) ?: run {
                Log.w(TAG, "MQTT dispatch skipped: unknown sensor kind ${alert.kind}")
                return
            }
            ensureConnection(config)
            announceOnce(config)
            val topics = topicsFor(config)
            client.publish(topics.stateTopicFor(kind), STATE_ON, qos = 1)
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
     * like [publishRetainedOffline]; a no-op when not connected.
     */
    private fun clearDiscoveryConfigs(config: Config) {
        if (!client.isConnected) return
        try {
            val topics = topicsFor(config)
            for (kind in MqttTopics.SensorKind.entries) {
                client.publish(topics.discoveryTopicFor(kind), EMPTY_PAYLOAD, qos = 1, retain = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Discovery clear on close failed: ${e.message}")
        }
    }

    /** Discovery + availability, once per connection. */
    private fun announceOnce(config: Config) {
        if (announced) return
        announce(topicsFor(config))
        announced = true
    }

    private fun announce(topics: MqttTopics.EntityTopics) {
        for (kind in MqttTopics.SensorKind.entries) {
            client.publish(
                topics.discoveryTopicFor(kind),
                MqttTopics.discoveryPayload(topics, deviceId, deviceName, kind).toByteArray(),
                qos = 1,
                retain = true,
            )
        }
        client.publish(topics.availability, STATE_ONLINE, qos = 1, retain = true)
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
