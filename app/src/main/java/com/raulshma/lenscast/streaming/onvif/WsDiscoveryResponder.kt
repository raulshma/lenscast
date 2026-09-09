package com.raulshma.lenscast.streaming.onvif

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure WS-Discovery ProbeMatch builder: the probe's MessageId in, the reply
 * XML out. Deterministic — every value including this reply's own MessageID
 * is a parameter, so the discovery reply is JVM-tested without a socket.
 */
object ProbeReplyBuilder {

    fun reply(
        relatesToMessageId: String?,
        endpointAddress: String,
        types: String,
        scopes: String,
        xaddrs: String,
        replyMessageId: String = "urn:uuid:" + UUID.randomUUID(),
    ): String {
        val relatesTo = relatesToMessageId?.let { xmlEscape(it) } ?: ""
        return XML_DECLARATION +
            "<s:Envelope" +
            " xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"" +
            " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\"" +
            " xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"" +
            " xmlns:tdn=\"http://www.onvif.org/ver10/network/wsdl\"" +
            ">" +
            "<s:Header>" +
            "<a:MessageID>${xmlEscape(replyMessageId)}</a:MessageID>" +
            "<a:RelatesTo>$relatesTo</a:RelatesTo>" +
            "<a:To>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:To>" +
            "<a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</a:Action>" +
            "</s:Header>" +
            "<s:Body>" +
            "<d:ProbeMatches><d:ProbeMatch>" +
            "<a:EndpointReference><a:Address>${xmlEscape(endpointAddress)}</a:Address></a:EndpointReference>" +
            "<d:Types>${xmlEscape(types)}</d:Types>" +
            "<d:Scopes>${xmlEscape(scopes)}</d:Scopes>" +
            "<d:XAddrs>${xmlEscape(xaddrs)}</d:XAddrs>" +
            "<d:MetadataVersion>1</d:MetadataVersion>" +
            "</d:ProbeMatch></d:ProbeMatches>" +
            "</s:Body>" +
            "</s:Envelope>"
    }

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
}

/**
 * Loose WS-Discovery datagram classifier: is this a Probe, and what MessageId
 * does it carry? Clients prefix the discovery elements inconsistently
 * (`w5:Probe`, `d:Probe`, `wsd:Probe`, default namespace), so both the
 * element and the MessageId are matched prefix-tolerantly. Garbage → false/null.
 */
object WsDiscoveryProbeParser {

    /** Whether the datagram contains a Probe request element at all. */
    fun isProbe(datagram: String): Boolean = PROBE_REGEX.containsMatchIn(datagram)

    /** The probe's MessageId (`urn:uuid:…`), or null when absent/unparseable. */
    fun messageId(datagram: String): String? =
        MESSAGE_ID_REGEX.find(datagram)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    // Any-prefix or default-ns Probe element followed by whitespace or '>'.
    private val PROBE_REGEX = Regex("<(?:[A-Za-z_][\\w.-]*:)?Probe[\\s>]")

    // MessageId capitalisation varies too; the value is always a urn:uuid here.
    private val MESSAGE_ID_REGEX = Regex(
        "<(?:[A-Za-z_][\\w.-]*:)?MessageID>\\s*(urn:uuid:[^<\\s]+)",
        RegexOption.IGNORE_CASE,
    )
}

/**
 * The WS-Discovery UDP responder: listens on the 239.255.255.250:3702
 * multicast group and answers Probe datagrams with a [ProbeReplyBuilder]
 * ProbeMatch so LAN ONVIF clients find the device service.
 *
 * Threading/lifecycle style follows `ServiceDiscoveryManager`'s precedent
 * (daemon worker, idempotent start/stop, best-effort with logged failures):
 * one daemon thread loops on receive; `stop()` closes the socket, which
 * unblocks the loop. The socket binds the wildcard address (all interfaces);
 * bind/join/send failures are caught and logged — a hostile or absent
 * network degrades discovery only, never the app.
 *
 * Multicast lock: `ServiceDiscoveryManager` takes none (NsdManager manages
 * its own multicast internally), so there is no shared lock to reuse — this
 * responder owns the one app-level `WifiManager.MulticastLock`
 * (CHANGE_WIFI_MULTICAST_STATE is already in the manifest), skipped when no
 * context is supplied (JVM composition / tests).
 *
 * Rate limit: at most one reply per [MIN_REPLY_INTERVAL_MS] — discovery
 * storms (a switch flapping, HA restarting) must not spin the device.
 */
class WsDiscoveryResponder(
    private val context: Context?,
    private val endpointAddress: String,
    private val xaddrs: () -> String?,
    private val scopes: String,
    private val types: String = DEFAULT_TYPES,
) {

    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var worker: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var lastReplyAtMs = 0L

    /** Idempotent start: the second call is a no-op. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        acquireMulticastLock()
        val bound = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS-Discovery bind failed on port $DISCOVERY_PORT; discovery unavailable", e)
            running.set(false)
            return
        }
        socket = bound
        try {
            bound.joinGroup(InetAddress.getByName(MULTICAST_GROUP))
        } catch (e: Exception) {
            Log.w(TAG, "WS-Discovery group join failed; discovery unavailable", e)
            closeSocket(bound)
            running.set(false)
            return
        }
        worker = Thread({ loop(bound) }, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
        Log.d(TAG, "WS-Discovery responder started ($endpointAddress)")
    }

    /** Idempotent stop: the second call is a no-op. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.interrupt()
        worker = null
        socket?.let { closeSocket(it) }
        socket = null
        releaseMulticastLock()
        Log.d(TAG, "WS-Discovery responder stopped")
    }

    private fun loop(bound: MulticastSocket) {
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)
        while (running.get()) {
            val packet = try {
                val p = DatagramPacket(buffer, buffer.size)
                bound.receive(p)
                p
            } catch (e: Exception) {
                if (running.get()) Log.d(TAG, "WS-Discovery receive failed; continuing", e)
                continue
            }
            val datagram = try {
                String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            if (!WsDiscoveryProbeParser.isProbe(datagram)) continue
            if (!rateLimitAllows()) continue
            val target = xaddrs() ?: continue
            val reply = ProbeReplyBuilder.reply(
                relatesToMessageId = WsDiscoveryProbeParser.messageId(datagram),
                endpointAddress = endpointAddress,
                types = types,
                scopes = scopes,
                xaddrs = target,
            )
            try {
                val bytes = reply.toByteArray(Charsets.UTF_8)
                bound.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                Log.d(TAG, "WS-Discovery ProbeMatch sent to ${packet.address.hostAddress}")
            } catch (e: Exception) {
                Log.d(TAG, "WS-Discovery reply failed; continuing", e)
            }
        }
    }

    /** At most one reply per [MIN_REPLY_INTERVAL_MS], regardless of storm size. */
    private fun rateLimitAllows(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastReplyAtMs < MIN_REPLY_INTERVAL_MS) return false
        lastReplyAtMs = now
        return true
    }

    private fun acquireMulticastLock() {
        if (context == null || multicastLock != null) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock(LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Multicast lock unavailable; discovery may not receive probes", e)
            multicastLock = null
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Multicast lock release failed", e)
        }
        multicastLock = null
    }

    private fun closeSocket(s: MulticastSocket) {
        try {
            s.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "WsDiscovery"
        private const val MULTICAST_GROUP = "239.255.255.250"
        private const val DISCOVERY_PORT = 3702
        private const val THREAD_NAME = "ws-discovery"
        private const val LOCK_TAG = "LensCastWsDiscovery"
        private const val MAX_DATAGRAM_BYTES = 4096

        /** Reply ceiling for the rate limiter: a few replies per second, max. */
        private const val MIN_REPLY_INTERVAL_MS = 250L

        /** WS-Discovery Types value — one ONVIF network device. */
        const val DEFAULT_TYPES = "tdn:Device"
    }
}
