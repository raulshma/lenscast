package com.raulshma.lenscast.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Web Push subscription store under JVM tests (file-seamed like
 * AuditLog): persistence across instances, endpoint dedupe (re-subscribe
 * updates in place), removal, and the admission verdicts that keep malformed
 * key material out of the store — a bad key would only ever surface as a
 * failed dispatch.
 */
class PushSubscriptionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A shape-valid 65-byte point and 16-byte auth secret. */
    private val p256dh = PushEncryption.base64Url(byteArrayOf(0x04) + ByteArray(64) { 1 })
    private val auth = PushEncryption.base64Url(ByteArray(16) { 2 })

    private fun store(): PushSubscriptionStore = PushSubscriptionStore(File(tmp.root, "push_subscriptions.json"))

    private fun endpoint(name: String) = "https://push.example.com/send/$name"

    // ── admission ──

    @Test
    fun `add accepts a valid https subscription`() {
        val subscriptions = store()
        assertTrue(subscriptions.add(endpoint("a"), p256dh, auth, labels = emptyList(), createdAtMs = 1))
        assertEquals(1, subscriptions.count())
        assertEquals(endpoint("a"), subscriptions.all().single().endpoint)
        assertEquals(p256dh, subscriptions.all().single().p256dh)
    }

    @Test
    fun `add rejects non-https endpoints and malformed key material`() {
        val subscriptions = store()
        assertFalse(subscriptions.add("http://push.example.com/send/a", p256dh, auth, emptyList(), 1))
        assertFalse(subscriptions.add(endpoint("a"), PushEncryption.base64Url(ByteArray(32)), auth, emptyList(), 1))
        assertFalse(subscriptions.add(endpoint("a"), p256dh, PushEncryption.base64Url(ByteArray(8)), emptyList(), 1))
        assertFalse(subscriptions.add("   ", p256dh, auth, emptyList(), 1))
        assertEquals(0, subscriptions.count())
    }

    @Test
    fun `the pure policy mirrors the store's admission verdict`() {
        assertTrue(PushSubscriptionPolicy.isSubscribable(endpoint("a"), p256dh, auth))
        assertFalse(PushSubscriptionPolicy.isSubscribable("ftp://push.example.com", p256dh, auth))
        assertFalse(PushSubscriptionPolicy.isSubscribable(endpoint("a"), "%%%", auth))
    }

    // ── dedupe and removal ──

    @Test
    fun `re-subscribing the same endpoint updates in place instead of duplicating`() {
        val subscriptions = store()
        subscriptions.add(endpoint("a"), p256dh, auth, emptyList(), createdAtMs = 1)
        val rotated = PushEncryption.base64Url(byteArrayOf(0x04) + ByteArray(64) { 9 })

        assertTrue(subscriptions.add(endpoint("a"), rotated, auth, emptyList(), createdAtMs = 2))

        assertEquals(1, subscriptions.count())
        assertEquals(rotated, subscriptions.all().single().p256dh)
        assertEquals(2, subscriptions.all().single().createdAtMs)
    }

    @Test
    fun `remove drops the endpoint and reports absence honestly`() {
        val subscriptions = store()
        subscriptions.add(endpoint("a"), p256dh, auth, emptyList(), 1)
        subscriptions.add(endpoint("b"), p256dh, auth, emptyList(), 1)

        assertTrue(subscriptions.remove(endpoint("a")))
        assertFalse(subscriptions.remove(endpoint("a")))
        assertEquals(listOf(endpoint("b")), subscriptions.all().map { it.endpoint })
    }

    @Test
    fun `remove tolerates surrounding whitespace like add normalizes it`() {
        val subscriptions = store()
        subscriptions.add(endpoint("a"), p256dh, auth, emptyList(), 1)
        assertTrue(subscriptions.remove("  ${endpoint("a")} "))
    }

    // ── persistence ──

    @Test
    fun `the store survives a new instance over the same file`() {
        val file = File(tmp.root, "push_subscriptions.json")
        val first = PushSubscriptionStore(file)
        first.add(endpoint("a"), p256dh, auth, labels = listOf("dashboard"), createdAtMs = 42)

        val second = PushSubscriptionStore(file)
        val loaded = second.all().single()
        assertEquals(endpoint("a"), loaded.endpoint)
        assertEquals(p256dh, loaded.p256dh)
        assertEquals(auth, loaded.auth)
        assertEquals(42, loaded.createdAtMs)
        assertEquals(listOf("dashboard"), loaded.labels)
    }

    @Test
    fun `a corrupt store file starts clean instead of crashing`() {
        val file = File(tmp.root, "push_subscriptions.json")
        file.writeText("{\"endpoint\": half-written")

        val subscriptions = PushSubscriptionStore(file)

        assertEquals(0, subscriptions.count())
        // And a later add persists normally over the corrupt copy.
        assertTrue(subscriptions.add(endpoint("a"), p256dh, auth, emptyList(), 1))
        assertEquals(1, PushSubscriptionStore(file).count())
    }

    @Test
    fun `a blank endpoint row never survives the load filter`() {
        val file = File(tmp.root, "push_subscriptions.json")
        val good = endpoint("a")
        file.writeText(
            """[{"endpoint":"","p256dh":"x","auth":"y","createdAtMs":0},""" +
                """{"endpoint":"$good","p256dh":"$p256dh","auth":"$auth","createdAtMs":1}]""",
        )
        val subscriptions = PushSubscriptionStore(file)
        assertEquals(listOf(good), subscriptions.all().map { it.endpoint })
    }
}
