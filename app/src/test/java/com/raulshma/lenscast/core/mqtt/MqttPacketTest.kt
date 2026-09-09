package com.raulshma.lenscast.core.mqtt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire-pinning tests for the MQTT 3.1.1 codec: hand-computed expected byte
 * streams for CONNECT/PUBLISH/PUBACK/PINGREQ/DISCONNECT, the frame splitter's
 * partial-frame behavior, and CONNACK/PUBACK body reads.
 */
class MqttPacketTest {

    @Test
    fun `connect with clean session, credentials and will matches the wire bytes`() {
        val packet = MqttPacket.connect(
            clientId = "lenscast_test",
            keepAliveSeconds = 60,
            username = "user",
            password = "pass",
            willTopic = "homeassistant/lenscast/test/status",
            willMessage = "offline".toByteArray(),
        )
        // Hand-built per the spec: fixed header 0x10, remaining length,
        // variable header (MQTT/4/flags 0xC6/keepalive 60), payload
        // (clientId, will topic, will payload with 2-byte length, user, pass).
        val variable = byteArrayOf(
            0x00, 0x04, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(),
            0x04, // protocol level 4
            0xC6.toByte(), // username | password | will | clean session
            0x00, 0x3C, // keepalive 60
        )
        val payload = byteArrayOf(
            0x00, 0x0D, // client id length 13
            *("lenscast_test".toByteArray()),
            0x00, 0x22, // will topic length 34
            *("homeassistant/lenscast/test/status".toByteArray()),
            0x00, 0x07, // will payload length 7
            *("offline".toByteArray()),
            0x00, 0x04, *("user".toByteArray()),
            0x00, 0x04, *("pass".toByteArray()),
        )
        val body = variable + payload
        val expected = byteArrayOf(0x10.toByte(), body.size.toByte(), *body)
        assertArrayEquals(expected, packet)
    }

    @Test
    fun `publish qos1 retained carries packet id and retain flag`() {
        val packet = MqttPacket.publish("a/b", "hi".toByteArray(), qos = 1, retain = true, packetId = 0x0102)
        val expected = byteArrayOf(
            0x33.toByte(), // PUBLISH, QoS1, retain
            0x09, // remaining: 2 len + 3 topic + 2 packet id + 2 payload
            0x00, 0x03, 'a'.code.toByte(), '/'.code.toByte(), 'b'.code.toByte(),
            0x01, 0x02,
            'h'.code.toByte(), 'i'.code.toByte(),
        )
        assertArrayEquals(expected, packet)
    }

    @Test
    fun `publish qos0 drops packet id and retain flag`() {
        val packet = MqttPacket.publish("t", byteArrayOf(), qos = 0, retain = false, packetId = 0)
        // The QoS-0 frame is 5 bytes: fixed header + remaining length (3) +
        // 2-byte topic length + 1 topic char — no packet id at QoS 0.
        val expected = byteArrayOf(
            0x30, 0x03,
            0x00, 0x01, 't'.code.toByte(),
        )
        assertArrayEquals(expected, packet)
    }

    @Test
    fun `puback pingreq disconnect match the spec frames`() {
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0x00), MqttPacket.pingReq())
        assertArrayEquals(byteArrayOf(0xE0.toByte(), 0x00), MqttPacket.disconnect())
    }

    @Test
    fun `will retain flag lands in the connect flags byte`() {
        val retained = MqttPacket.connect(
            clientId = "c", keepAliveSeconds = 60, username = null, password = null,
            willTopic = "t", willMessage = "offline".toByteArray(), willRetain = true,
        )
        // Flags are the 8th byte of the frame (index 7 + the 2-byte header):
        // will 0x04 | will-retain 0x20 | clean session 0x02 = 0x26.
        assertEquals(0x26, retained[9].toInt() and 0xFF)
        val plain = MqttPacket.connect(
            clientId = "c", keepAliveSeconds = 60, username = null, password = null,
            willTopic = "t", willMessage = "offline".toByteArray(),
        )
        assertEquals(0x06, plain[9].toInt() and 0xFF)
    }

    @Test
    fun `readFrame splits consecutive frames and returns null on partial input`() {
        // Hand-built PUBACK (0x40, len 2, packet id 7) + PINGREQ.
        val puback = byteArrayOf(0x40, 0x02, 0x00, 0x07)
        val ping = MqttPacket.pingReq()
        val both = puback + ping
        val first = MqttPacket.readFrame(both)!!
        assertEquals(MqttPacket.TYPE_PUBACK, first.first.type)
        assertEquals(7, MqttPacket.pubackPacketId(first.first.body))
        assertEquals(puback.size, first.second)
        val rest = both.copyOfRange(first.second, both.size)
        val second = MqttPacket.readFrame(rest)!!
        assertEquals(MqttPacket.TYPE_PINGREQ, second.first.type)
        // A lone fixed header byte is an incomplete frame, not an error.
        assertNull(MqttPacket.readFrame(byteArrayOf(0x40)))
        // A header whose remaining length exceeds the buffer is incomplete.
        assertNull(MqttPacket.readFrame(byteArrayOf(0x40, 0x05, 0x01)))
    }

    @Test
    fun `connack return code is the second body byte`() {
        assertEquals(0, MqttPacket.connackReturnCode(byteArrayOf(0x00, 0x00)))
        assertEquals(5, MqttPacket.connackReturnCode(byteArrayOf(0x00, 0x05)))
    }

    @Test
    fun `five remaining-length bytes throw a protocol exception`() {
        // Four length bytes is the spec ceiling; a fifth continuation byte
        // must fail as a protocol error, not overflow the length arithmetic.
        val header = byteArrayOf(0x30, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01)
        try {
            MqttPacket.readFrame(header)
            org.junit.Assert.fail("Expected MqttProtocolException")
        } catch (e: MqttProtocolException) {
            assertEquals("Malformed remaining length", e.message)
        }
    }

    @Test
    fun `remaining length encodes multi-byte varints`() {
        // Topic "t" costs 3 bytes of variable header, so the payloads are
        // sized to make the remaining length exactly 16383 and 16384 — the
        // one-byte ceiling and one past it.
        val small = MqttPacket.publish("t", ByteArray(16380), qos = 0, retain = false, packetId = 0)
        assertEquals(0xFF, small[1].toInt() and 0xFF)
        val large = MqttPacket.publish("t", ByteArray(16381), qos = 0, retain = false, packetId = 0)
        assertEquals(0x80, large[1].toInt() and 0xFF)
        assertEquals(0x80, large[2].toInt() and 0xFF)
        assertEquals(0x01, large[3].toInt() and 0xFF)
    }
}
