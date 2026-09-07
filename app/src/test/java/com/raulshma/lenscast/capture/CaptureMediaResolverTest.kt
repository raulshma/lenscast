package com.raulshma.lenscast.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The capture-media scheme ladder. Path classification is pure (string in →
 * verdict out); the plain-path file branches run for real against temp files.
 * The content:// and file:// URI branches need the platform (ContentResolver
 * / android.net.Uri) and are exercised on device only.
 */
class CaptureMediaResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val resolver = CaptureMediaResolver()

    // ── Classification ──

    @Test
    fun `content uris classify as content`() {
        assertEquals(
            CaptureMediaResolver.PathKind.CONTENT_URI,
            resolver.classify("content://media/external/images/1"),
        )
    }

    @Test
    fun `file uris classify as file`() {
        assertEquals(
            CaptureMediaResolver.PathKind.FILE_URI,
            resolver.classify("file:///storage/emulated/0/Pictures/LensCast/IMG_1.jpg"),
        )
    }

    @Test
    fun `plain paths classify as plain`() {
        assertEquals(
            CaptureMediaResolver.PathKind.PLAIN_PATH,
            resolver.classify("/storage/emulated/0/Pictures/LensCast/IMG_1.jpg"),
        )
        assertEquals(CaptureMediaResolver.PathKind.PLAIN_PATH, resolver.classify(""))
    }

    // ── Display models ──

    @Test
    fun `existing plain path resolves to its file, a missing one to null`() {
        val file = tmp.newFile("IMG_1.jpg")
        assertEquals(file, resolver.displayModel(file.absolutePath))
        assertNull(resolver.displayModel(File(tmp.root, "missing.jpg").absolutePath))
    }

    // ── Streams and existence (plain-path branches) ──

    @Test
    fun `plain paths open and exist through the file`() {
        val file = tmp.newFile("IMG_2.jpg")
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(resolver.exists(file.absolutePath))
        resolver.openStream(file.absolutePath)!!.use { stream ->
            assertEquals(3, stream.readBytes().size)
        }
        assertNull(resolver.openStream(File(tmp.root, "missing.jpg").absolutePath))
        assertFalse(resolver.exists(File(tmp.root, "missing.jpg").absolutePath))
    }

    @Test
    fun `openMedia reports the actual length for plain paths`() {
        val file = tmp.newFile("IMG_3.jpg")
        file.writeBytes(ByteArray(42))
        val opened = resolver.openMedia(file.absolutePath, recordedSizeBytes = 7L)
        requireNotNull(opened)
        assertEquals(42L, opened.sizeBytes)
        assertNull(resolver.openMedia(File(tmp.root, "missing.jpg").absolutePath, 7L))
    }

    // ── Deletion (plain-path branches) ──

    @Test
    fun `delete removes plain files`() {
        val plain = tmp.newFile("IMG_4.jpg")
        assertTrue(resolver.delete(plain.absolutePath))
        assertFalse(plain.exists())
    }

    @Test
    fun `a missing plain file already counts as deleted`() {
        val missing = File(tmp.root, "already_gone.jpg")
        assertTrue(resolver.delete(missing.absolutePath))
    }

    @Test
    fun `content uris without a resolver can neither open exist nor delete`() {
        val content = "content://media/external/images/1"
        assertNull(resolver.openStream(content))
        assertFalse(resolver.exists(content))
        assertFalse(resolver.delete(content))
        assertNull(resolver.openMedia(content, recordedSizeBytes = 5L))
    }
}
