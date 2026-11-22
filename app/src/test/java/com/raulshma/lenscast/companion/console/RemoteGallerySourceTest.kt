package com.raulshma.lenscast.companion.console

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.model.CompanionAuthKind
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.model.StoredCredentials
import com.raulshma.lenscast.companion.roster.CompanionCredentialCipher
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.core.AppJson
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The remote gallery backend's rules, one MockWebServer round trip per
 * verdict — real threads (`runBlocking`, the DeviceSessionManagerTest
 * convention; no virtual clock, and the fetch coroutines must actually run):
 *
 * - paging: page 0 lands on construction, `loadMore` appends page 1 with the
 *   0-based wire index, a replayed page dedupes by id, and `hasMore` rides
 *   the server's verdict, going inert once false;
 * - the type filter restarts paging at page 0 with the wire `type` param;
 * - delete: one id rides DELETE /api/media/{id}, many ride
 *   POST /api/media/batch-delete, and the items drop only after the 2xx —
 *   a forbidden delete leaves the list untouched;
 * - the favorite wire verdict: `canFavorite` is pinned false (the wire
 *   carries the flag read-only) and a toggle fires no request at all.
 *
 * Plain JUnit — the source touches no Android API (its render slots are
 * never invoked here); Robolectric would only slow the suite down.
 */
class RemoteGallerySourceTest {

    /** The per-page await ceiling: a MockWebServer round trip is milliseconds; the ceiling only bounds the guard-race re-issues. */
    private val pageAwaitTimeoutMs = 5_000L

    /** The re-issue cadence through the in-flight-guard race window. */
    private val pageGuardRetryMs = 50L

    private lateinit var server: MockWebServer
    private lateinit var filesDir: File
    private lateinit var roster: DeviceRosterStore
    private lateinit var credentials: CompanionCredentialStore
    private lateinit var device: CompanionDevice

    private val requests = CopyOnWriteArrayList<String>()
    private val parentScopes = mutableListOf<CoroutineScope>()

    /** The delete routes' answer code; a test flips it to 403 for the denial verdict. */
    private val deleteResponseCode = AtomicInteger(200)

    /**
     * The ids the fake device has confirmed deleted, folded into every
     * served gallery page: the real device's list no longer carries them,
     * so the source's post-delete page-0 reload reads a deletion-shaped
     * answer instead of resurrecting the removed tiles.
     */
    private val deletedIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add("${request.method} ${request.path.orEmpty()}")
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    path == "/api/gallery" -> ok(
                        galleryPageJson(
                            page = request.requestUrl?.queryParameter("page"),
                            type = request.requestUrl?.queryParameter("type"),
                        )
                    )
                    request.method == "DELETE" && path.startsWith("/api/media/") -> {
                        if (deleteResponseCode.get() == 200) {
                            deletedIds.add(path.removePrefix("/api/media/"))
                            ok("""{"success":true}""")
                        } else {
                            statusJson(deleteResponseCode.get())
                        }
                    }
                    request.method == "POST" && path == "/api/media/batch-delete" -> {
                        if (deleteResponseCode.get() == 200) {
                            // The device's batch is PARTIAL-capable: the
                            // response names what actually went (the
                            // confirmed ids), and the page server stops
                            // serving them.
                            val body = request.body?.readUtf8().orEmpty()
                            val idsArray = Regex("\"ids\"\\s*:\\s*\\[([^\\]]*)\\]")
                                .find(body)?.groupValues?.get(1).orEmpty()
                            val requested = Regex("\"([^\"]+)\"")
                                .findAll(idsArray)
                                .map { it.groupValues[1] }
                                .toList()
                            deletedIds.addAll(requested)
                            ok(
                                """{"success":true,"deleted":${
                                    requested.joinToString(", ", "[", "]") { "\"$it\"" }
                                }}"""
                            )
                        } else {
                            statusJson(deleteResponseCode.get())
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        filesDir = createTempDir("gallery-src")
        roster = DeviceRosterStore(filesDir, AppJson)
        credentials = CompanionCredentialStore(filesDir, plaintextCipher())
        device = CompanionDevice(
            id = "dev-gallery",
            name = "Gallery",
            host = "127.0.0.1",
            port = server.port,
            auth = CompanionAuthKind.TOKEN,
            role = "admin",
            addedAtMs = 0L,
        ).also { roster.upsert(it) }
        credentials.put(device.id, StoredCredentials(CompanionAuthKind.TOKEN, token = "t"))
    }

    @After
    fun tearDown() {
        parentScopes.forEach { it.cancel() }
        requests.clear()
        deletedIds.clear()
        runCatching { server.shutdown() }
        filesDir.deleteRecursively()
    }

    private fun plaintextCipher() = object : CompanionCredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    private fun ok(json: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json)

    private fun statusJson(code: Int): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
            .setBody("""{"success":${code == 200}}""")

    private fun newScope(parent: Job): CoroutineScope =
        // IO lanes but parented to the caller's job, so a leak would hang the
        // runBlocking and fail the test (the console-test note, verbatim).
        CoroutineScope(SupervisorJob(parent) + Dispatchers.IO).also { parentScopes.add(it) }

    private fun source(parent: Job, api: com.raulshma.lenscast.companion.client.LenscastApiClient? =
        LenscastApiClient(device) { credentials.get(device.id) }) =
        RemoteGallerySource(api = api, canDelete = true, scope = newScope(parent))

    /**
     * One load-more request + await of [size] loaded items, re-issued on a
     * short cadence until the page lands. The source's re-entrancy guard can
     * drop the request when the previous page's publish beat that fetch job's
     * completion — a dropped call produces no further emission, so a single
     * loadMore + await is timing-dependent under load (the app's page walkers
     * re-fire on a cadence for the same documented race). Bounded whole-wait
     * by [pageAwaitTimeoutMs].
     */
    private suspend fun RemoteGallerySource.loadMoreAwaiting(size: Int): List<CaptureHistory> =
        withTimeout(pageAwaitTimeoutMs) {
            while (items.value.size < size) {
                loadMore()
                delay(pageGuardRetryMs)
            }
            items.first { it.size == size }
        }

    private fun itemJson(id: String, type: String = "PHOTO") =
        """{"id":"$id","type":"$type","fileName":"$id.jpg","timestamp":${id.length.toLong() + 1_700_000_000_000},""" +
            """"fileSizeBytes":10,"durationMs":0,"favorite":false,""" +
            """"thumbnailUrl":"/api/media/$id/thumbnail","url":"/api/media/$id",""" +
            """"downloadUrl":"/api/media/$id?download=1"}"""

    /**
     * The paged answer: page 0 (absent param) carries a+b with more behind;
     * page 1 carries a REPLAY of a (the dedupe guard's target) + c, done.
     * The `type=VIDEO` answer is a different page entirely — only b, no more
     * — so the filter test asserts replacement, not accumulation. Confirmed
     * deletions persist ([deletedIds]): every page serves only the ids the
     * fake device still holds, so the post-delete reload reads a
     * deletion-shaped page 0 like the real device.
     */
    private fun galleryPageJson(page: String?, type: String? = null): String {
        val (entries, hasMore) = when {
            type == "VIDEO" -> listOf("b" to "VIDEO") to false
            page == null || page == "0" -> listOf("a" to "PHOTO", "b" to "VIDEO") to true
            page == "1" -> listOf("a" to "PHOTO", "c" to "PHOTO") to false
            else -> emptyList<Pair<String, String>>() to false
        }
        val items = entries
            .filter { it.first !in deletedIds }
            .joinToString(",") { (id, kind) -> itemJson(id, kind) }
        val pageIndex = page?.toIntOrNull() ?: 0
        return """{"items":[$items],"total":3,"page":$pageIndex,"pageSize":30,"hasMore":$hasMore}"""
    }

    @Test
    fun `construction fetches page 0 and loadMore appends page 1 with a dedupe and the hasMore verdict`() =
        runBlocking {
            val parent = Job()
            val src = source(parent)
            try {
                val first = withTimeout(5_000) { src.items.first { it.isNotEmpty() } }
                assertEquals(listOf("a", "b"), first.map { it.id })
                assertTrue(src.hasMore.first())

                val second = src.loadMoreAwaiting(3)
                // The replayed "a" deduped; the wire order preserved.
                assertEquals(listOf("a", "b", "c"), second.map { it.id })
                assertFalse(src.hasMore.first())

                // The wire page index is 0-based and the FIRST page rides with
                // no page param at all (the client omits non-positive pages —
                // the media tab's correction of the web client's slip); the
                // next page asks for page=1 explicitly.
                val galleryRequests = requests.filter { it.contains("/api/gallery") }
                assertEquals(2, galleryRequests.size)
                assertFalse(galleryRequests[0].contains("page="))
                assertTrue(galleryRequests[1].contains("page=1"))
            } finally {
                parent.cancel()
            }
        }

    @Test
    fun `loadMore is inert once the server answered hasMore=false`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.items.first { it.isNotEmpty() } } // page 0 first: loadMore no-ops while a fetch is in flight
            src.loadMoreAwaiting(3)
            requests.clear()
            src.loadMore()
            delay(150)
            assertTrue(requests.none { it.contains("/api/gallery") })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `the type filter restarts paging at page 0 with the wire type param`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.items.first { it.isNotEmpty() } }
            src.setMediaTypeFilter(CaptureType.VIDEO)
            // The filter REPLACES the pages — the unfiltered library shrinks
            // to the video-only answer, it does not append. The restart rides
            // the wire type param and no page param (the first page again).
            withTimeout(5_000) { src.items.first { it.size == 1 } }
            assertEquals(listOf("b"), src.items.first().map { it.id })
            assertFalse(src.hasMore.first())
            val last = requests.last { it.contains("/api/gallery") }
            assertTrue(last.contains("type=VIDEO"))
            assertFalse(last.contains("page="))
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a single delete rides the media route and drops the item after the 2xx`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.items.first { it.isNotEmpty() } }
            src.delete(listOf("a"))
            withTimeout(5_000) { src.items.first { list -> list.none { it.id == "a" } } }
            assertTrue(requests.any { it.startsWith("DELETE /api/media/a") })
            assertEquals(listOf("b"), src.items.first().map { it.id })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a batch delete rides batch-delete and drops the selection`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.items.first { it.isNotEmpty() } } // page 0 first: loadMore no-ops while a fetch is in flight
            src.loadMoreAwaiting(3)
            src.delete(listOf("a", "c"))
            withTimeout(5_000) { src.items.first { it.size == 1 } }
            assertEquals(listOf("b"), src.items.first().map { it.id })
            assertTrue(requests.any { it.startsWith("POST /api/media/batch-delete") })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a forbidden delete leaves the list untouched`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.items.first { it.isNotEmpty() } } // page 0 first: loadMore no-ops while a fetch is in flight
            src.loadMoreAwaiting(3)
            val before = src.items.first()
            deleteResponseCode.set(403) // the viewer-role write the device denies
            src.delete(listOf("a", "b"))
            delay(250)
            assertEquals(before, src.items.first())
        } finally {
            parent.cancel()
            deleteResponseCode.set(200)
        }
    }

    @Test
    fun `a roster-lost source settles empty and no-ops every mutation`() = runBlocking {
        val parent = Job()
        val src = source(parent, api = null)
        try {
            delay(150) // the init refresh has nowhere to dial
            assertTrue(src.items.first().isEmpty())
            assertFalse(src.hasMore.first())
            src.delete(listOf("a"))
            src.toggleFavorite("a")
            delay(100)
            assertTrue(requests.isEmpty())
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `the favorite toggle is wire-hidden and fires no request`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.items.first { it.isNotEmpty() } }
            assertFalse(src.canFavorite)
            val before = requests.size
            src.toggleFavorite("a")
            delay(150)
            assertEquals(before, requests.size)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `remote verdicts pin the read-only facts`() = runBlocking {
        val parent = Job()
        val src = source(parent)
        try {
            withTimeout(5_000) { src.items.first { it.isNotEmpty() } }
            assertFalse(src.deleteIsUndoable)
            assertFalse(src.canShareExternally)
            assertTrue(src.stagedIds.first().isEmpty())
            assertNull(src.storageBar.first())
        } finally {
            parent.cancel()
        }
    }
}
