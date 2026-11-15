package com.raulshma.lenscast.companion.ui.tabs.media

import com.raulshma.lenscast.companion.client.dto.GalleryItem

/**
 * The media tab's MVI vocabulary: the render state the tab shows, the one-shot
 * effects it executes outside the state tree, and the small UI records that
 * carry the wire DTOs' facts (transcribed, not leaked — the composables stay
 * ViewModel-free and the state stays free of client types).
 *
 * State shape ([MediaUiState.Loading] before the first reads land, one
 * [MediaUiState.Ready] after — sections that fail to load still land Ready,
 * with their section named in [MediaUiState.Ready.failedSections], because a
 * dead gallery page must not blank an otherwise-readable recordings card).
 */

/** The gallery's type filter chips; [wire] is the `type` query param value. */
enum class MediaGalleryFilter(val wire: String?) {
    ALL(null),
    PHOTOS("PHOTO"),
    VIDEOS("VIDEO"),
}

/** One tab section that failed its initial load; the error banner names it. */
enum class MediaSection { GALLERY, RECORDINGS, INTERVAL }

/**
 * One capture in the tab's grid — the [GalleryItem] facts the UI renders.
 * [mediaUrl]/[downloadUrl] are full absolute URLs (the tab builds them off
 * the client's URL builders) so the viewer/loader and the share intent need
 * no client round trip to use them.
 */
data class MediaUiItem(
    val id: String,
    /** The wire type — `PHOTO` | `VIDEO`. */
    val type: String,
    val fileName: String,
    val timestampMs: Long,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val favorite: Boolean,
    /** The full-size media route — the viewer's (and thumbnails') source. */
    val mediaUrl: String,
    /** The forced-download route — the v1 share intent's payload. */
    val downloadUrl: String,
) {
    val isVideo: Boolean get() = type == "VIDEO"
}

/** The item the in-tab viewer is showing; null = the viewer is closed. */
data class MediaViewerTarget(
    val id: String,
    val isVideo: Boolean,
    /** The playback/full-size source (auth headers ride separately). */
    val url: String,
    /** The forced-download URL — the share button's payload. */
    val downloadUrl: String,
    val fileName: String,
)

/** What the armed delete-confirm dialog will remove, when one is open. */
sealed interface MediaPendingDelete {

    /** One capture, named for the dialog's copy. */
    data class Single(val id: String, val fileName: String) : MediaPendingDelete

    /** The multi-select batch. */
    data class Batch(val ids: List<String>) : MediaPendingDelete
}

/** The manual-recording card's facts. */
data class MediaRecordingUi(
    val isRecording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val isScheduled: Boolean = false,
)

/** One NVR recording session row (compact list, latest first). */
data class MediaSessionUi(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    /** `manual` | `motion` | `sound` | `continuous` | `scheduled` | `interval`. */
    val trigger: String,
    val hasClip: Boolean,
)

/** The interval-capture card's facts. */
data class MediaIntervalUi(
    val isRunning: Boolean = false,
    val completedCaptures: Int = 0,
)

/** The media tab's MVI state. */
sealed interface MediaUiState {

    /** The initial parallel load (gallery page 1 + recordings + interval). */
    data object Loading : MediaUiState

    /**
     * The tab's render facts. Sections that failed their load are named in
     * [failedSections] — the banner lists them and offers a retry; everything
     * else renders whatever did land (an empty gallery is the honest empty
     * state, not an error).
     */
    data class Ready(
        val items: List<MediaUiItem> = emptyList(),
        val filter: MediaGalleryFilter = MediaGalleryFilter.ALL,
        val total: Int = 0,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        /** Roster-role gate: viewer devices get no destructive controls. */
        val canManage: Boolean = true,
        /** The open in-tab viewer; null = closed. */
        val viewer: MediaViewerTarget? = null,
        /** The multi-select set (grid selection mode is its non-emptiness). */
        val selection: Set<String> = emptySet(),
        /** The armed delete-confirm dialog, when one is open. */
        val pendingDelete: MediaPendingDelete? = null,
        val recording: MediaRecordingUi = MediaRecordingUi(),
        /** Latest first (the VM sorts before publishing). */
        val sessions: List<MediaSessionUi> = emptyList(),
        val interval: MediaIntervalUi = MediaIntervalUi(),
        /**
         * The interval editor's raw draft (the seconds field's text) and its
         * validity against the device's accepted range — the ViewModel
         * recomputes the flag on every draft write; the card's error copy and
         * the Start button's enablement render from it.
         */
        val intervalDraft: String = DEFAULT_INTERVAL_DRAFT,
        val intervalDraftValid: Boolean = true,
        val failedSections: Set<MediaSection> = emptySet(),
        /** A recording/interval/delete command is in flight — buttons wait. */
        val busy: Boolean = false,
    ) : MediaUiState
}

/** One-shot effects the tab executes outside the state tree (snackbars). */
sealed interface MediaEffect {

    /** The pending delete succeeded — [count] captures went away. */
    data class Deleted(val count: Int) : MediaEffect

    /** The device refused a destructive action with 403 — viewer session. */
    data object Forbidden : MediaEffect

    /** The device answered 401 — the held credential is stale (re-pair cue). */
    data object AuthRequired : MediaEffect

    /** Any other command failure (transport, non-2xx envelope). */
    data object ActionFailed : MediaEffect
}

/** GalleryItem → grid record. */
internal fun GalleryItem.toUiItem(mediaUrl: String, downloadUrl: String): MediaUiItem = MediaUiItem(
    id = id,
    type = type,
    fileName = fileName,
    timestampMs = timestamp,
    fileSizeBytes = fileSizeBytes,
    durationMs = durationMs,
    favorite = favorite,
    mediaUrl = mediaUrl,
    downloadUrl = downloadUrl,
)

/** The interval editor's seed text — [MediaViewModel.DEFAULT_INTERVAL_SECONDS]. */
internal const val DEFAULT_INTERVAL_DRAFT = "60"
