package com.raulshma.lenscast.core.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The publisher's connection lifecycle choreography over a real loopback
 * socket — the parts nvr-integration.md promises that no pure test pinned:
 * the CONNECT carries a retained-offline last will on the availability
 * topic, the announce publishes are retained (discovery configs + online),
 * and a graceful close publishes a retained `offline` and clears the
 * retained discovery configs before the DISCONNECT (which would otherwise
 * suppress the will and strand the retained online — and the entities).
 */
class MqttAlertPublisherLifecycleTest {

    private class Frame(val header: Int, val body: ByteArray) {
        val type: Int get() = (header and 0xF0) shr 4
        val qos: Int get() = (header shr 1) and 0x03
        val retained: Boolean get() = header and 0x01 != 0
    }

    /** The broker half of the socket: frame-accurate reads and canned replies. */
    private class Broker(socket: Socket) {
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()

        fun readFrame(): Frame {
            val header = input.read()
            if (header < 0) error("Broker stream closed before a frame header")
            var multiplier = 1
            var length = 0
            while (true) {
                val digit = input.read()
                if (digit < 0) error("Broker stream closed mid remaining-length")
                length += (digit and 0x7F) * multiplier
                multiplier *= 128
                if (digit and 0x80 == 0) break
            }
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(body, offset, length - offset)
                if (read < 0) error("Broker stream closed mid body")
                offset += read
            }
            return Frame(header, body)
        }

        fun connack() {
            output.write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
            output.flush()
        }

        fun puback(packetId: Int) {
            output.write(
                byteArrayOf(0x40, 0x02, ((packetId shr 8) and 0xFF).toByte(), (packetId and 0xFF).toByte()),
            )
            output.flush()
        }
    }

    /** CONNECT variable header: protocol name + level precede the flags byte. */
    private fun connectFlags(body: ByteArray): Int {
        val nameLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        return body[2 + nameLength + 1].toInt() and 0xFF
    }

    private fun publishTopic(body: ByteArray): String {
        val length = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        return String(body, 2, length, Charsets.UTF_8)
    }

    private fun publishPayload(body: ByteArray): String {
        val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        // QoS 1: topic, 2-byte packet id, then the payload.
        val payloadStart = 2 + topicLength + 2
        return String(body, payloadStart, body.size - payloadStart, Charsets.UTF_8)
    }

    private fun publishPacketId(body: ByteArray): Int {
        val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        return ((body[2 + topicLength].toInt() and 0xFF) shl 8) or
            (body[3 + topicLength].toInt() and 0xFF)
    }

    @Test
    fun `announce is retained and close publishes retained offline and clears discovery before disconnect`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val publisher = MqttAlertPublisher(
                configProvider = {
                    MqttAlertPublisher.Config(
                        enabled = true,
                        broker = MqttAlertPublisher.Broker("127.0.0.1", server.localPort, "", "", false),
                        discoveryPrefix = "ha",
                    )
                },
                deviceId = "dev1",
                deviceName = "Test Phone",
            )
            publisher.start()

            val broker = Broker(server.accept())
            try {
                val connect = broker.readFrame()
                assertEquals(1, connect.type)
                // Clean session + will flag + will retained — the LWT that
                // flips HA offline when the device dies without DISCONNECT.
                assertEquals(0x02 or 0x04 or 0x20, connectFlags(connect.body))
                broker.connack()

                // Announce: one retained discovery config per sensor kind,
                // then the retained availability online.
                val topics = mutableListOf<String>()
                repeat(MqttTopics.SensorKind.entries.size + 1) { index ->
                    val frame = broker.readFrame()
                    assertEquals(3, frame.type)
                    assertEquals(1, frame.qos)
                    assertTrue("announce publish $index must be retained", frame.retained)
                    val topic = publishTopic(frame.body)
                    if (index < MqttTopics.SensorKind.entries.size) {
                        assertTrue(topic.startsWith("ha/binary_sensor/lenscast_dev1_"))
                    } else {
                        assertEquals("ha/lenscast/dev1/status", topic)
                        assertEquals("online", publishPayload(frame.body))
                    }
                    broker.puback(publishPacketId(frame.body))
                    topics.add(topic)
                }

                // Close: retained offline FIRST (the DISCONNECT that follows
                // suppresses the will), then one empty retained publish per
                // discovery config (the broker-side delete that makes HA drop
                // the entities), then the graceful DISCONNECT itself.
                publisher.close()
                val offline = broker.readFrame()
                assertEquals(3, offline.type)
                assertTrue("close offline must be retained", offline.retained)
                assertEquals("ha/lenscast/dev1/status", publishTopic(offline.body))
                assertEquals("offline", publishPayload(offline.body))
                broker.puback(publishPacketId(offline.body))

                val cleared = mutableListOf<String>()
                repeat(MqttTopics.SensorKind.entries.size) { index ->
                    val clear = broker.readFrame()
                    assertEquals(3, clear.type)
                    assertEquals(1, clear.qos)
                    assertTrue("discovery clear $index must be retained", clear.retained)
                    assertEquals("", publishPayload(clear.body))
                    broker.puback(publishPacketId(clear.body))
                    cleared.add(publishTopic(clear.body))
                }
                // Every announced discovery topic is cleared, and nothing else.
                assertEquals(topics.dropLast(1).sorted(), cleared.sorted())

                assertEquals(14, broker.readFrame().type) // DISCONNECT
            } finally {
                publisher.close()
            }
        }
    }

    @Test
    fun `close clears the retained stream states the announce published`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val publisher = MqttAlertPublisher(
                configProvider = {
                    MqttAlertPublisher.Config(
                        enabled = true,
                        broker = MqttAlertPublisher.Broker("127.0.0.1", server.localPort, "", "", false),
                        discoveryPrefix = "ha",
                    )
                },
                deviceId = "dev1",
                deviceName = "Test Phone",
                streamStatesProvider = { mapOf(MqttTopics.StreamOutput.WEB to true) },
            )
            publisher.start()

            val broker = Broker(server.accept())
            try {
                assertEquals(1, broker.readFrame().type) // CONNECT
                broker.connack()

                // Announce: one retained discovery config per sensor kind,
                // then the retained availability online, then the retained
                // stream-state truth for the one live output.
                repeat(MqttTopics.SensorKind.entries.size + 1) { index ->
                    val frame = broker.readFrame()
                    assertEquals(3, frame.type)
                    assertEquals(1, frame.qos)
                    assertTrue("announce publish $index must be retained", frame.retained)
                    if (index < MqttTopics.SensorKind.entries.size) {
                        assertTrue(publishTopic(frame.body).startsWith("ha/binary_sensor/lenscast_dev1_"))
                    } else {
                        assertEquals("ha/lenscast/dev1/status", publishTopic(frame.body))
                        assertEquals("online", publishPayload(frame.body))
                    }
                    broker.puback(publishPacketId(frame.body))
                }
                val streamState = broker.readFrame()
                assertEquals(3, streamState.type)
                assertTrue("the stream state must be retained", streamState.retained)
                assertEquals("ha/lenscast/dev1/stream/web/state", publishTopic(streamState.body))
                assertEquals("ON", publishPayload(streamState.body))
                broker.puback(publishPacketId(streamState.body))

                // Close: retained offline, then the discovery configs, then —
                // the pin — one retained empty per stream output (every
                // output's state topic, not just the live one: the close path
                // clears exactly what went out), then the DISCONNECT.
                publisher.close()
                val offline = broker.readFrame()
                assertEquals(3, offline.type)
                assertTrue("close offline must be retained", offline.retained)
                assertEquals("ha/lenscast/dev1/status", publishTopic(offline.body))
                assertEquals("offline", publishPayload(offline.body))
                broker.puback(publishPacketId(offline.body))

                repeat(MqttTopics.SensorKind.entries.size) { index ->
                    val clear = broker.readFrame()
                    assertEquals(3, clear.type)
                    assertTrue("discovery clear $index must be retained", clear.retained)
                    assertEquals("", publishPayload(clear.body))
                    broker.puback(publishPacketId(clear.body))
                }
                val clearedStreams = mutableListOf<String>()
                repeat(MqttTopics.StreamOutput.entries.size) { index ->
                    val clear = broker.readFrame()
                    assertEquals(3, clear.type)
                    assertTrue("stream-state clear $index must be retained", clear.retained)
                    assertEquals("", publishPayload(clear.body))
                    clearedStreams.add(publishTopic(clear.body))
                    broker.puback(publishPacketId(clear.body))
                }
                assertEquals(
                    MqttTopics.StreamOutput.entries
                        .map { "ha/lenscast/dev1/stream/${it.wireName}/state" }
                        .sorted(),
                    clearedStreams.sorted(),
                )

                assertEquals(14, broker.readFrame().type) // DISCONNECT
            } finally {
                publisher.close()
            }
        }
    }

    @Test
    fun `a telemetry flip re-runs the connection lifecycle and the changed path clears the telemetry entities`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var config = MqttAlertPublisher.Config(
                enabled = true,
                broker = MqttAlertPublisher.Broker("127.0.0.1", server.localPort, "", "", false),
                discoveryPrefix = "ha",
                telemetryEnabled = true,
            )
            val publisher = MqttAlertPublisher(
                configProvider = { config },
                deviceId = "dev1",
                deviceName = "Test Phone",
            )
            publisher.start()

            val broker1 = Broker(server.accept())
            try {
                assertEquals(1, broker1.readFrame().type) // CONNECT
                broker1.connack()

                // Announce under telemetry-on: the binary_sensor configs, one
                // sensor discovery config per telemetry entity, then online.
                val telemetryCount = MqttTopics.TelemetrySensor.entries.size
                repeat(MqttTopics.SensorKind.entries.size + telemetryCount + 1) { index ->
                    val frame = broker1.readFrame()
                    assertEquals(3, frame.type)
                    assertTrue(frame.retained)
                    broker1.puback(publishPacketId(frame.body))
                    when {
                        index < MqttTopics.SensorKind.entries.size ->
                            assertTrue(publishTopic(frame.body).startsWith("ha/binary_sensor/"))
                        index < MqttTopics.SensorKind.entries.size + telemetryCount ->
                            assertTrue(publishTopic(frame.body).startsWith("ha/sensor/"))
                        else -> assertEquals("ha/lenscast/dev1/status", publishTopic(frame.body))
                    }
                }

                // Flip the telemetry toggle off (same broker — the config
                // VALUE change alone re-runs the lifecycle): the changed path
                // publishes the retained offline under the OLD truth, clears
                // the binary_sensor configs and every telemetry discovery AND
                // state topic, disconnects, reconnects, and re-announces the
                // base discovery with NO telemetry entities.
                config = config.copy(telemetryEnabled = false)
                publisher.start()

                val offline = broker1.readFrame()
                assertTrue(offline.retained)
                assertEquals("ha/lenscast/dev1/status", publishTopic(offline.body))
                assertEquals("offline", publishPayload(offline.body))
                broker1.puback(publishPacketId(offline.body))

                repeat(MqttTopics.SensorKind.entries.size) { index ->
                    val clear = broker1.readFrame()
                    assertEquals("", publishPayload(clear.body))
                    assertTrue(publishTopic(clear.body).startsWith("ha/binary_sensor/"))
                    broker1.puback(publishPacketId(clear.body))
                }
                repeat(telemetryCount) { index ->
                    val sensor = MqttTopics.TelemetrySensor.entries[index]
                    val discoveryClear = broker1.readFrame()
                    assertEquals("", publishPayload(discoveryClear.body))
                    assertEquals(
                        "ha/sensor/lenscast_dev1_${sensor.wireName}/config",
                        publishTopic(discoveryClear.body),
                    )
                    broker1.puback(publishPacketId(discoveryClear.body))
                    val stateClear = broker1.readFrame()
                    assertEquals("", publishPayload(stateClear.body))
                    assertEquals(
                        "ha/lenscast/dev1/${sensor.wireName}/state",
                        publishTopic(stateClear.body),
                    )
                    broker1.puback(publishPacketId(stateClear.body))
                }
                assertEquals(14, broker1.readFrame().type) // DISCONNECT

                // The reconnect announces the base truth only: no telemetry
                // entity may exist behind the flip.
                val broker2 = Broker(server.accept())
                assertEquals(1, broker2.readFrame().type) // CONNECT
                broker2.connack()
                repeat(MqttTopics.SensorKind.entries.size + 1) { index ->
                    val frame = broker2.readFrame()
                    assertEquals(3, frame.type)
                    assertTrue(frame.retained)
                    broker2.puback(publishPacketId(frame.body))
                    if (index < MqttTopics.SensorKind.entries.size) {
                        assertTrue(publishTopic(frame.body).startsWith("ha/binary_sensor/"))
                    } else {
                        assertEquals("ha/lenscast/dev1/status", publishTopic(frame.body))
                        assertEquals("online", publishPayload(frame.body))
                    }
                }
            } finally {
                publisher.close()
            }
        }
    }

    @Test
    fun `a changed discovery prefix clears the old prefix's entities before the new prefix's announce`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var config = MqttAlertPublisher.Config(
                enabled = true,
                broker = MqttAlertPublisher.Broker("127.0.0.1", server.localPort, "", "", false),
                discoveryPrefix = "ha",
            )
            val publisher = MqttAlertPublisher(
                configProvider = { config },
                deviceId = "dev1",
                deviceName = "Test Phone",
            )
            publisher.start()

            val broker1 = Broker(server.accept())
            try {
                assertEquals(1, broker1.readFrame().type) // CONNECT
                broker1.connack()

                // First announce under prefix "ha".
                repeat(MqttTopics.SensorKind.entries.size + 1) { index ->
                    val frame = broker1.readFrame()
                    assertEquals(3, frame.type)
                    assertTrue(frame.retained)
                    if (index < MqttTopics.SensorKind.entries.size) {
                        assertTrue(publishTopic(frame.body).startsWith("ha/binary_sensor/"))
                    } else {
                        assertEquals("ha/lenscast/dev1/status", publishTopic(frame.body))
                    }
                    broker1.puback(publishPacketId(frame.body))
                }

                // Move to prefix "hb": the old prefix's retained availability
                // and discovery configs are cleared (and the connection
                // dropped) BEFORE anything under the new prefix goes out — a
                // changed prefix must never orphan the old prefix's entities.
                config = config.copy(discoveryPrefix = "hb")
                publisher.start()

                val offline = broker1.readFrame()
                assertTrue(offline.retained)
                assertEquals("ha/lenscast/dev1/status", publishTopic(offline.body))
                assertEquals("offline", publishPayload(offline.body))
                broker1.puback(publishPacketId(offline.body))
                repeat(MqttTopics.SensorKind.entries.size) { index ->
                    val clear = broker1.readFrame()
                    assertTrue(clear.retained)
                    assertEquals("", publishPayload(clear.body))
                    assertTrue(publishTopic(clear.body).startsWith("ha/binary_sensor/"))
                    broker1.puback(publishPacketId(clear.body))
                }
                assertEquals(14, broker1.readFrame().type) // DISCONNECT

                // The new connection announces under the NEW prefix only.
                val broker2 = Broker(server.accept())
                assertEquals(1, broker2.readFrame().type) // CONNECT
                broker2.connack()
                repeat(MqttTopics.SensorKind.entries.size + 1) { index ->
                    val frame = broker2.readFrame()
                    assertEquals(3, frame.type)
                    assertTrue(frame.retained)
                    if (index < MqttTopics.SensorKind.entries.size) {
                        assertTrue(publishTopic(frame.body).startsWith("hb/binary_sensor/"))
                    } else {
                        assertEquals("hb/lenscast/dev1/status", publishTopic(frame.body))
                        assertEquals("online", publishPayload(frame.body))
                    }
                    broker2.puback(publishPacketId(frame.body))
                }
            } finally {
                publisher.close()
            }
        }
    }
}
