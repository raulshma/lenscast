package com.raulshma.lenscast.capture

import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.core.MediaCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The decrypted-photo cache: encrypted photos land once as cache files and
 * serve the gallery's Coil loading; plaintext media never enters the cache;
 * entries die with their capture ([deleteAll], the history store's hook);
 * a `.part` leftover from a killed decrypt is never mistaken for a complete
 * entry. Plain-path resolvers over temp folders, key provider faked — all JVM.
 */
class DecryptedPhotoCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES")
    private val keyProvider = MediaCrypto.KeyProvider { key }
    // Lazy: the TemporaryFolder rule creates the root in its own @Before, so
    // the folder must not be resolved at field-initialization time.
    private val cacheDir by lazy { tmp.newFolder("media_decrypto") }
    private val resolver = CaptureMediaResolver(keyProvider = keyProvider)

    private fun cache() = DecryptedPhotoCache(resolver, cacheDir)

    private fun plainFile(name: String, bytes: ByteArray): File {
        val file = tmp.root.resolve(name)
        file.writeBytes(bytes)
        return file
    }

    private fun encryptedFile(name: String, plaintext: ByteArray): File {
        val file = tmp.root.resolve(name)
        file.writeBytes(MediaCrypto.encrypt(keyProvider.getOrCreateKey(), plaintext))
        return file
    }

    @Test
    fun `plaintext photos stay out of the cache`() = runBlocking {
        val photo = plainFile("IMG_plain.jpg", ByteArray(64))
        assertNull(cache().ensure("id-1", "IMG_plain.jpg", photo.absolutePath))
        assertEquals(0, cacheDir.listFiles()?.size)
    }

    @Test
    fun `encrypted photos decrypt into a cache file with plaintext bytes`() = runBlocking {
        val plaintext = ByteArray(2_048) { (it % 251).toByte() }
        val photo = encryptedFile("IMG_enc.jpg", plaintext)
        val produced = cache().ensure("id-2", "IMG_enc.jpg", photo.absolutePath)
        requireNotNull(produced)
        assertTrue(produced.path.startsWith(cacheDir.path))
        assertArrayEquals(plaintext, produced.readBytes())
        assertTrue(cache().sniffEncrypted(photo.absolutePath))
    }

    @Test
    fun `a warm entry is reused, not re-decrypted`() = runBlocking {
        val photo = encryptedFile("IMG_warm.jpg", "bytes".toByteArray())
        val cache = cache()
        val first = cache.ensure("id-3", "IMG_warm.jpg", photo.absolutePath)!!
        // The source goes away; the cache file is the surviving model.
        photo.delete()
        val second = cache.ensure("id-3", "IMG_warm.jpg", photo.absolutePath)
        assertEquals(first, second)
        assertEquals(first, cache.peek("id-3", "IMG_warm.jpg"))
    }

    @Test
    fun `deleting the capture deletes its cache file`() = runBlocking {
        val photo = encryptedFile("IMG_gone.jpg", "bytes".toByteArray())
        val cache = cache()
        cache.ensure("id-4", "IMG_gone.jpg", photo.absolutePath)!!
        cache.delete("id-4", "IMG_gone.jpg")
        assertNull(cache.peek("id-4", "IMG_gone.jpg"))
    }

    @Test
    fun `deleteAll clears exactly the removed entries`() = runBlocking {
        val cache = cache()
        val a = encryptedFile("IMG_a.jpg", "a".toByteArray())
        val b = encryptedFile("IMG_b.jpg", "b".toByteArray())
        cache.ensure("id-a", "IMG_a.jpg", a.absolutePath)!!
        cache.ensure("id-b", "IMG_b.jpg", b.absolutePath)!!
        cache.deleteAll(
            listOf(
                CaptureHistory(
                    id = "id-a", type = CaptureType.PHOTO,
                    fileName = "IMG_a.jpg", filePath = a.absolutePath, timestamp = 0, fileSizeBytes = 0,
                ),
            ),
        )
        assertNull(cache.peek("id-a", "IMG_a.jpg"))
        assertTrue(cache.peek("id-b", "IMG_b.jpg") != null)
    }

    @Test
    fun `a stale part file is never handed out as a complete entry`() {
        val cache = cache()
        val stale = File(cache.cacheFile("id-5", "IMG_stale.jpg").parentFile, cache.cacheFile("id-5", "IMG_stale.jpg").name + ".part")
        stale.writeBytes(byteArrayOf(1, 2))
        assertNull(cache.peek("id-5", "IMG_stale.jpg"))
        assertFalse(cache.cacheFile("id-5", "IMG_stale.jpg").exists())
    }

    @Test
    fun `unreadable sources answer null without poisoning the cache`() = runBlocking {
        val missing = tmp.root.resolve("missing.jpg").absolutePath
        assertNull(cache().ensure("id-6", "missing.jpg", missing))
        assertEquals(0, cacheDir.listFiles()?.size)
    }
}
