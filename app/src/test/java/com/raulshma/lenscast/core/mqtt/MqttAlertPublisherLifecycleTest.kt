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
}
