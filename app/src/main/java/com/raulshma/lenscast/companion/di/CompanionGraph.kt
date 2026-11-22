package com.raulshma.lenscast.companion.di

import android.content.Context
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.discovery.NsdDiscoveryClient
import com.raulshma.lenscast.companion.model.CompanionDevice
import com.raulshma.lenscast.companion.roster.CompanionCredentialStore
import com.raulshma.lenscast.companion.roster.DeviceRosterStore
import com.raulshma.lenscast.companion.roster.KeystoreCompanionCredentialCipher
import com.raulshma.lenscast.companion.session.DeviceSessionManager
import com.raulshma.lenscast.core.AppJson
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * The companion mode's sub-graph: the collaborators the viewing/controlling
 * half of the app resolves, composed here instead of growing [AppGraph] by
 * a dozen members — the camera half's graph stays untouched, and the device
 * screens reach everything through this one object.
 *
 * All members are lazy: the companion mode costs nothing until a device
 * screen (or the roster) is first entered, matching the app's
 * lazy-`val`-per-collaborator composition idiom. The app-lifetime [scope]
 * and the one [AppJson] instance are passed in rather than re-created, so
 * the companion's coroutines die with the process scope and its wire format
 * cannot drift from the app's.
 */
class CompanionGraph(
    context: Context,
    appScope: CoroutineScope,
    appJson: AppJson,
) {

    private val filesDir: File = context.filesDir
    private val scope: CoroutineScope = appScope
    private val appJson: AppJson = appJson

    /** The persisted paired-device roster; one JSON document under filesDir. */
    val roster: DeviceRosterStore by lazy {
        DeviceRosterStore(filesDir, appJson)
    }

    /** The encrypted per-device credentials, sealed by the Keystore cipher. */
    val credentials: CompanionCredentialStore by lazy {
        CompanionCredentialStore(filesDir, KeystoreCompanionCredentialCipher())
    }

    /** The pairing sheet's mDNS radar. */
    val discovery: NsdDiscoveryClient by lazy {
        NsdDiscoveryClient(context)
    }

    /** The per-device live-session registry. */
    val sessions: DeviceSessionManager by lazy {
        DeviceSessionManager(roster, credentials, scope)
    }

    /**
     * One API client per device, built per call around the passed snapshot —
     * the client is cheap (the OkHttp engine is shared per construction
     * site's device) and reads its credentials live from the store, so a
     * re-pair takes effect on the next request without rebuilding anything.
     */
    fun apiClient(device: CompanionDevice): LenscastApiClient =
        LenscastApiClient(device) { credentials.get(device.id) }

    /**
     * The camera dashboard's REMOTE console backend factory for one device —
     * the shape P3's NavigationGraph call site consumes
     * (`companion.consoleFactory(deviceId, context.applicationContext)` fed
     * to `viewModel(factory = …)`). Resolves every
     * [com.raulshma.lenscast.companion.console.DeviceConsoleViewModel]
     * collaborator the graph owns: the cached [DeviceSessionManager.session]
     * (the same live session the device screen rides), the roster role (the
     * write gate), and the stored-credential bit (the talkback cookie-gate
     * mapping). The viewer-local settings store comes off the app graph when
     * the host implements it (production always does); a bare context —
     * tests, a non-graph host — builds a standalone store over the same
     * process-wide DataStore file.
     */
    fun consoleFactory(
        deviceId: String,
        appContext: Context,
    ): com.raulshma.lenscast.companion.console.DeviceConsoleViewModel.Factory {
        val app = appContext.applicationContext
        val settingsStore =
            (app as? com.raulshma.lenscast.AppGraph)?.settingsDataStore
                ?: com.raulshma.lenscast.data.SettingsDataStore(app)
        return com.raulshma.lenscast.companion.console.DeviceConsoleViewModel.Factory(
            companion = this,
            session = sessions.session(deviceId),
            appContext = app,
            settingsDataStore = settingsStore,
        )
    }

    /**
     * The gallery's REMOTE backend for one device — the source factory beside
     * [consoleFactory] that the gallery/viewer routes consume when the route
     * carries the device argument. CACHED PER DEVICE: the gallery grid and
     * the media viewer are two ViewModels over one seam, and the pager index
     * only carries over if both read the same accumulated item list (the
     * local backends don't need this — this phone's store is already the
     * process-wide truth). Role-gated: the roster's paired role decides
     * [com.raulshma.lenscast.companion.console.RemoteGallerySource.canDelete]
     * (a viewer pairing would 403 the write); a device that left the roster
     * still builds a settled empty source.
     *
     * Visibility contract: the source rides plain REST reads — it never
     * starts or stops the device session (the console screen owns that
     * lifecycle), so a deep-linked gallery works without any live link, and
     * a visit beside a live console shares the same engine and trust
     * decision. Sources live for the process (bounded by roster size), but a
     * re-pair that changes the device's ENDPOINT or ROLE invalidates the
     * cached source and builds a fresh one: the client's base URL and engine
     * trust and the canDelete verdict are baked at build time, so reuse must
     * reflect both changes (an unchanged pairing — including the session
     * manager's routine lastSeenMs stamps — keeps reusing the cached seam).
     */
    fun gallerySource(deviceId: String): com.raulshma.lenscast.gallery.GallerySource =
        cachedSource(gallerySources, deviceId) { device ->
            com.raulshma.lenscast.companion.console.RemoteGallerySource(
                api = device?.let { apiClient(it) },
                canDelete = device != null && device.role != ROLE_VIEWER,
                scope = scope,
            )
        }

    /**
     * The pairing-key-guarded cache accessor every snapshot-bound source
     * factory shares: each entry stores the source TOGETHER with the pairing
     * key it was built from, and both are read and written under the cache's
     * own lock — a separately updated pairing table would be guarded by a
     * DIFFERENT map's lock than the family caches it annotates (they share
     * one table) and could pair a fresh source with a stale verdict. The
     * cached source is reused only while that attached key still matches: a
     * re-pair that changed the ENDPOINT or ROLE invalidates the cached source
     * and builds a fresh one (the baked base URL/engine trust and role
     * verdict must reflect both changes), while an unchanged pairing —
     * including the session manager's routine lastSeenMs stamps — keeps
     * reusing the cached seam. [keyExtra] folds non-roster dial facts into
     * the key (the settings source's session transport override — see
     * [settingsSource]).
     */
    private fun <T> cachedSource(
        cache: MutableMap<String, CachedSource<T>>,
        deviceId: String,
        keyExtra: () -> Any? = { null },
        build: (CompanionDevice?) -> T,
    ): T = synchronized(cache) {
        val device = roster.find(deviceId)
        val key = pairingKey(device) + keyExtra()
        val cached = cache[deviceId]
        if (cached != null && cached.key == key) {
            cached.source
        } else {
            val built = build(device)
            cache[deviceId] = CachedSource(built, key)
            built
        }
    }

    /** One cache entry: the source and the exact pairing key it was built from. */
    private class CachedSource<T>(val source: T, val key: List<Any?>)

    /**
     * The detection-event log's REMOTE backend for one device — the events
     * route's counterpart of [gallerySource], cached per device for the same
     * one-seam-per-device rule (and the same pairing-key invalidation — see
     * [cachedSource]). Rides the cached [DeviceSessionManager.session]
     * (the same live link the console screen drives — `start()` is
     * idempotent, so the source's presence never forks a second ladder),
     * with the roster role gating
     * [com.raulshma.lenscast.companion.console.RemoteEventsSource.canClear]
     * per the [com.raulshma.lenscast.streaming.web.RoleGatePolicy] verdict
     * the device itself would enforce.
     */
    fun eventsSource(deviceId: String): com.raulshma.lenscast.capture.EventsSource =
        cachedSource(eventsSources, deviceId) { device ->
            com.raulshma.lenscast.companion.console.RemoteEventsSource(
                session = sessions.session(deviceId),
                api = device?.let { apiClient(it) },
                canClear = device != null && device.role != ROLE_VIEWER,
                scope = scope,
            )
        }

    /**
     * The capture screen's REMOTE backend for one device — the capture
     * route's counterpart of [gallerySource]/[eventsSource], cached per
     * device for the same one-seam-per-device rule (and the same pairing-key
     * invalidation — see [cachedSource]). Rides plain REST
     * (interval + recording status polls on WhileSubscribed beats, writes on
     * the capture routes), so a deep-linked capture screen works without any
     * live link and never touches the session lifecycle. Role-gated: the
     * roster's paired role decides every write verdict
     * ([com.raulshma.lenscast.companion.console.RemoteCaptureSource]'s
     * canCapturePhoto/canControlRecording/canControlInterval — a viewer
     * pairing would 403 each), and the timelapse section is wire-degraded
     * hidden there (no timelapse carrier exists).
     */
    fun captureSource(deviceId: String): com.raulshma.lenscast.capture.CaptureSource =
        cachedSource(captureSources, deviceId) { device ->
            val canWrite = device != null && device.role != ROLE_VIEWER
            com.raulshma.lenscast.companion.console.RemoteCaptureSource(
                api = device?.let { apiClient(it) },
                canCapturePhoto = canWrite,
                canControlRecording = canWrite,
                canControlInterval = canWrite,
                scope = scope,
            )
        }

    /**
     * The automation screen's REMOTE backend for one device — the rule
     * editor's counterpart of [captureSource], cached per device (same
     * pairing-key invalidation — see [cachedSource]). Rides the
     * settings-shaped `GET/PUT /api/automation` document plus the test
     * routes (plain REST, no session lifecycle), with the roster role gating
     * [com.raulshma.lenscast.companion.console.RemoteAutomationSource.canEdit]
     * per the [com.raulshma.lenscast.streaming.web.RoleGatePolicy] verdict
     * the device itself would enforce (a viewer pairing reads the rules
     * read-only).
     */
    fun automationSource(deviceId: String): com.raulshma.lenscast.automation.AutomationSource =
        cachedSource(automationSources, deviceId) { device ->
            com.raulshma.lenscast.companion.console.RemoteAutomationSource(
                api = device?.let { apiClient(it) },
                canEdit = device != null && device.role != ROLE_VIEWER,
                scope = scope,
            )
        }

    /**
     * The settings screens' REMOTE backend for one device — the P6
     * counterpart of [automationSource], cached per device. Rides the
     * `GET/PUT /api/settings` document (camera + streaming sections) plus
     * `/api/auth/config`, with the roster role gating
     * [com.raulshma.lenscast.companion.console.RemoteSettingsSource.canEdit]
     * (a viewer pairing renders the screens read-only). The viewer-local
     * lanes bind the app graph's settings store — the SAME store the local
     * backend passes through — so this phone's viewfinder/theming prefs stay
     * this phone's even while the device-side sections ride the device (the
     * [consoleFactory] settings-store resolution rule). A device that left
     * the roster still builds a settled source (the wire defaults; the
     * viewer-local lanes live).
     *
     * Dial rule: the settings API rides the session's CURRENT client, not a
     * roster-snapshot client — a device that switched its listener scheme
     * (HTTPS on/off) re-announces a `transport` event and a live session
     * rebuilds its client in place, while the roster keeps the old scheme
     * (the session override is deliberately session-local). [sessionDialSnapshot]
     * folds that active dial into the cache key, so a scheme flip invalidates
     * the cached source and the screen re-dials the active scheme — no
     * console screen required ([DeviceSessionManager.session] hands back the
     * cached session whether or not a link is live).
     */
    fun settingsSource(
        deviceId: String,
        appContext: android.content.Context,
    ): com.raulshma.lenscast.settings.SettingsSource =
        cachedSource(settingsSources, deviceId, keyExtra = { sessionDialSnapshot(deviceId) }) { device ->
            val app = appContext.applicationContext
            val settingsStore =
                (app as? com.raulshma.lenscast.AppGraph)?.settingsDataStore
                    ?: com.raulshma.lenscast.data.SettingsDataStore(app)
            com.raulshma.lenscast.companion.console.RemoteSettingsSource(
                api = settingsDial(deviceId, device),
                canEdit = device != null && device.role != ROLE_VIEWER,
                localSettings = settingsStore,
                scope = scope,
            )
        }

    /**
     * The settings source's dial: the session's current client — it carries
     * the session-local transport override (a scheme flip rebuilds the
     * session's client in place; see [settingsSource]) — falling back to the
     * roster-snapshot client only when the session cannot hand one out (no
     * roster entry, no credentials yet). Reading the session's client neither
     * starts nor requires a live link: an idle session answers the
     * roster-scheme client, the [consoleFactory] resolver shape.
     */
    private fun settingsDial(deviceId: String, device: CompanionDevice?): LenscastApiClient? {
        if (device == null) return null
        return try {
            sessions.session(deviceId).api()
        } catch (_: IllegalStateException) {
            apiClient(device)
        }
    }

    /**
     * The dial fact the settings cache invalidates on: the device snapshot
     * the session's current client dials (its scheme reflects any live
     * transport override). Null when the session has no dialable device —
     * matching [settingsDial]'s roster fallback.
     */
    private fun sessionDialSnapshot(deviceId: String): CompanionDevice? =
        try {
            sessions.session(deviceId).api().device()
        } catch (_: IllegalStateException) {
            null
        }

    /**
     * The remote App-settings screen's diagnostics seam for one device — the
     * folded System-tab cards' backend, cached per device beside the other
     * sources (same pairing-key invalidation — see [cachedSource]).
     * Maintenance gate: the roster's ADMIN verdict (restart/reset and kick
     * are device-mutating lifecycle actions — a viewer pairing gets the
     * read-only cards and no maintenance affordances), stricter than the
     * settings source's write gate by design.
     */
    fun settingsDiagnostics(deviceId: String): com.raulshma.lenscast.companion.console.SettingsDiagnostics =
        cachedSource(settingsDiagnostics, deviceId) { device ->
            com.raulshma.lenscast.companion.console.RemoteDiagnosticsSource(
                api = device?.let { apiClient(it) },
                canManage = device != null && device.role != ROLE_VIEWER,
                scope = scope,
            )
        }

    /** The remote gallery backends, one per device, process-lifetime. */
    private val gallerySources =
        mutableMapOf<String, CachedSource<com.raulshma.lenscast.gallery.GallerySource>>()

    /**
     * The pairing facts a source bakes in: endpoint (host, port, scheme,
     * TLS trust) and the role gate. Deliberately NOT the whole device — the
     * session manager stamps lastSeenMs into the roster on routine
     * connect/offline transitions, and that churn must not reset the
     * sources' shared state.
     */
    private fun pairingKey(device: CompanionDevice?): List<Any?> =
        device?.let { listOf(it.host, it.port, it.useTls, it.tlsFingerprint, it.role) }
            ?: emptyList()

    /** The remote event backends, one per device, process-lifetime. */
    private val eventsSources =
        mutableMapOf<String, CachedSource<com.raulshma.lenscast.capture.EventsSource>>()

    /** The remote capture backends, one per device, process-lifetime. */
    private val captureSources =
        mutableMapOf<String, CachedSource<com.raulshma.lenscast.capture.CaptureSource>>()

    /** The remote automation backends, one per device, process-lifetime. */
    private val automationSources =
        mutableMapOf<String, CachedSource<com.raulshma.lenscast.automation.AutomationSource>>()

    /** The remote settings backends, one per device, process-lifetime. */
    private val settingsSources =
        mutableMapOf<String, CachedSource<com.raulshma.lenscast.settings.SettingsSource>>()

    /** The remote diagnostics backends, one per device, process-lifetime. */
    private val settingsDiagnostics =
        mutableMapOf<String, CachedSource<com.raulshma.lenscast.companion.console.SettingsDiagnostics>>()

    companion object {
        /** The roster role string that degrades pairing surfaces to read-only. */
        private const val ROLE_VIEWER = "viewer"
    }
}
