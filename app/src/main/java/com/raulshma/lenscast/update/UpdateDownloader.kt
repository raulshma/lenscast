package com.raulshma.lenscast.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateDownloader(private val context: Context) {

    companion object {
        private const val TAG = "UpdateDownloader"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val UPDATE_DIR = "updates"
    }

    private val updateDir = File(context.cacheDir, UPDATE_DIR).also { it.mkdirs() }

    fun download(downloadUrl: String, fileName: String): Flow<Float> = flow {
        updateDir.listFiles()?.forEach { it.delete() }

        val targetFile = File(updateDir, fileName)
        val connection = createConnection(downloadUrl)

        try {
            val contentLength = connection.contentLengthLong
            connection.inputStream.buffered().use { input ->
                targetFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        totalRead += read
                        if (contentLength > 0) {
                            emit(totalRead.toFloat() / contentLength.toFloat())
                        }
                    }
                }
            }
            emit(1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            targetFile.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun getDownloadedApk(): File? {
        return updateDir.listFiles()?.firstOrNull { it.name.endsWith(".apk") && it.length() > 0 }
    }

    private fun createConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "LensCast")
            instanceFollowRedirects = true
        }
    }
}
