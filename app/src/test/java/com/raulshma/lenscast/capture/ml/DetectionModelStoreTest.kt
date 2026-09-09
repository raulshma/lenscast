package com.raulshma.lenscast.capture.ml

import com.raulshma.lenscast.capture.ml.DetectionModelStore.State
import com.raulshma.lenscast.update.UpdateIntegrity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The on-demand model store under JVM tests: the install ladder
 * (`verifyAndInstall` — size, pinned SHA-256, atomic rename, part-file
 * hygiene on every failure), the disk resolution the engine reads, and the
 * idempotent single-flight download over a fake connection (tiny fake
 * expectations keep the whole ladder hermetic — the production digest and
 * size are constructor-injected; the Unconfined scope makes the launched
 * download run to completion inside `requestDownload`).
 */
class DetectionModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // The fake model's pinned identity: tiny, but the same ladder the
    // production 4.4 MB download walks.
    private val fakeModelBytes = "fake-model-bytes".toByteArray()
    private val fakeModelSha256 = UpdateIntegrity.sha256Hex(ByteArrayInputStream(fakeModelBytes))

    /** Serves fixed bytes (or fails on connect / mid-stream); counts connects for the idempotence pins. */
    private class FakeConnection(
        private val payload: ByteArray,
        private val fail: Boolean = false,
        /** When set, the stream serves this many bytes then dies — the connection-drop case. */
        private val failAfter: Int = -1,
    ) : HttpURLConnection(URL(DetectionModelStore.MODEL_URL)) {

        var connectCount = 0
            private set

        override fun getContentLength(): Int = if (fail) -1 else payload.size

        override fun connect() {
            connectCount++
            if (fail) throw IOException("network unreachable")
        }

        override fun getInputStream(): java.io.InputStream {
            connect()
            if (failAfter >= 0) {
                return object : java.io.InputStream() {
                    var served = 0
                    override fun read(): Int {
                        if (served >= failAfter) throw IOException("connection reset mid-stream")
                        served++
                        return payload[served - 1].toInt() and 0xFF
                    }
                }
            }
            return ByteArrayInputStream(payload)
        }

        override fun usingProxy(): Boolean = false

        override fun disconnect() = Unit
    }

    private fun store(
        dir: File = tmp.newFolder(),
        connection: FakeConnection? = null,
        expectedBytes: Long = fakeModelBytes.size.toLong(),
        expectedSha256: String = fakeModelSha256,
    ): DetectionModelStore =
        DetectionModelStore(
            modelsDir = dir,
            expectedSha256Hex = expectedSha256,
            expectedBytes = expectedBytes,
            connectionFactory = { connection ?: FakeConnection(ByteArray(0)) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun partFile(dir: File): File = File(dir, "${DetectionModelStore.MODEL_FILE_NAME}.part")

    // verifyAndInstall — the integrity gate

    @Test
    fun `correct bytes install under the model file name and remove the part file`() {
        val dir = tmp.newFolder()
        val store = store(dir)
        partFile(dir).writeBytes(fakeModelBytes)

        val result = store.verifyAndInstall(partFile(dir)) as State.Ready

        assertEquals(fakeModelBytes.size.toLong(), result.file.length())
        assertEquals(result.file, store.resolveModelFile())
        assertFalse(partFile(dir).exists())
    }

    @Test
    fun `a wrong-size download is discarded and never installed`() {
        val dir = tmp.newFolder()
        val store = store(dir)
        partFile(dir).writeBytes(fakeModelBytes.copyOfRange(0, 5))

        val result = store.verifyAndInstall(partFile(dir)) as State.Failed

        assertTrue(result.reason.contains("size"))
        assertFalse(partFile(dir).exists())
        assertNull(store.resolveModelFile())
    }

    @Test
    fun `a wrong-digest download is discarded and never installed`() {
        val dir = tmp.newFolder()
        val store = store(dir, expectedSha256 = "00".repeat(32))
        partFile(dir).writeBytes(fakeModelBytes)

        val result = store.verifyAndInstall(partFile(dir)) as State.Failed

        assertTrue(result.reason.contains("SHA-256"))
        assertFalse(partFile(dir).exists())
        assertNull(store.resolveModelFile())
    }

    @Test
    fun `a missing part file fails without creating anything`() {
        val dir = tmp.newFolder()
        val store = store(dir)

        val result = store.verifyAndInstall(partFile(dir))

        assertTrue(result is State.Failed)
        assertEquals(0, dir.listFiles()?.size)
    }

    // resolveModelFile — what the engine reads

    @Test
    fun `resolve returns null while nothing is installed`() {
        assertNull(store().resolveModelFile())
    }

    @Test
    fun `resolve quarantines an installed file with the wrong size`() {
        val dir = tmp.newFolder()
        val wrong = File(dir, DetectionModelStore.MODEL_FILE_NAME).apply { writeBytes("too-short".toByteArray()) }

        assertNull(store(dir).resolveModelFile())
        assertFalse(wrong.exists())
    }

    @Test
    fun `a fresh store starts Ready when a previous run already installed the model`() {
        val dir = tmp.newFolder()
        partFile(dir).writeBytes(fakeModelBytes)
        store(dir).verifyAndInstall(partFile(dir))

        val fresh = store(dir)

        assertTrue(fresh.state.value is State.Ready)
        assertNotNull(fresh.resolveModelFile())
    }

    @Test
    fun `an installed file corrupted at rest is quarantined and demoted off Ready`() {
        val dir = tmp.newFolder()
        val connection = FakeConnection(fakeModelBytes)
        val store = store(dir, connection)
        store.requestDownload()
        val installed = store.resolveModelFile()!!
        // Same size, different bytes: exactly the corruption the size gate alone misses.
        installed.writeBytes(ByteArray(fakeModelBytes.size))
        assertTrue(store.state.value is State.Ready)

        assertNull(store.resolveModelFile())
        assertFalse(installed.exists())
        val state = store.state.value as State.Failed
        assertTrue(state.reason.contains("verification"))

        // The demotion un-wedges the lifecycle: the next request re-downloads.
        store.requestDownload()
        assertTrue(store.state.value is State.Ready)
        assertEquals(2, connection.connectCount)
    }

    @Test
    fun `a wrong-size installed file is quarantined and the retry re-downloads`() {
        val dir = tmp.newFolder()
        val connection = FakeConnection(fakeModelBytes)
        val store = store(dir, connection)
        store.requestDownload()
        assertTrue(store.state.value is State.Ready)
        store.resolveModelFile()!!.writeBytes("wrong-size".toByteArray())

        assertNull(store.resolveModelFile())
        assertFalse(store.state.value is State.Ready)

        store.requestDownload()
        assertTrue(store.state.value is State.Ready)
        assertEquals(2, connection.connectCount)
    }

    @Test
    fun `a fresh store over a corrupt installed file starts NotDownloaded and quarantines it`() {
        val dir = tmp.newFolder()
        val corrupt = File(dir, DetectionModelStore.MODEL_FILE_NAME)
        corrupt.writeBytes(ByteArray(fakeModelBytes.size))

        val fresh = store(dir)

        assertNull(fresh.resolveModelFile())
        assertFalse(corrupt.exists())
        assertTrue(fresh.state.value is State.NotDownloaded)
    }

    // requestDownload — idempotence and the full ladder

    @Test
    fun `a download over a fake connection installs the model and lands Ready`() {
        val dir = tmp.newFolder()
        val connection = FakeConnection(fakeModelBytes)
        val store = store(dir, connection)

        store.requestDownload()

        assertTrue(store.state.value is State.Ready)
        assertEquals(fakeModelBytes.size.toLong(), store.resolveModelFile()?.length())
        assertFalse(partFile(dir).exists())
    }

    @Test
    fun `a Ready model is never re-downloaded`() {
        val dir = tmp.newFolder()
        val connection = FakeConnection(fakeModelBytes)
        val store = store(dir, connection)
        store.requestDownload()
        val connectsAfterFirst = connection.connectCount

        store.requestDownload()

        assertEquals(connectsAfterFirst, connection.connectCount)
        assertTrue(store.state.value is State.Ready)
    }

    @Test
    fun `a connection drop mid-stream fails and discards the partial file`() {
        val dir = tmp.newFolder()
        val store = store(dir, FakeConnection(fakeModelBytes, failAfter = 5))

        store.requestDownload()

        val state = store.state.value as State.Failed
        assertTrue(state.reason.contains("mid-stream"))
        assertFalse(partFile(dir).exists())
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun `a failed download lands in Failed with the reason`() {
        val dir = tmp.newFolder()
        val store = store(dir, FakeConnection(ByteArray(0), fail = true))

        store.requestDownload()

        val state = store.state.value as State.Failed
        assertTrue(state.reason.isNotEmpty())
        assertNull(store.resolveModelFile())
    }

    @Test
    fun `a Failed model may be retried on the same store`() {
        val dir = tmp.newFolder()
        var fail = true
        val store = DetectionModelStore(
            modelsDir = dir,
            expectedSha256Hex = fakeModelSha256,
            expectedBytes = fakeModelBytes.size.toLong(),
            connectionFactory = { FakeConnection(fakeModelBytes, fail = fail) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        store.requestDownload()
        assertTrue(store.state.value is State.Failed)

        fail = false
        store.requestDownload()

        assertTrue(store.state.value is State.Ready)
        assertEquals(fakeModelBytes.size.toLong(), store.resolveModelFile()?.length())
    }

    @Test
    fun `concurrent requests download exactly once`() {
        val dir = tmp.newFolder()
        val connection = FakeConnection(fakeModelBytes)
        val store = store(dir, connection)

        // Whether the second request overlaps the first (single-flight claim)
        // or lands after it (Ready no-op), exactly one connect may happen.
        val threads = (1..2).map { Thread { store.requestDownload() } }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }

        assertTrue(store.state.value is State.Ready)
        assertEquals(1, connection.connectCount)
    }

    // Pinned production constants

    @Test
    fun `the production digest is a full lowercase sha256 hex string`() {
        assertTrue(DetectionModelStore.EXPECTED_SHA256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `the production size is positive and the url points at the pinned model over https`() {
        assertTrue(DetectionModelStore.EXPECTED_BYTES > 0)
        assertTrue(DetectionModelStore.MODEL_URL.endsWith(DetectionModelStore.MODEL_FILE_NAME))
        assertTrue(DetectionModelStore.MODEL_URL.startsWith("https://"))
    }

    // wireFields — the settings DTO's response-only mapping

    @Test
    fun `every state maps to its wire fields`() {
        assertEquals(
            DetectionModelStore.ModelWireFields(
                DetectionModelStore.STATE_NOT_DOWNLOADED,
                DetectionModelStore.PROGRESS_NONE,
                "",
            ),
            DetectionModelStore.wireFields(State.NotDownloaded),
        )
        assertEquals(
            DetectionModelStore.ModelWireFields(DetectionModelStore.STATE_DOWNLOADING, 0.5, ""),
            DetectionModelStore.wireFields(State.Downloading(0.5f)),
        )
        assertEquals(
            DetectionModelStore.ModelWireFields(DetectionModelStore.STATE_READY, DetectionModelStore.PROGRESS_NONE, ""),
            DetectionModelStore.wireFields(State.Ready(File("model.tflite"))),
        )
        assertEquals(
            DetectionModelStore.ModelWireFields(DetectionModelStore.STATE_FAILED, DetectionModelStore.PROGRESS_NONE, "boom"),
            DetectionModelStore.wireFields(State.Failed("boom")),
        )
    }
}
