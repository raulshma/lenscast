package com.raulshma.lenscast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Telegram multipart byte layout (boundary format, field order, final
 * boundary) and the small pure helpers around it — JVM-tested without a
 * socket.
 */
class TelegramMultipartTest {

    private val boundary = "----LensCastTestBoundary"

    @Test
    fun `build lays out chat_id then document then the final boundary`() {
        val body = TelegramMultipart.build(
            boundary = boundary,
            chatId = "123456789",
            fileName = "IMG_20260909_10153042.jpg",
            fileBytes = byteArrayOf(1, 2, 3),
            contentType = "image/jpeg",
        )
        val text = body.toString(Charsets.UTF_8)
        val expected =
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"chat_id\"\r\n" +
                "\r\n" +
                "123456789\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"document\"; filename=\"IMG_20260909_10153042.jpg\"\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "\r\n" +
                "\u0001\u0002\u0003" +
                "\r\n--$boundary--\r\n"
        assertEquals(expected, text)
    }

    @Test
    fun `the chat_id field precedes the document part`() {
        val body = TelegramMultipart.build(boundary, "42", "clip.mp4", ByteArray(4), "video/mp4")
            .toString(Charsets.UTF_8)
        val chatIdIndex = body.indexOf("name=\"chat_id\"")
        val documentIndex = body.indexOf("name=\"document\"")
        assert(chatIdIndex in 0 until documentIndex) { "chat_id must come first" }
    }

    @Test
    fun `the final boundary is the closing form terminator`() {
        val body = TelegramMultipart.build(boundary, "42", "clip.mp4", ByteArray(2))
            .toString(Charsets.UTF_8)
        val terminator = "\r\n--$boundary--\r\n"
        assert(body.endsWith(terminator)) { "body must end with the final boundary" }
        // Exactly one closing terminator, and every other boundary is open.
        assertEquals(1, body.split("--$boundary--").size - 1)
        assertEquals(2, body.split("--$boundary\r\n").size - 1)
    }

    @Test
    fun `binary file bytes survive untouched`() {
        val bytes = ByteArray(256) { it.toByte() }
        val body = TelegramMultipart.build(boundary, "42", "data.bin", bytes)
        val prologue =
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"chat_id\"\r\n" +
                "\r\n" +
                "42\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"document\"; filename=\"data.bin\"\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n"
        val offset = prologue.toByteArray(Charsets.UTF_8).size
        assertEquals(bytes.toList(), body.copyOfRange(offset, offset + bytes.size).toList())
    }

    @Test
    fun `content type defaults to octet-stream`() {
        val body = TelegramMultipart.build(boundary, "42", "data.bin", ByteArray(1))
        assert(body.toString(Charsets.UTF_8).contains("Content-Type: application/octet-stream\r\n"))
    }

    @Test
    fun `sanitize strips what would break the quoted disposition header`() {
        assertEquals("clip.mp4", TelegramUploader.sanitizeFileName("clip.mp4"))
        assertEquals("clip.mp4", TelegramUploader.sanitizeFileName("cl\"ip.mp4"))
        assertEquals("clip.mp4", TelegramUploader.sanitizeFileName("clip\r\n.mp4"))
        assertEquals("capture", TelegramUploader.sanitizeFileName(""))
        assertEquals("capture", TelegramUploader.sanitizeFileName("\""))
    }
}

/** The pure backup-target routing verdict the BackupWorker routes through. */
class BackupTargetPolicyTest {

    // ── parse ──

    @Test
    fun `parse accepts the wire names and tolerates case and whitespace`() {
        assertEquals(BackupTarget.WEBDAV, BackupTargetPolicy.parse("webdav"))
        assertEquals(BackupTarget.TELEGRAM, BackupTargetPolicy.parse("telegram"))
        assertEquals(BackupTarget.TELEGRAM, BackupTargetPolicy.parse(" TELEGRAM "))
    }

    @Test
    fun `parse falls back to webdav on null blank or unknown`() {
        assertEquals(BackupTarget.WEBDAV, BackupTargetPolicy.parse(null))
        assertEquals(BackupTarget.WEBDAV, BackupTargetPolicy.parse(""))
        assertEquals(BackupTarget.WEBDAV, BackupTargetPolicy.parse("sftp"))
        assertEquals(BackupTarget.WEBDAV, BackupTargetPolicy.parse("WEBDAV"))
    }

    @Test
    fun `wire names round-trip through parse`() {
        for (target in BackupTarget.entries) {
            assertEquals(target, BackupTargetPolicy.parse(target.wireName))
        }
    }

    // ── resolve ──

    @Test
    fun `webdav selected and configured wins`() {
        assertEquals(BackupTarget.WEBDAV, BackupTargetPolicy.resolve(BackupTarget.WEBDAV, webdavConfigured = true, telegramConfigured = false))
    }

    @Test
    fun `webdav selected but unconfigured is null - even when telegram would work`() {
        assertNull(BackupTargetPolicy.resolve(BackupTarget.WEBDAV, webdavConfigured = false, telegramConfigured = true))
    }

    @Test
    fun `telegram selected and configured wins`() {
        assertEquals(
            BackupTarget.TELEGRAM,
            BackupTargetPolicy.resolve(BackupTarget.TELEGRAM, webdavConfigured = false, telegramConfigured = true),
        )
        assertEquals(
            BackupTarget.TELEGRAM,
            BackupTargetPolicy.resolve(BackupTarget.TELEGRAM, webdavConfigured = true, telegramConfigured = true),
        )
    }

    @Test
    fun `telegram selected but unconfigured is null - no cross-target fallback`() {
        assertNull(BackupTargetPolicy.resolve(BackupTarget.TELEGRAM, webdavConfigured = true, telegramConfigured = false))
        assertNull(BackupTargetPolicy.resolve(BackupTarget.TELEGRAM, webdavConfigured = false, telegramConfigured = false))
    }
}
