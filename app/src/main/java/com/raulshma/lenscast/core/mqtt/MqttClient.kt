package com.raulshma.lenscast.core.mqtt

import android.util.Log
import com.raulshma.lenscast.core.StreamDefaults
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal blocking MQTT 3.1.1 client for the alert publisher: one
 * persistent connection at a time, QoS 0/1 publishes with PUBACK waits, a
 * keepalive pinger, and a last-will message so a dead connection flips the
 * availability entity offline on the broker side. No subscribe support —
 * LensCast only publishes.
 *
 * Lock discipline: [connect]/[publish]/[close] serialize on one lock and do
 * their writes under it; the reader thread is the only consumer of the socket
 * input after [connect] returns and reads WITHOUT the lock, so a quiet broker
 * never blocks a publish. The QoS 1 PUBACK wait in [publish] also happens
 * outside the lock — a broker that swallows PUBACKs must not stall [close]
 * or the keepalive pinger for the full ack timeout. Inbound bytes accumulate in the reader's own buffer
 * and frames are peeled off only when complete — a frame split across TCP
 * segments is never discarded. Socket read timeouts are normal idle (the
 * pinger keeps the session alive); any other transport or protocol error
 * marks the connection dead and the next [publish] reconnects. The TLS
 * socket factory is resolved through the injected provider so tests can wire
 * a plain factory.
 */
class MqttClient(
    private val keepAliveSeconds: Int = StreamDefaults.MQTT_KEEPALIVE_SECONDS,
    private val connectTimeoutMs: Int = StreamDefaults.MQTT_CONNECT_TIMEOUT_MS,
    private val ackTimeoutMs: Long = StreamDefaults.MQTT_PUBLISH_ACK_TIMEOUT_MS.toLong(),
    private val tlsSocketFactory: SocketFactory =
        SSLSocketFactory.getDefault() as SocketFactory,
    private val hostnameVerifier: HostnameVerifier =
        HttpsURLConnection.getDefaultHostnameVerifier(),
) {
    /** Connection parameters; a change closes any live connection on next connect. */
    data class Endpoint(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val username: String?,
        val password: String?,
        val clientId: String,
        val willTopic: String?,
        val willMessage: ByteArray?,
        val willRetain: Boolean = false,
    ) {
        // The generated toString would print the broker password (and the
        // will bytes) into any log line that carries the endpoint.
        override fun toString(): String =
            "Endpoint(host=$host, port=$port, tls=$tls, username=$username, " +
                "password=****, clientId=$clientId, willTopic=$willTopic, " +
                "willMessage=****, willRetain=$willRetain)"
    }

    private val lock = Object()
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var readerStream: InputStream? = null
    private var endpoint: Endpoint? = null
    private var connected = false
    private val nextPacketId = AtomicInteger(0)
    private val pendingAcks = ConcurrentHashMap<Int, PendingAck>()

    /**
     * A QoS 1 publish's PUBACK wait. [closeLocked] counts the latch down too
     * — but marks it failed first, so a connection that dies mid-wait surfaces
     * as an error instead of a delivered-at-least-once lie.
     */
    private class PendingAck {
        val latch = CountDownLatch(1)

        @Volatile
        var failed = false
    }

    private val pinger = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MqttPinger").apply { isDaemon = true }
    }

    init {
        val period = keepAliveSeconds.toLong().coerceAtLeast(1)
        pinger.scheduleAtFixedRate(::sendKeepalivePing, period, period, TimeUnit.SECONDS)
    }

    val isConnected: Boolean
        get() = synchronized(lock) { connected }

    /**
     * Connects (closing any stale connection first), waits for CONNACK, and
     * starts the reader loop. Throws [IOException] on transport failure or
     * CONNACK refusal — the caller decides whether to retry.
     */
    fun connect(target: Endpoint) {
        synchronized(lock) {
            closeLocked()
            val factory = if (target.tls) tlsSocketFactory else javax.net.SocketFactory.getDefault()
            val newSocket = factory.createSocket()
            newSocket.tcpNoDelay = true
            newSocket.soTimeout = keepAliveSeconds * 2_000
            newSocket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMs)
            val newInput = BufferedInputStream(newSocket.getInputStream())
            val newOutput = newSocket.getOutputStream()
            socket = newSocket
            output = newOutput
            endpoint = target
            try {
                // The TLS handshake runs here, before any MQTT bytes, so a
                // MITM cert never receives the CONNECT packet — credentials
                // included. Verifying the hostname (not just the chain, which
                // the factory already did) closes the any-CA-signed-cert-for-
                // any-host gap.
                if (target.tls && newSocket is SSLSocket) {
                    newSocket.startHandshake()
                    if (!hostnameVerifier.verify(target.host, newSocket.session)) {
                        throw IOException("TLS hostname verification failed for ${target.host}")
                    }
                }
                newOutput.write(
                    MqttPacket.connect(
                        clientId = target.clientId,
                        keepAliveSeconds = keepAliveSeconds,
                        username = target.username,
                        password = target.password,
                        willTopic = target.willTopic,
                        willMessage = target.willMessage,
                        willRetain = target.willRetain,
                    ),
                )
                newOutput.flush()
                val connack = readFrameBlocking(newInput)
                    ?: throw MqttProtocolException("No CONNACK from broker")
                if (connack.type != MqttPacket.TYPE_CONNACK) {
                    throw MqttProtocolException("Expected CONNACK, got type ${connack.type}")
                }
                val returnCode = MqttPacket.connackReturnCode(connack.body)
                if (returnCode != MqttPacket.CONNACK_ACCEPTED) {
                    throw IOException("Broker refused connection: code $returnCode")
                }
                connected = true
            } catch (e: Throwable) {
                // A refused CONNACK or dead transport must not leave the open
                // socket parked in the fields until the next connect reaps it.
                closeLocked()
                throw e
            }
            startReader(newInput)
            Log.d(TAG, "Connected to ${target.host}:${target.port}")
        }
    }

    /**
     * Publishes one message, reconnecting first when the connection is down.
     * QoS 1 blocks for the PUBACK; a timeout or transport error closes the
     * connection and rethrows — one reconnect attempt per call is the
     * publisher's retry budget.
     */
    fun publish(topic: String, payload: ByteArray, qos: Int = 1, retain: Boolean = false) {
        // The ack is held locally so a fast PUBACK (removed from the map by
        // the reader thread) cannot null out this thread's wait.
        val ack: PendingAck?
        val packetId: Int
        synchronized(lock) {
            val target = endpoint
                ?: throw IOException("Not connected: connect() never ran")
            if (!connected) connect(target)
            ack = if (qos > 0) PendingAck() else null
            packetId = if (qos > 0) {
                val value = nextPacketId.updateAndGet { current -> if (current >= 0xFFFF) 1 else current + 1 }
                pendingAcks[value] = ack!!
                value
            } else {
                0
            }
            try {
                output?.write(MqttPacket.publish(topic, payload, qos, retain, packetId))
                output?.flush()
            } catch (e: IOException) {
                pendingAcks.remove(packetId)
                failConnectionLocked(e)
                throw e
            }
        }
        // Awaited WITHOUT the lock: the wait can stretch to the full ack
        // timeout on a dead broker, and close()/the pinger must stay live
        // through it. A concurrent closeLocked marks the ack failed and
        // counts the latch down, so this wait always terminates.
        if (ack != null) {
            val acked = ack.latch.await(ackTimeoutMs, TimeUnit.MILLISECONDS)
            pendingAcks.remove(packetId)
            if (ack.failed) {
                throw IOException("Connection closed before PUBACK for packet $packetId")
            }
            if (!acked) {
                val cause = IOException("PUBACK timeout for packet $packetId")
                synchronized(lock) { failConnectionLocked(cause) }
                throw cause
            }
        }
    }

    /**
     * Publishes DISCONNECT (graceful: no will fires) and closes the socket.
     * Deliberately NOT terminal: the pinger executor keeps running (its task
     * no-ops while disconnected) because this client is a reconnecting
     * singleton — a config change closes only to reconnect on the next
     * publish, and the process exit reaps the daemon threads.
     */
    fun close() {
        synchronized(lock) {
            if (connected) {
                runCatching {
                    output?.write(MqttPacket.disconnect())
                    output?.flush()
                }
            }
            closeLocked()
        }
    }

    /** CONNACK is read inline on the connecting thread, before the reader starts. */
    private fun readFrameBlocking(stream: InputStream): MqttPacket.MqttFrame {
        val accumulator = ByteArray(INITIAL_BUFFER_BYTES)
        var filled = 0
        while (true) {
            val parsed = MqttPacket.readFrame(accumulator.copyOf(filled))
            if (parsed != null) return parsed.first
            if (filled >= accumulator.size) throw MqttProtocolException("Oversized control frame")
            val read = stream.read(accumulator, filled, accumulator.size - filled)
            if (read < 0) throw IOException("Connection closed before CONNACK")
            filled += read
        }
    }

    /**
     * The post-handshake reader: accumulates inbound bytes and peels whole
     * frames off the front, carrying partial frames across reads — CONNACKs,
     * PUBACKs, and PINGRESPs are 2-4 bytes and TCP makes no framing promises.
     */
    private fun startReader(stream: InputStream) {
        val reader = Thread({
            var accumulator = ByteArray(INITIAL_BUFFER_BYTES)
            var filled = 0
            try {
                while (true) {
                    if (filled == accumulator.size) {
                        if (accumulator.size >= MAX_BUFFER_BYTES) {
                            synchronized(lock) {
                                failConnectionLocked(IOException("Inbound frame exceeds buffer cap"))
                            }
                            return@Thread
                        }
                        accumulator = accumulator.copyOf(accumulator.size * 2)
                    }
                    val read = try {
                        stream.read(accumulator, filled, accumulator.size - filled)
                    } catch (_: SocketTimeoutException) {
                        continue // idle; the pinger keeps the session alive
                    } catch (_: IOException) {
                        return@Thread
                    }
                    if (read < 0) return@Thread
                    filled += read
                    var consumed = 0
                    while (consumed < filled) {
                        val parsed = try {
                            MqttPacket.readFrame(accumulator.copyOfRange(consumed, filled))
                        } catch (e: MqttProtocolException) {
                            synchronized(lock) { failConnectionLocked(IOException(e.message)) }
                            return@Thread
                        } ?: break // trailing partial frame: wait for more bytes
                        consumed += parsed.second
                        handleFrame(parsed.first)
                    }
                    System.arraycopy(accumulator, consumed, accumulator, 0, filled - consumed)
                    filled -= consumed
                }
            } finally {
                synchronized(lock) {
                    // A superseded reader (older socket) must not kill the live
                    // connection; only the current stream's death does.
                    if (readerStream === stream) {
                        failConnectionLocked(IOException("Reader exited"))
                    }
                }
            }
        }, "MqttReader")
        reader.isDaemon = true
        // Registered before the thread starts: an instantly-dying reader must
        // find its own stream here, or its death would go unreported and the
        // connection would look alive on a dead socket.
        synchronized(lock) { readerStream = stream }
        reader.start()
    }

    private fun handleFrame(frame: MqttPacket.MqttFrame) {
        when (frame.type) {
            MqttPacket.TYPE_PUBACK ->
                pendingAcks.remove(MqttPacket.pubackPacketId(frame.body))?.latch?.countDown()
            MqttPacket.TYPE_CONNACK, MqttPacket.TYPE_PINGRESP -> Unit
        }
    }

    private fun sendKeepalivePing() {
        synchronized(lock) {
            if (!connected) return
            try {
                output?.write(MqttPacket.pingReq())
                output?.flush()
            } catch (e: IOException) {
                failConnectionLocked(e)
            }
        }
    }

    private fun failConnectionLocked(cause: IOException) {
        Log.d(TAG, "Connection failed: ${cause.message}")
        closeLocked()
    }

    private fun closeLocked() {
        connected = false
        output = null
        readerStream = null
        socket?.let { runCatching { it.close() } }
        socket = null
        pendingAcks.values.forEach {
            it.failed = true
            it.latch.countDown()
        }
        pendingAcks.clear()
    }

    companion object {
        private const val TAG = "MqttClient"

        /** CONNACK (4 B), PUBACK (4 B), PINGRESP (2 B) — the accumulator's start size. */
        private const val INITIAL_BUFFER_BYTES = 64

        /**
         * Inbound frames this client accepts are a few bytes; a broker sending
         * more without a complete frame is broken or hostile, and the cap
         * bounds the accumulator's growth instead of trusting the peer.
         */
        private const val MAX_BUFFER_BYTES = 1 shl 20
    }
}
