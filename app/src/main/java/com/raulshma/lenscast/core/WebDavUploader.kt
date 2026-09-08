package com.raulshma.lenscast.core

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Minimal WebDAV client for capture backup: MKCOL for missing collections,
 * PUT for the file, Basic auth throughout. Covers Nextcloud/ownCloud/Apache
 * (RFC 4918) — the F-Droid-friendly backup path with no cloud dependency.
 *
 * The URL/content-type mapping helpers are internal and pure so they can be
 * exercised from JVM tests; the network I/O is not (it is exercised against
 * real collections).
 */
class WebDavUploader(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) {

    fun upload(file: File): Boolean = try {
        mkdirs(directoryPath()) &&
            putStream(collectionUrlFor(file.name).toString(), file.length(), file.inputStream(), contentTypesFor(file.name))
    } catch (e: Exception) {
        Log.w(TAG, "WebDAV upload failed for ${file.name}: ${e.message}")
        false
    }

    /** Stream variant for MediaStore content URIs (recordings). */
    fun upload(fileName: String, sizeBytes: Long, input: java.io.InputStream): Boolean = try {
        mkdirs(directoryPath()) &&
            putStream(collectionUrlFor(fileName).toString(), sizeBytes, input, contentTypesFor(fileName))
    } catch (e: Exception) {
        Log.w(TAG, "WebDAV upload failed for $fileName: ${e.message}")
        false
    }

    /** The server path of the collection part of [baseUrl] (no trailing slash). */
    internal fun directoryPath(): String = basePath().trimEnd('/')

    internal fun collectionUrlFor(fileName: String): URL = URI(
        baseUrl.trimEnd('/') + "/" + java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20"),
    ).toURL()

    private fun basePath(): String {
        val uri = URI(baseUrl)
        return uri.path ?: "/"
    }

    /** MKCOL every missing segment of the directory path; existing (405) is fine. */
    internal fun mkdirs(directoryPath: String): Boolean {
        val uri = URI(baseUrl)
        val segments = directoryPath.split("/").filter { it.isNotBlank() }
        var accumulated = ""
        var ok = true
        for (segment in segments) {
            accumulated += "/" + segment
            val code = request(
                url = URI(uri.scheme, uri.userInfo, uri.host, uri.port, accumulated, null, null).toString(),
                method = "MKCOL",
                sizeBytes = 0L,
                body = null,
            )
            // 201 created, 405 already exists, 301 already exists (trailing-slash
            // redirect from some servers) all mean "usable collection".
            if (code !in listOf(201, 405, 301) && code >= 400) ok = false
        }
        return ok
    }

    private fun putStream(url: String, sizeBytes: Long, input: java.io.InputStream, contentType: String): Boolean {
        val code = request(url, "PUT", sizeBytes, input, contentType)
        if (code !in 200..299) {
            Log.w(TAG, "WebDAV PUT answered HTTP $code")
        }
        return code in 200..299
    }

    private fun request(
        url: String,
        method: String,
        sizeBytes: Long,
        body: java.io.InputStream?,
        contentType: String = "application/octet-stream",
    ): Int {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            if (username.isNotEmpty() || password.isNotEmpty()) {
                val credentials = Base64Codec.encode(
                    "$username:$password".toByteArray(Charsets.UTF_8)
                )
                setRequestProperty("Authorization", "Basic $credentials")
            }
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(sizeBytes)
                setRequestProperty("Content-Type", contentType)
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    body.use { input -> input.copyTo(output, 64 * 1024) }
                }
            }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    internal fun contentTypesFor(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".mp4", true) -> "video/mp4"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "WebDavUploader"
        private const val TIMEOUT_MS = 30_000
    }
}
