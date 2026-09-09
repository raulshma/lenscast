package com.raulshma.lenscast.core.mqtt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * The reader's framing regression test, over a real loopback socket: a fake
 * broker that answers CONNACK and then deliberately splits the PUBACK across
 * two TCP writes with a pause between them. A reader that discards trailing
 * partial frames would time out waiting for an ack that already arrived;
 * this test pins the cross-read accumulation that keeps the publish honest.
 */
class MqttClientLoopbackTest {

    @Test
    fun `publish completes when the puback spans two tcp segments`() {
        ServerSocket(0).use { server ->
            val clientDone = Thread {
                val client = MqttClient(
                    keepAliveSeconds = 5,
                    connectTimeoutMs = 2_000,
                    ackTimeoutMs = 5_000,
                )
                client.connect(
                    MqttClient.Endpoint(
                        host = "127.0.0.1",
                        port = server.localPort,
                        tls = false,
                        username = null,
                        password = null,
                        clientId = "test",
                        willTopic = null,
                        willMessage = null,
                    ),
                )
                client.publish("t", "x".toByteArray(), qos = 1)
                client.close()
            }
            clientDone.isDaemon = true
            clientDone.start()

            server.accept().use { broker ->
                val input = broker.getInputStream()
                drain(input) // CONNECT

                // CONNACK, accepted.
                broker.getOutputStream().write(byteArrayOf(0x20, 0x02, 0x00, 0x00))
                broker.getOutputStream().flush()

                drain(input) // PUBLISH

                val out: OutputStream = broker.getOutputStream()
                // The PUBACK for packet id 1, torn in half.
                out.write(byteArrayOf(0x40))
                out.flush()
                Thread.sleep(150)
                out.write(byteArrayOf(0x02, 0x00, 0x01))
                out.flush()
            }

            clientDone.join(10_000)
            assertTrue("Client thread died before the acked publish finished", !clientDone.isAlive)
        }
    }

    /** Reads the 4-byte CONNECT/PUBLISH headers plus their remaining lengths. */
    private fun drain(input: java.io.InputStream): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(1)
        // Fixed header byte + remaining-length byte are enough for these
        // tiny frames; read until the declared frame has fully arrived.
        var read = input.read(chunk)
        if (read < 0) error("Stream closed")
        buffer.write(chunk)
        read = input.read(chunk)
        if (read < 0) error("Stream closed")
        buffer.write(chunk)
        val remaining = chunk[0].toInt()
        repeat(remaining) {
            val r = input.read(chunk)
            if (r < 0) error("Stream closed")
            buffer.write(chunk)
        }
        return buffer.toByteArray()
    }
}
