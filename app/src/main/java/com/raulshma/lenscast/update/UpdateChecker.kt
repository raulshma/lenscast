package com.raulshma.lenscast.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.raulshma.lenscast.update.model.GitHubRelease
import com.raulshma.lenscast.update.model.UpdateCheckResult
import com.raulshma.lenscast.core.AppJson
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateChecker(private val context: Context) {

    companion object {
        // The list endpoint carries stable releases AND pre-releases; the
        // policy filters/picks per the user's beta opt-in. /releases/latest
        // would hide betas entirely — that is exactly the stable channel.
        private const val RELEASES_URL =
            "https://api.github.com/repos/raulshma/lenscast/releases?per_page=100"
        private const val TAG = "UpdateChecker"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    private val releaseListAdapter by lazy {
        AppJson.moshi.adapter<List<GitHubRelease>>(
            Types.newParameterizedType(List::class.java, GitHubRelease::class.java)
        )
    }

    suspend fun checkForUpdate(includePrereleases: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking for updates: $RELEASES_URL (includePrereleases=$includePrereleases)")
            val connection = UpdateHttp.openConnection(RELEASES_URL, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
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

            return@withContext UpdateHttp.mapCheckOutcome(
                responseCode,
                parsed = parseReleaseResult(body, includePrereleases),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            UpdateCheckResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * The release list parsed and the eligible release selected per the
     * beta opt-in, wrapped the way a 200 reports it (UpToDate/
     * UpdateAvailable/Error) — null when the body is not a release list,
     * which [UpdateHttp.mapCheckOutcome] turns into the parse-failure error.
     */
    private fun parseReleaseResult(body: String, includePrereleases: Boolean): UpdateCheckResult? {
        Log.d(TAG, "Parsing release JSON (${body.length} chars)")
        val releases = releaseListAdapter.fromJson(body) ?: return null
        val release = UpdatePolicy.selectRelease(releases, includePrereleases)
            ?: return UpdateCheckResult.Error("No release found")

        Log.d(TAG, "Selected release: ${release.tagName} (prerelease=${release.prerelease}, ${release.assets.size} assets)")
        val apkAsset = UpdatePolicy.selectApkAsset(release.assets, Build.SUPPORTED_ABIS.toList())
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
