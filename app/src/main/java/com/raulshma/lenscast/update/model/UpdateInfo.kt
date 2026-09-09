package com.raulshma.lenscast.update.model

import androidx.annotation.Keep
import com.squareup.moshi.Json

@Keep
data class GitHubRelease(
    @param:Json(name = "tag_name") val tagName: String,
    @param:Json(name = "name") val name: String,
    @param:Json(name = "body") val body: String,
    @param:Json(name = "html_url") val htmlUrl: String,
    @param:Json(name = "assets") val assets: List<GitHubAsset>,
)

@Keep
data class GitHubAsset(
    @param:Json(name = "name") val name: String,
    @param:Json(name = "browser_download_url") val browserDownloadUrl: String,
    @param:Json(name = "size") val size: Long,
    // GitHub's release-asset digest, "sha256:<hex>". Absent on some
    // releases — null means no integrity verification is possible (the
    // install proceeds with a logged warning), never a failed one.
    @param:Json(name = "digest") val digest: String? = null,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpdateAvailable(
        val version: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val fileSizeBytes: Long,
        val fileName: String,
        /** The release asset's "sha256:<hex>" digest; null when GitHub omits it. */
        val digest: String? = null,
    ) : UpdateState
    data class UpToDate(val remoteVersion: String = "") : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class ReadyToInstall(val apkFilePath: String) : UpdateState
    data class Error(val message: String) : UpdateState
}

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: GitHubRelease, val apkAsset: GitHubAsset) : UpdateCheckResult
    data class UpToDate(val remoteVersion: String, val localVersion: String) : UpdateCheckResult
    data object RateLimited : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}
