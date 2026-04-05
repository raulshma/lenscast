package com.raulshma.lenscast.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.raulshma.lenscast.update.model.GitHubAsset
import com.raulshma.lenscast.update.model.GitHubRelease
import com.raulshma.lenscast.update.model.UpdateCheckResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val context: Context) {

    companion object {
        private const val API_URL =
            "https://api.github.com/repos/raulshma/lenscast/releases/latest"
        private const val TAG = "UpdateChecker"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    private val moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val releaseAdapter by lazy {
        moshi.adapter(GitHubRelease::class.java)
    }

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking for updates: $API_URL")
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "LensCast")
                instanceFollowRedirects = true
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")
            if (responseCode == 403) {
                Log.w(TAG, "GitHub API rate limited")
                return@withContext UpdateCheckResult.Error("GitHub API rate limited. Try again later.")
            }
            if (responseCode != 200) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (_: Exception) { null }
                Log.e(TAG, "HTTP $responseCode: $errorBody")
                return@withContext UpdateCheckResult.Error("Server returned HTTP $responseCode")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            Log.d(TAG, "Parsing release JSON (${body.length} chars)")
            val release = releaseAdapter.fromJson(body)
                ?: return@withContext UpdateCheckResult.Error("Failed to parse release JSON")

            Log.d(TAG, "Latest release: ${release.tagName} with ${release.assets.size} assets")
            val apkAsset = selectApkAsset(release.assets)
                ?: return@withContext UpdateCheckResult.Error("No APK found in release")

            val currentVersion = getAppVersionName()
            val remoteVersion = release.tagName.trimStart('v')
            Log.d(TAG, "Remote: $remoteVersion, Local: $currentVersion, isNewer: ${isNewerVersion(release.tagName, currentVersion)}")
            if (!isNewerVersion(release.tagName, currentVersion)) {
                return@withContext UpdateCheckResult.UpToDate(remoteVersion, currentVersion)
            }

            Log.d(TAG, "Update available: ${release.tagName}")
            UpdateCheckResult.UpdateAvailable(release, apkAsset)
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            UpdateCheckResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun selectApkAsset(assets: List<GitHubAsset>): GitHubAsset? {
        // Prefer universal APK for ABI compatibility
        val universal = assets.firstOrNull {
            it.name.endsWith(".apk") && it.name.contains("universal", ignoreCase = true)
        }
        if (universal != null) return universal

        // Fallback to any APK
        return assets.firstOrNull { it.name.endsWith(".apk") }
    }

    private fun getAppVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "0.0.0"
        }
    }

    private fun isNewerVersion(remoteTag: String, localVersion: String): Boolean {
        val remote = remoteTag.trimStart('v')
        val local = localVersion.trimStart('v')
        val remoteParts = remote.split('.').mapNotNull { it.toIntOrNull() }
        val localParts = local.split('.').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
