package com.raulshma.lenscast.core.push

import com.raulshma.lenscast.core.Base64Codec
import com.raulshma.lenscast.core.DetectionAlert
import com.raulshma.lenscast.core.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Web Push sink under JVM tests, the webhook-notifier way: the HTTP seam
 * is faked, so the tests pin the wire contract (headers incl. the VAPID
 * shape, TTL, Urgency, Content-Encoding), the RFC 8291-encrypted body
 * (decrypted back with the receiver's own key material), the 404/410 prune,
 * and the fail-open contract — one dead browser never costs the others, and
 * no failure ever escapes the dispatch.
 */
class WebPushSenderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingTransport : WebPushSender.Transport {
        class Call(val endpoint: String, val headers: Map<String, String>, val body: ByteArray)

        val calls = mutableListOf<Call>()
        // Set BEFORE the dispatch is queued: the worker may start posting the
        // instant notifyEvent returns, so the latch can never be installed
        // after the fact without racing the first POST.
        @Volatile var latch = CountDownLatch(0)
        var respond: (String) -> Int = { 201 }

        override fun post(endpoint: String, headers: Map<String, String>, body: ByteArray): Int =
            synchronized(calls) {
                calls.add(Call(endpoint, headers, body))
                latch.countDown()
                respond(endpoint)
            }

        fun prepare(count: Int) {
            latch = CountDownLatch(count)
        }

        fun await(): Boolean = latch.await(10, TimeUnit.SECONDS)
    }

    // Initialized in @Before — TemporaryFolder's root only exists after the
    // rule runs, never at field-initializer time.
    private lateinit var vapidKeys: VapidKeys

    @Before
    fun setUp() {
        vapidKeys = VapidKeys.loadOrCreate(File(tmp.root, "vapid.key"))
    }

    /** A shape-valid receiver: the store's admission accepts exactly these. */
    private fun subscribeReceiver(store: PushSubscriptionStore, name: String): Triple<KeyPair, ByteArray, ByteArray> {
        val pair = p256()
        val point = VapidKeys.uncompressedPoint(pair.public as ECPublicKey)
        val authSecret = ByteArray(16) { (it + 3).toByte() }
        assertTrue(
            store.add(
                "https://push.example.com/send/$name",
                PushEncryption.base64Url(point),
                PushEncryption.base64Url(authSecret),
                labels = emptyList(),
                createdAtMs = 1,
            ),
        )
        return Triple(pair, point, authSecret)
    }

    private fun p256(): java.security.KeyPair {
        val generator = java.security.KeyPairGenerator.getInstance("EC")
        generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"), java.security.SecureRandom())
        return generator.generateKeyPair()
    }

    private fun alert(kind: EventKind = EventKind.MOTION) = DetectionAlert(
        kind = kind,
        value = 42.0,
        zones = listOf("Doorway"),
        labels = listOf("person"),
        batteryPercent = 85,
        snapshotJpegBase64 = null,
        timestampMs = 1_700_000_000_000L,
    )

    private fun config(enabled: Boolean = true) = WebPushSender.Config(
        enabled = enabled,
        subject = "mailto:operator@example.com",
    )

    // ── the go/no-go verdict ──

    @Test
    fun `willDispatch requires the toggle and at least one subscription`() {
        assertTrue(WebPushSender.willDispatch(config(), subscriptionCount = 1))
        assertFalse(WebPushSender.willDispatch(config(enabled = false), subscriptionCount = 1))
        assertFalse(WebPushSender.willDispatch(config(), subscriptionCount = 0))
        assertFalse(WebPushSender.willDispatch(null, subscriptionCount = 3))
    }

    @Test
    fun `notifyEvent returns false when nothing is subscribed`() {
        val transport = RecordingTransport()
        val sender = WebPushSender(::config, PushSubscriptionStore(File(tmp.root, "a.json")), vapidKeys, transport)

        assertFalse(sender.notifyEvent(alert(), eventId = "e1", clipAvailable = false))
        assertEquals(0, transport.calls.size)
    }

    // ── the wire contract ──

    @Test
    fun `each subscription gets one POST with the push headers and VAPID`() {
        val transport = RecordingTransport()
        val store = PushSubscriptionStore(File(tmp.root, "b.json"))
        subscribeReceiver(store, "one")
        subscribeReceiver(store, "two")
        val sender = WebPushSender(::config, store, vapidKeys, transport)
        transport.prepare(2)

        assertTrue(sender.notifyEvent(alert(), eventId = "e1", clipAvailable = true))
        assertTrue(transport.await())
        assertEquals(2, transport.calls.size)

        for (call in transport.calls) {
            assertTrue(call.endpoint.startsWith("https://push.example.com/send/"))
            assertEquals("aes128gcm", call.headers["Content-Encoding"])
            assertEquals("3600", call.headers["TTL"])
            assertEquals("high", call.headers["Urgency"])
            val authorization = call.headers.getValue("Authorization")
            assertTrue(authorization.startsWith("vapid t="))
            assertTrue(authorization.contains(", k="))
            // aud binds the JWT to the endpoint's origin.
            val claims = String(
                Base64Codec.decodeOrNull(authorization.removePrefix("vapid t=").substringBefore(",").split('.')[1])!!,
                Charsets.UTF_8,
            )
            assertTrue(claims.contains("\"aud\":\"https://push.example.com\""))
            assertTrue(claims.contains("\"sub\":\"mailto:operator@example.com\""))
            // The body is one aes128gcm record: header (86 bytes: salt 16 +
            // rs 4 + idlen 1 + keyid 65) plus ciphertext (json + del + tag).
            assertTrue(call.body.size > 86 + 16)
            assertEquals(65, call.body[20].toInt() and 0xFF)
        }
    }

    @Test
    fun `the encrypted body decrypts to the event payload`() {
        val transport = RecordingTransport()
        val store = PushSubscriptionStore(File(tmp.root, "c.json"))
        val (receiverPair, point, authSecret) = subscribeReceiver(store, "one")
        val sender = WebPushSender(::config, store, vapidKeys, transport)
        transport.prepare(1)

        sender.notifyEvent(alert(kind = EventKind.SOUND), eventId = "evt-9", clipAvailable = false)
        assertTrue(transport.await())

        val body = transport.calls.single().body
        val json = String(
            PushEncryption.decrypt(body, point, receiverPair.private, authSecret),
            Charsets.UTF_8,
        )
        assertTrue(json.contains("\"title\":\"LensCast Sound alert\""))
        assertTrue(json.contains("\"tag\":\"lenscast-sound\""))
        assertTrue(json.contains("\"eventId\":\"evt-9\""))
        assertTrue(json.contains("\"type\":\"sound\""))
        assertTrue(json.contains("\"clipAvailable\":false"))
        assertTrue(json.contains("Doorway"))
        assertTrue(json.contains("person"))
        assertTrue(json.contains("battery 85%"))
    }

    // ── pruning and fail-open ──

    @Test
    fun `a 410 or 404 answer prunes the subscription`() {
        for (gone in listOf(410, 404)) {
            val transport = RecordingTransport()
            transport.respond = { gone }
            val store = PushSubscriptionStore(File(tmp.newFolder("prune-$gone"), "prune.json"))
            subscribeReceiver(store, "one")
            val sender = WebPushSender(::config, store, vapidKeys, transport)
            transport.prepare(1)

            assertTrue(sender.notifyEvent(alert(), eventId = "e1", clipAvailable = false))
            assertTrue(transport.await())
            // The prune runs in the worker right after the POST returns — the
            // latch releases first, so poll instead of racing the removal.
            val deadline = System.currentTimeMillis() + 5_000
            while (store.count() != 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(0, store.count())
        }
    }

    @Test
    fun `a server error keeps the subscription for the next event`() {
        val transport = RecordingTransport()
        transport.respond = { 500 }
        val store = PushSubscriptionStore(File(tmp.root, "keep.json"))
        subscribeReceiver(store, "one")
        val sender = WebPushSender(::config, store, vapidKeys, transport)
        transport.prepare(1)

        assertTrue(sender.notifyEvent(alert(), eventId = "e1", clipAvailable = false))
        assertTrue(transport.await())
        assertEquals(1, store.count())
    }

    @Test
    fun `fail-open — one failing subscription never costs the others`() {
        val transport = RecordingTransport()
        transport.respond = { endpoint -> if (endpoint.endsWith("bad")) throw IllegalStateException("socket gone") else 201 }
        val store = PushSubscriptionStore(File(tmp.root, "mix.json"))
        subscribeReceiver(store, "bad")
        subscribeReceiver(store, "good")
        val sender = WebPushSender(::config, store, vapidKeys, transport)
        transport.prepare(2)

        assertTrue(sender.notifyEvent(alert(), eventId = "e1", clipAvailable = false))
        assertTrue(transport.await())
        assertEquals(2, transport.calls.size)
        // And the store is untouched — a thrown transport is a transport
        // failure, not a subscription expiry.
        assertEquals(2, store.count())
    }

    // ── payload mapping ──

    @Test
    fun `buildPayload carries the notification fields`() {
        val payload = WebPushSender.buildPayload(alert(EventKind.TAMPER), eventId = "evt-1", clipAvailable = true)

        assertEquals("LensCast Tamper alert", payload.title)
        assertEquals("lenscast-tamper", payload.tag)
        assertEquals("tamper", payload.type)
        assertEquals("evt-1", payload.eventId)
        assertTrue(payload.clipAvailable)
        assertTrue(payload.body.contains("Doorway"))
        assertTrue(payload.body.contains("person"))
        assertTrue(payload.body.contains("battery 85%"))
    }

    @Test
    fun `buildPayload degrades honestly when the alert carries nothing`() {
        val payload = WebPushSender.buildPayload(
            DetectionAlert(kind = EventKind.TEST, value = 0.0, timestampMs = 5L),
            eventId = null,
            clipAvailable = false,
        )

        assertEquals("LensCast Test alert", payload.title)
        assertEquals("lenscast-test", payload.tag)
        assertEquals(null, payload.eventId)
        assertEquals("Test event detected", payload.body)
        assertFalse(payload.clipAvailable)
    }
}
