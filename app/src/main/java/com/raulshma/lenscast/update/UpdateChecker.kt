package com.raulshma.lenscast.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.raulshma.lenscast.update.model.GitHubRelease
import com.raulshma.lenscast.update.model.UpdateCheckResult
import com.raulshma.lenscast.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateChecker(private val context: Context) {

    companion object {
        private const val API_URL =
            "https://api.github.com/repos/raulshma/lenscast/releases/latest"
        private const val TAG = "UpdateChecker"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    private val releaseAdapter by lazy {
        AppJson.moshi.adapter(GitHubRelease::class.java)
    }

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking for updates: $API_URL")
            val connection = UpdateHttp.openConnection(API_URL, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
                .apply { setRequestProperty("Accept", "application/vnd.github+json") }

            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")
            if (responseCode == 403) {
                Log.w(TAG, "GitHub API rate limited")
                return@withContext UpdateHttp.mapCheckOutcome(responseCode, parsed = null)
            }
            if (responseCode != 200) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (_: Exception) { null }
                Log.e(TAG, "HTTP $responseCode: $errorBody")
                return@withContext UpdateHttp.mapCheckOutcome(responseCode, parsed = null)
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            return@withContext UpdateHttp.mapCheckOutcome(responseCode, parsed = parseReleaseResult(body))
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            UpdateCheckResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * The release body parsed and wrapped the way a 200 reports it
     * (UpToDate/UpdateAvailable/Error) — null when the body is not a release,
     * which [UpdateHttp.mapCheckOutcome] turns into the parse-failure error.
     */
    private fun parseReleaseResult(body: String): UpdateCheckResult? {
        Log.d(TAG, "Parsing release JSON (${body.length} chars)")
        val release = releaseAdapter.fromJson(body) ?: return null

        Log.d(TAG, "Latest release: ${release.tagName} with ${release.assets.size} assets")
        val apkAsset = UpdatePolicy.selectApkAsset(release.assets)
            ?: return UpdateCheckResult.Error("No APK found in release")

        val currentVersion = getAppVersionName()
        val remoteVersion = UpdatePolicy.normalize(release.tagName)
        Log.d(TAG, "Remote: $remoteVersion, Local: $currentVersion, isNewer: ${UpdatePolicy.isNewer(release.tagName, currentVersion)}")
        if (!UpdatePolicy.isNewer(release.tagName, currentVersion)) {
            return UpdateCheckResult.UpToDate(remoteVersion, currentVersion)
        }

        Log.d(TAG, "Update available: ${release.tagName}")
        return UpdateCheckResult.UpdateAvailable(release, apkAsset)
    }

    private fun getAppVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "0.0.0"
        }
    }
}
