package com.raulshma.lenscast.companion.ui.tabs.system

import androidx.annotation.StringRes
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.dto.AllSettings
import com.raulshma.lenscast.companion.client.dto.AuthSessionEntry
import com.raulshma.lenscast.companion.client.dto.LensesResponse
import com.raulshma.lenscast.companion.client.dto.RemoteAuditEntry
import com.raulshma.lenscast.companion.client.dto.StreamingSettings
import com.raulshma.lenscast.companion.client.dto.SystemInfo

/**
 * The system tab's MVI vocabulary: one sealed state, the per-card data
 * blocks, the one-shot effects, and the action lambda set the stateless
 * content composable renders against (the repo's [DevicesActions] idiom, so
 * the Robolectric test never needs a ViewModel).
 */

/**
 * The tab's state tree. [Loading] is the first-fetch moment; [Ready] carries
 * the four cards independently — each section degrades on its own (a failed
 * settings read must not blank the info card), with `null` meaning "this
 * section has nothing to show" and [SystemUiState.Ready.failed] marking the
 * nothing-landed-at-all case the retry affordance covers.
 */
sealed interface SystemUiState {

    /** The initial parallel load is in flight. */
    data object Loading : SystemUiState

    /**
     * The four cards' facts. Refreshes keep the previous blocks in place
     * (sections never blank while their re-read is in flight).
     */
    data class Ready(
        val info: SystemInfo? = null,
        val lenses: LensesResponse? = null,
        val settings: SettingsBlock? = null,
        val identity: IdentityBlock? = null,
        val audit: AuditBlock? = null,
        /** Every section failed — the card area shows the retry affordance. */
        val failed: Boolean = false,
        /** A refresh round is in flight; cards keep rendering. */
        val refreshing: Boolean = false,
    ) : SystemUiState
}

/**
 * The settings editor's block: the last server-confirmed document, plus the
 * in-progress edit overlay. [draft] is `null` for a viewer (read-only) and
 * whenever the admin has not touched anything yet — the editor renders
 * [document] then, and `draft != null` IS the dirty flag. A save applies the
 * draft's edited fields onto a freshly fetched document (the web console's
 * GET-merge-PUT rule: a PUT section persists wholesale, so the merge is what
 * keeps concurrently-changed fields from being reset); on failure the draft
 * is dropped and the editor falls back to [document].
 */
data class SettingsBlock(
    val document: AllSettings,
    val draft: StreamingSettings? = null,
    val saving: Boolean = false,
)

/**
 * The auth card's facts: who this app is signed in as on the remote, and —
 * admin sessions only — the live web-session roster. [sessions] `null` means
 * "not fetched" (viewer sessions never fetch the admin-only roster; a failed
 * fetch keeps the previous value or null).
 */
data class IdentityBlock(
    /** Whether the remote requires a sign-in at all (GET /api/auth/status). */
    val authRequired: Boolean,
    /** Whether the held session cookie is live (GET /api/auth/session). */
    val authenticated: Boolean,
    /** `"admin"` | `"viewer"`; null = pre-role answer or signed-out — treated as admin. */
    val role: String?,
    val sessions: List<AuthSessionEntry>? = null,
    /** A revoke/logout round trip is in flight; the card's buttons idle. */
    val busy: Boolean = false,
    /** Arms the logout-confirm dialog. */
    val pendingLogout: Boolean = false,
)

/**
 * The audit card's page: newest-first entries, the trail's total, the limit
 * the current page was fetched with, and the paging flags — [exhausted]
 * once the page covers the trail (or a bigger limit stopped returning more,
 * the server's own cap guard).
 */
data class AuditBlock(
    val entries: List<RemoteAuditEntry>,
    val total: Int,
    val limit: Int,
    val loadingMore: Boolean = false,
    val exhausted: Boolean = false,
)

/** One-shot effects the tab executes outside the state tree: snackbars. */
sealed interface SystemTabEffect {

    /** The snackbar material, as the resource id to resolve in the UI. */
    data class Notice(
        @StringRes val messageRes: Int,
    ) : SystemTabEffect

    companion object {
        /** The exception taxonomy → the user-facing one-line verdict. */
        fun forError(error: CompanionApiException): Notice = when (error) {
            is CompanionApiException.Forbidden -> Notice(R.string.companion_system_error_forbidden)
            is CompanionApiException.Auth -> Notice(R.string.companion_system_error_auth)
            is CompanionApiException.Network -> Notice(R.string.companion_system_error_network)
            else -> Notice(R.string.companion_system_error_http)
        }
    }
}

/**
 * The stateless content's write surface — every user gesture the system tab
 * offers, as lambdas (the Robolectric test renders [SystemTabContent] with
 * recording lambdas and never builds a ViewModel).
 */
data class SystemTabActions(
    /** The whole-load failed — run the parallel section load again. */
    val onRetry: () -> Unit,
    /**
     * One settings control moved: the card hands over a transform (applied
     * onto the editor's base document); the VM gates viewers and maps the
     * result into the draft overlay.
     */
    val onEditSettings: (transform: (StreamingSettings) -> StreamingSettings) -> Unit,
    val onSaveSettings: () -> Unit,
    val onRevertSettings: () -> Unit,
    /** Revoke one web session by its token prefix (admin; fires immediately). */
    val onRevokeSession: (prefix: String) -> Unit,
    /** Arms/cancels the logout-confirm dialog (null = cancel). */
    val onRequestLogout: (Boolean) -> Unit,
    /** The logout dialog was confirmed. */
    val onConfirmLogout: () -> Unit,
    val onLoadMoreAudit: () -> Unit,
)

/** Stable compose test tags the system tab's own render test pokes. */
object SystemTabTags {
    /** The LazyColumn that hosts the four cards (the scroll seam for tests). */
    const val CARDS_LIST = "companion_system_cards_list"
}
