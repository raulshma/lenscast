package com.raulshma.lenscast.companion.console

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import com.raulshma.lenscast.R
import com.raulshma.lenscast.camera.SelfTimerRunner
import com.raulshma.lenscast.camera.UrlClipboard
import com.raulshma.lenscast.camera.CameraConsole
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.CameraSettings
import com.raulshma.lenscast.camera.model.CameraState
import com.raulshma.lenscast.camera.model.ConsoleCapabilities
import com.raulshma.lenscast.camera.model.CountdownState
import com.raulshma.lenscast.camera.model.GridStyle
import com.raulshma.lenscast.camera.model.LevelIndicatorPolicy
import com.raulshma.lenscast.camera.model.ProToolsPolicy
import com.raulshma.lenscast.camera.model.QuickSettingCatalog
import com.raulshma.lenscast.camera.model.QuickSettingEditorValue
import com.raulshma.lenscast.camera.model.QuickSettingType
import com.raulshma.lenscast.camera.model.RecordingToggle
import com.raulshma.lenscast.camera.model.SelfTimerMode
import com.raulshma.lenscast.camera.model.StreamKind
import com.raulshma.lenscast.camera.model.StreamStartOutcome
import com.raulshma.lenscast.camera.model.StreamStatus
import com.raulshma.lenscast.camera.model.StreamToggle
import com.raulshma.lenscast.capture.RecordingState
import com.raulshma.lenscast.capture.model.FlashMode
import com.raulshma.lenscast.capture.model.RecordingConfig
import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.AllSettings
import com.raulshma.lenscast.companion.client.dto.CommandResponse
import com.raulshma.lenscast.companion.client.dto.DeviceStatus
import com.raulshma.lenscast.companion.client.ws.TalkbackClient
import com.raulshma.lenscast.companion.client.ws.TalkbackSession
import com.raulshma.lenscast.companion.client.ws.TalkbackState
import com.raulshma.lenscast.companion.playback.AndroidWhepPeer
import com.raulshma.lenscast.companion.playback.AudioListener
import com.raulshma.lenscast.companion.playback.AudioListenerState
import com.raulshma.lenscast.companion.playback.HlsRung
import com.raulshma.lenscast.companion.playback.HlsSession
import com.raulshma.lenscast.companion.playback.MicTalkbackSource
import com.raulshma.lenscast.companion.playback.MjpegPlayer
import com.raulshma.lenscast.companion.playback.MjpegState
import com.raulshma.lenscast.companion.playback.PlaybackRung
import com.raulshma.lenscast.companion.playback.PlayerLadder
import com.raulshma.lenscast.companion.playback.RetryBudget
import com.raulshma.lenscast.companion.playback.WhepState
import com.raulshma.lenscast.companion.playback.WhepViewer
import com.raulshma.lenscast.companion.session.ConnectionState
import com.raulshma.lenscast.companion.session.DeviceSession
import com.raulshma.lenscast.core.MicAccess
import com.raulshma.lenscast.core.MicGate
import com.raulshma.lenscast.core.MicStartDecision
import com.raulshma.lenscast.core.NetworkQualityMonitor
import com.raulshma.lenscast.core.ThermalState
import com.raulshma.lenscast.data.SettingsDataStore
import com.raulshma.lenscast.streaming.AdaptiveBitrateController
import com.raulshma.lenscast.streaming.StreamingManager
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The camera dashboard's REMOTE [CameraConsole] backend: the same screen
 * seam [com.raulshma.lenscast.camera.CameraViewModel] serves locally, driven
 * instead by a paired LensCast device through its [DeviceSession]'s HTTP API
 * (one interface, two backends — the dashboard binds to the interface and
 * reads [capabilities]; it never knows which backend it is talking to).
 *
 * Every state flow is DERIVED truth, composed from three lanes:
 *
 * - the session's conflated status SSE ([DeviceSession.status]) →
 *   [cameraState] via [DeviceStatusMappers.cameraState], [streamStatus] via
 *   [DeviceStatusMappers.streamStatus], the control ranges, the thermal
 *   state, and the two live facts ([torchEnabled]/[zoomRatio]) that
 *   [DeviceStatusMappers.withLiveFacts] overlays onto the settings snapshot;
 * - the device's settings document (`GET /api/settings`, fetched once per
 *   CONNECTED edge and re-read after every PUT) → the device-side settings
 *   snapshot via [RemoteSettingsMapper.toCameraSettings];
 * - this phone's viewer-local [SettingsDataStore] lanes → grid, self-timer,
 *   preview toggle, pro-tool toggles, flash chip (a viewer preference with no
 *   device carrier — see [cycleFlashMode]).
 *
 * Writes port the companion live view's optimistic discipline verbatim: the
 * mirror flips first, the POST follows, and ANY failure reverts — status
 * frames never stomp an in-flight optimistic write (per-field AtomicBoolean
 * flags).
 * A `CompanionApiException.Forbidden` publishes the viewer-permissions notice
 * on [consoleEvents] and nothing else mutates.
 *
 * Ownership, per the session's contract: this ViewModel does NOT own the
 * [DeviceSession] — it starts it once if found IDLE (idempotent), mirrors its
 * flows, and follows the device shell's dispose contract through [dispose]
 * (the screen calls it; the session stays cached in the manager).
 * Composition visibility rides [setActive], which also mirrors
 * `session.setVisible` so the session's polls freeze exactly when the
 * console's do.
 *
 * Playback owns a [PlayerLadder] (WHEP → HLS → MJPEG) with the companion
 * live view's demote/cooldown logic, exposed on the CONCRETE class for the preview stage
 * ([com.raulshma.lenscast.companion.console.RemotePreviewStage]) — the
 * interface stays render-agnostic. Talkback and the live-audio listen lane
 * are likewise concrete-surface members: they are companion affordances, not
 * dashboard-interface ones.
 */
class DeviceConsoleViewModel(
    private val session: DeviceSession,
    /** The session's API client; null (no roster entry) degrades every remote call to a no-op. */
    private val api: LenscastApiClient?,
    /**
     * The per-command client resolver. The [api] above is the construction-
     * time snapshot — a transport switch (or re-pair) rebuilds the session's
     * client in place ([DeviceSession.api]), so every remote call resolves
     * the CURRENT client through this seam instead of commanding through the
     * stale one. Defaults to the snapshot (tests inject the client directly);
     * production resolves `session.api()` per call, null (no roster entry or
     * credentials) degrading to the same no-op as the snapshot.
     */
    private val clientResolver: () -> LenscastApiClient? = { api },
    /**
     * The roster role's write gate (the `canControl` capability and every
     * command's guard) — the CONSTRUCTION-TIME snapshot; per-command reads
     * resolve through [canControlResolver] below.
     */
    private val canControl: Boolean,
    /**
     * The per-command role resolver, the [clientResolver] precedent: the
     * snapshot above bakes the roster at construction, but a RETAINED
     * console can outlive a re-pair that moved the device's role (admin ↔
     * viewer) — production re-reads the roster per command (and at the bind
     * points, see [refreshCanControl]) so a stale write gate never survives
     * the re-bind; tests inject the snapshot directly.
     */
    private val canControlResolver: () -> Boolean = { canControl },
    /** This phone's viewer-local preferences (grid, self-timer, preview, pro-tool toggles). */
    private val settingsDataStore: SettingsDataStore,
    /** This phone's context — clipboard, toasts, the mic permission. Never the device's. */
    private val appContext: Context,
    /** The state/effects scope (Main.immediate in production). */
    private val scope: CoroutineScope,
    /** The playback/audio lanes' home scope; cancelled on [onCleared]. */
    private val playerScope: CoroutineScope,
    /** The playback ladder decision math (WHEP → HLS → MJPEG, cooldowns). */
    private val ladder: PlayerLadder = PlayerLadder(),
    private val timings: ConsoleTimings = ConsoleTimings(),
    /** The pacing seam (tests advance a fake clock; production sleeps). */
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
    private val whepFactory: (LenscastApiClient) -> WhepViewer,
    private val hlsFactory: (LenscastApiClient) -> HlsSession,
    private val mjpegFactory: (LenscastApiClient) -> MjpegPlayer,
    private val audioFactory: (LenscastApiClient) -> AudioListener,
    private val talkbackConnect: (LenscastApiClient) -> TalkbackSession,
    /** The mic source per hold; null in tests that only exercise the uplink dial. */
    private val micFactory: (() -> MicTalkbackSource)? = null,
    /** This phone's mic grant, re-read live at every talkback consult. */
    private val micGranted: () -> Boolean = { MicAccess.isGranted(appContext) },
    /** The shared warning/failure toast sink (tests record instead of popping). */
    private val toast: (String) -> Unit = { message ->
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    },
    /** Whether the pairing stored a token/basic secret (no session cookie → talkback gate). */
    private val hasStoredCredential: Boolean = true,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * The countdown clock for the self-timer runner — the production
     * monotonic elapsedRealtime; injectable because Robolectric freezes
     * SystemClock (tests pass a real-time clock instead, the live tab's
     * injectable-pacing precedent).
     */
    private val selfTimerElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    /**
     * The settings-write boundary with the paired device's settings-screens
     * source ([RemoteSettingsSource]): when present, [putSettingsSection]'s
     * full GET-merge-PUT-confirm sequence runs inside it, so a dashboard
     * quick-edit serializes with the source's full-section writers through
     * the SAME write mutex — both sides PUT whole sections, and the server's
     * key-level merge cannot prevent two interleaved chains from losing an
     * update. Null keeps the console's own [settingsLaneMutex] only (the
     * historical behavior — tests inject nothing). Deadlock contract: kotlinx
     * Mutex is not reentrant, so the gated block dials the client directly
     * and must never enter a [RemoteSettingsSource] write path (it would
     * re-acquire the mutex it already holds). Wired from the Factory off the
     * graph's cached settings source — the [canControlResolver] precedent
     * (a defaulted seam production supplies and tests omit).
     */
    private val settingsWriteGate: (suspend (suspend () -> Unit) -> Unit)? = null,
) : ViewModel(), CameraConsole {

    // ── capabilities ──
    // The remote verdict table. Everything a backend cannot promise is OFF by
    // default (ConsoleCapabilities.remote); what IS on:
    //  - tapToFocus: POST /api/camera/focus honors normalized (0..1) x/y.
    //  - canControl: the roster role's verdict (a viewer pairing degrades
    //    every write even on a fully-featured device — RoleGatePolicy).
    //  - talkback/liveAudio: RoleGatePolicy's split keeps BOTH viewer-
    //    allowed — the talkback uplink is the doorbell-intercom write the
    //    policy explicitly allows, and /audio is a plain authed read — so
    //    neither is role-gated (only the state writes are).
    //  - selfTimer: the countdown is viewer-local (SelfTimerRunner); the
    //    Fire POSTs /api/capture like any immediate press.
    //  - pip: the preview is this stage's renderable surface.
    // OFF by default and staying off:
    //  - flashControl: the flash mode is a viewer-local pref with no wire
    //    carrier for /api/capture (see cycleFlashMode) — a chip that cannot
    //    move a photo's flash must not render.
    // Constant, like the local backend's — EXCEPT canControl: the roster
    // role's verdict re-reads at the bind points ([setActive] activation and
    // the CONNECTED edge), so a retained console re-paired under a different
    // role flips its write affordances with it ([refreshCanControl]). The
    // device probe cannot move any other verdict (the wire answers, not
    // offers, each feature).
    private val _capabilities = MutableStateFlow(capabilitiesFor(canControlResolver()))
    override val capabilities: StateFlow<ConsoleCapabilities> = _capabilities.asStateFlow()

    /** The capability row for the current role verdict (the one mutable flag). */
    private fun capabilitiesFor(canControl: Boolean): ConsoleCapabilities =
        ConsoleCapabilities.remote(
            tapToFocus = true,
            canControl = canControl,
            talkback = true,
            liveAudio = true,
            selfTimer = true,
            pip = true,
        )

    /**
     * The re-pair seam: re-reads the roster role into the write gate — the
     * capability verdict here and every command guard below resolve through
     * [canControlResolver] — called at the bind points where a retained
     * console can have received a re-paired device ([setActive] activation,
     * the CONNECTED edge).
     */
    private fun refreshCanControl() {
        val allowed = canControlResolver()
        if (_capabilities.value.canControl != allowed) {
            _capabilities.value = capabilitiesFor(allowed)
        }
    }

    // ── session-derived state ──

    private val _cameraState =
        MutableStateFlow(DeviceStatusMappers.cameraState(session.connectionState.value))
    override val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _streamStatus = MutableStateFlow(StreamStatus())
    override val streamStatus: StateFlow<StreamStatus> = _streamStatus.asStateFlow()

    private val _thermalState = MutableStateFlow(ThermalState.NORMAL)
    override val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    // The wifi banner warns a LOCAL server host that no wifi means no
    // viewers; the remote device just answered over the network, so the
    // banner never applies — held true to keep CameraDashboardPolicy's
    // shouldShowWifiBanner verdict false.
    override val wifiConnected: StateFlow<Boolean> = MutableStateFlow(true)

    // The ABR chip renders only when enabled; the remote backend does not
    // drive this phone's encoder, so the disabled default keeps the chip
    // hidden (the device's own adaptiveBitrate status block is the device
    // console's concern, not the dashboard's).
    override val adaptiveBitrateState: StateFlow<AdaptiveBitrateController.AdaptiveState> =
        MutableStateFlow(
            AdaptiveBitrateController.AdaptiveState(
                enabled = false,
                qualityLevel = NetworkQualityMonitor.NetworkQualityLevel.EXCELLENT,
                currentQuality = 0,
                targetQuality = 0,
                currentFps = 0,
                targetFps = 0,
                estimatedBandwidthKbps = 0,
                minClientThroughputKbps = 0,
                activeClients = 0,
            ),
        )

    // The dashboard's connection-quality card reads THIS server's per-client
    // telemetry; no analysis/network stats cross the remote API for this
    // phone, so null (the card's "nothing to show" value).
    override val connectionQualityStats:
        StateFlow<NetworkQualityMonitor.NetworkStatsSnapshot?> = MutableStateFlow(null)

    // ── settings: the device document ⊕ the status lane's live facts ──

    // The GET-derived device snapshot (null until the first CONNECTED fetch
    // lands); the published flow is withLiveFacts(doc, torch, zoom).
    private val _settingsDoc = MutableStateFlow<CameraSettings?>(null)
    private val _settings = MutableStateFlow(CameraSettings())
    override val settings: StateFlow<CameraSettings> = _settings.asStateFlow()

    // Per-field optimistic-write flags — the companion live view's optimistic
    // pattern verbatim:
    // while set, the status lane's fresh truth for THAT field does not stomp
    // the optimistic mirror; the flag clears when the control's write owner
    // releases (see the writers below) and the next status frame reconciles.
    private val torchInFlight = AtomicBoolean(false)
    private val zoomInFlight = AtomicBoolean(false)
    private val webStreamInFlight = AtomicBoolean(false)
    private val rtspStreamInFlight = AtomicBoolean(false)

    /**
     * The post-write confirmation holds: a zoom/torch write that completed
     * but whose demand status read FAILED arms ITS control's hold (only the
     * control whose write was accepted — a failed stream confirmation arms
     * neither), so the status lane's (possibly pre-write) SSE frames cannot
     * clobber the accepted value with a stale sample. The FIRST non-fresh
     * frame after arming is one-frame grace (it keeps the held value and
     * clears the hold); later frames reconcile normally, and a demand read
     * that lands dissolves only ITS lane's hold ([refreshLiveStatus] /
     * [applyStatusSnapshot]).
     */
    @Volatile private var zoomAcceptedPending = false
    @Volatile private var torchAcceptedPending = false

    /** The command lane a demand status read confirms (see [refreshLiveStatus]). */
    private enum class ConfirmedLane { TORCH, ZOOM, STREAM }

    // The confirmed live facts: the torch/zoom value of the last status frame
    // this console actually APPLIED (a deflected fact — in-flight write, hold,
    // fence — is not an observation; the last applied one stands), so the doc
    // lane's republishes ([publishSettings]) keep the confirmed value instead
    // of regressing to the pre-write sample the session's status flow may
    // still hold. Every applied frame re-records the fact (newer device
    // status advances it); [lastAppliedSseFrame] is the provenance mark that
    // tells a doc-lane publish an unapplied (newer) session sample from the
    // stale one the confirmation outranks.
    @Volatile private var confirmedTorch: Boolean? = null
    @Volatile private var confirmedZoom: Float? = null
    @Volatile private var lastAppliedSseFrame: DeviceStatus? = null

    // ── per-control single write owners (torch / zoom) ──
    // Each control owns ONE writer coroutine: the entry points only record
    // the latest target and stick the optimistic mirror; the writer drains
    // it, so intermediate targets coalesce, attempts never interleave, and a
    // stale completion or failure can neither release ownership early,
    // reconcile the status lane, nor roll back a newer target. The pending
    // slots and running flags are the ownership hand-off: an entry that
    // loses the claim leaves its target for the running writer; a writer
    // that drains empty re-checks the slot after clearing the flag (and
    // re-claims it) so a racing entry's target is never orphaned.
    private val pendingTorchTarget = AtomicReference<Boolean?>(null)
    private val torchWriterRunning = AtomicBoolean(false)

    /** The rollback anchor for the torch train: the pre-train device fact. */
    @Volatile private var torchFallback: Boolean = false

    private val pendingZoomTarget = AtomicReference<Float?>(null)
    private val zoomWriterRunning = AtomicBoolean(false)

    /** The rollback anchor for the zoom train: the pre-train device ratio. */
    @Volatile private var zoomFallback: Float = 1f

    // The command-generation fences (the recording poll's stamp-and-reject
    // idiom, per control lane): every zoom/torch/stream command dispatch
    // bumps its lane, and a demand status read stamps all three before its
    // GET — a lane commanded again while the read flew is FENCED, its
    // (pre-command) answer neither publishing nor dissolving anything over
    // the newer command's state ([refreshLiveStatus]).
    private val zoomCommandGeneration = AtomicLong(0)
    private val torchCommandGeneration = AtomicLong(0)
    private val streamCommandGeneration = AtomicLong(0)

    // Whole-document guard for quick-setting writes: their optimistic value
    // spans every wire-carried field, so the doc lane (the POST-write's own
    // re-read) may not republish under it. Cleared with the write's finally.
    private val settingsWriteInFlight = AtomicBoolean(false)

    /**
     * One queued quick-setting edit. The INSTANCE is the edit's identity: a
     * write's completion removes its entry conditionally
     * ([ConcurrentHashMap.remove] matches the instance), so a stale write
     * settling under a newer edit of the same type cannot drop the newer
     * pending value (nor republish its own value as if still pending).
     */
    private class PendingQuickEdit(val editorValue: QuickSettingEditorValue)

    /**
     * The quick-setting edits whose write is not settled yet (type → pending
     * edit). [publishSettings] re-applies these on EVERY publication, so a
     * doc-lane refresh landing mid-write carries the edited values instead of
     * wiping them; a write's completion removes ONLY its own entry before
     * republishing (a newer frame-rate edit stays visible while an older ISO
     * write settles). Main-thread-confined (the UI entry and [scope] ride
     * Main.immediate); concurrent-map out of belt-and-braces.
     */
    private val pendingQuickEdits = ConcurrentHashMap<QuickSettingType, PendingQuickEdit>()

    /**
     * Serializes the document lane's reads and writes ([fetchSettings]' GETs
     * vs [putSettingsSection]'s GET-merge-PUT): a connect-time read that
     * started before a committed edit can never publish over it — whichever
     * sequence acquires the lock second observes (and republishes) the newer
     * document, so the latest confirmed settings stay the visible result.
     */
    private val settingsLaneMutex = Mutex()

    private val _lastServerError = MutableStateFlow<String?>(null)

    /**
     * The whole-server start failure line — written only by the stream
     * outcome mapping (kind-null failures; the output failures ride toasts),
     * cleared by the next successful start. Same semantics as the local
     * backend's field of the same name.
     */
    override val lastServerError: StateFlow<String?> = _lastServerError.asStateFlow()

    // ── capture truth ──

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    override val isRecording: StateFlow<Boolean> = _recordingState
        .map { it is RecordingState.Recording }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The device-reported recording elapsed seconds — refreshed at the
     * recording poll's cadence (5 s), not a local tick clock: the wire
     * carries the authoritative count, and interpolating locally would drift
     * from the device's own readout.
     */
    private val _recordingElapsedSeconds = MutableStateFlow(0)
    override val recordingElapsedSeconds: StateFlow<Int> = _recordingElapsedSeconds.asStateFlow()

    /**
     * The recording lane's generation: bumped by every recording command, so
     * a poll read that started before the command (its GET was answered from
     * the pre-command device state) discards its result instead of stomping
     * the command's own verdict when it completes afterwards.
     */
    private val recordingPollGeneration = AtomicLong(0)

    /**
     * The recording command gate: one toggle at a time, claimed BEFORE the
     * command coroutine launches, so a double-tap's second tap returns
     * instead of racing the first command's POST with a stale-state verdict.
     * Cleared when the command settles on either verdict.
     */
    private val recordingToggleInFlight = AtomicBoolean(false)

    // ── lenses ──

    private val _availableLenses = MutableStateFlow<List<CameraLensInfo>>(emptyList())
    override val availableLenses: StateFlow<List<CameraLensInfo>> = _availableLenses.asStateFlow()

    private val _selectedLensIndex = MutableStateFlow(0)
    override val selectedLensIndex: StateFlow<Int> = _selectedLensIndex.asStateFlow()

    /**
     * The lens lane's generation: a selection command bumps it, so an
     * enumeration read that dialed before the command (its GET answered from
     * the pre-selection device state) discards BOTH state writes instead of
     * overwriting the confirmed selection when it completes afterwards. The
     * recording poll's stamp-and-reject idiom.
     */
    private val lensReadGeneration = AtomicLong(0)

    // The control ranges: the mappers' documented fallbacks until the status
    // lane's live ranges land (the same "assumed span" the local side
    // publishes before device truth arrives).
    private val _availableIsoRange =
        MutableStateFlow<ClosedRange<Int>>(DeviceStatusMappers.isoRange(null))
    override val availableIsoRange: StateFlow<ClosedRange<Int>> = _availableIsoRange.asStateFlow()

    private val _availableZoomRange =
        MutableStateFlow<ClosedFloatingPointRange<Float>>(DeviceStatusMappers.zoomRange(null))
    override val availableZoomRange: StateFlow<ClosedFloatingPointRange<Float>> =
        _availableZoomRange.asStateFlow()

    private val _availableExposureRange =
        MutableStateFlow<ClosedRange<Int>>(DeviceStatusMappers.exposureCompensationRange(null))
    override val availableExposureRange: StateFlow<ClosedRange<Int>> =
        _availableExposureRange.asStateFlow()

    // ── viewer-local lanes (this phone's preferences, the Settings Store) ──

    override val showPreview: StateFlow<Boolean> = settingsDataStore.showPreview
    override val gridStyle: StateFlow<GridStyle> = settingsDataStore.gridStyle
    override val histogramEnabled: StateFlow<Boolean> = settingsDataStore.histogramEnabled
    override val zebrasEnabled: StateFlow<Boolean> = settingsDataStore.zebrasEnabled
    override val peakingEnabled: StateFlow<Boolean> = settingsDataStore.peakingEnabled

    /** The persisted main-shutter flash mode (viewer-local; see [cycleFlashMode]). */
    override val flashModeSetting: StateFlow<FlashMode> = settingsDataStore.flashMode

    /** The persisted self-timer mode — the countdown's source of truth. */
    val selfTimer: StateFlow<SelfTimerMode> = settingsDataStore.selfTimer

    // Local-only feeds: no analysis frames and no motion-sensor access cross
    // the remote API; the overlays stay unrendered (their real gates are the
    // capabilities' proToolsOverlays/spiritLevel, both off).
    override val proToolsFrame: StateFlow<ProToolsPolicy.ProToolsFrame?> = MutableStateFlow(null)
    override val levelLineState: StateFlow<LevelIndicatorPolicy.LevelLineState?> =
        MutableStateFlow(null)

    // The self-timer machinery — the SHARED runner the local backend uses,
    // so the countdown behavior is bit-identical over both backends. Fire
    // launches the capture POST (the runner's coroutine only walks ticks).
    private val selfTimerRunner = SelfTimerRunner(
        scope = scope,
        onFire = { scope.launch { capturePhoto() } },
        elapsedMs = selfTimerElapsedMs,
    )
    override val selfTimerSecondsRemaining: StateFlow<Int?> = selfTimerRunner.secondsRemaining

    /** The countdown state machine (Idle → Counting → Firing → Idle), for the overlay. */
    val selfTimerState: StateFlow<CountdownState> = selfTimerRunner.state

    // ── permission state ──
    // The permission screens only render on the local backend; this flow
    // exists so the interface's audio gate reflects THIS phone's mic (the
    // talkback lane still needs it). Refreshed live at every talkback start.

    private val _hasAudioPermission = MutableStateFlow(false)
    override val hasAudioPermission: StateFlow<Boolean> = _hasAudioPermission.asStateFlow()

    override val notificationPermissionAsked: StateFlow<Boolean> = MutableStateFlow(false)

    // ── one-shot events ──

    private val _captureFlashEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val captureFlashEvents: SharedFlow<Unit> = _captureFlashEvents.asSharedFlow()

    private val _consoleEvents = Channel<ConsoleEvent>(Channel.BUFFERED)

    /** Control-failure notices (forbidden / re-pair / failed) — the host renders a snackbar. */
    val consoleEvents: kotlinx.coroutines.flow.Flow<ConsoleEvent> = _consoleEvents.receiveAsFlow()

    // ── init / lifecycle ──

    private val active = AtomicBoolean(false)

    /**
     * Whether the persisted self-timer mode is confirmed read from disk —
     * the dashboard's Ready gate (see [observeSelfTimerHydration]).
     */
    @Volatile private var selfTimerModeLoaded = false

    init {
        // Idempotent: a session the shell already started ignores this; a
        // console constructed before the shell's effect climbs the link.
        if (session.connectionState.value == ConnectionState.IDLE) {
            session.start()
        }
        observeConnection()
        observeStatus()
        observeSelfTimerMode()
        observeSelfTimerHydration()
    }

    /**
     * The device shell's dispose contract (the hosting screen calls it):
     * freeze the session's lanes, then stop the link. The session object
     * itself stays cached in the manager — a re-entry rides the same
     * instance. Idempotent.
     */
    fun dispose() {
        session.setVisible(false)
        session.stop()
    }

    /**
     * Composition visibility: true enters playback at the best eligible rung
     * and starts the polls; false stops both and closes the audio/talkback
     * lanes. Mirrors `session.setVisible` so the session's own polls and
     * ladder freeze exactly with the console's.
     */
    fun setActive(isActive: Boolean) {
        if (active.get() == isActive) return
        active.set(isActive)
        session.setVisible(isActive)
        if (isActive) {
            // Re-bind: a retained console may be seeing a re-paired device
            // whose roster role moved — re-read the write gate before any
            // affordance renders or command guards run.
            refreshCanControl()
            if (currentApi() == null) return
            ladder.reset(PlaybackRung.WHEP)
            startActiveRung()
            startPolls()
            // The connect-time truth fetches, in case the CONNECTED edge
            // fired before this console went active (or was missed).
            scope.launch {
                fetchSettings()
                fetchLenses()
                pollRecording()
            }
        } else {
            stopRungs()
            cancelPolls()
            stopAudio()
            endTalk()
            // The dashboard left the screen. The ViewModel is back-stack
            // scoped (navigation to the gallery retains it — onCleared does
            // not run), so an armed countdown would keep ticking and fire
            // its capture POST after navigation. Nothing here promises a
            // background finish — cancel it with the rest of the lanes.
            selfTimerRunner.cancelCountdown()
        }
    }

    override fun onCleared() {
        active.set(false)
        stopRungs()
        cancelPolls()
        stopAudio()
        endTalk()
        selfTimerRunner.dispose()
        val reason = CancellationException("DeviceConsoleViewModel cleared")
        playerScope.cancel(reason)
        scope.cancel(reason)
    }

    // ── session mirrors ──

    /**
     * The client every remote call rides: resolved per command through
     * [clientResolver] so a transport switch mid-session (the session
     * rebuilds its client in place) commands through the currently selected
     * transport, never the construction-time snapshot.
     */
    private fun currentApi(): LenscastApiClient? = clientResolver()

    private fun observeConnection() {
        scope.launch {
            var previous = session.connectionState.value
            session.connectionState.collect { next ->
                publishCameraState(next)
                val wasConnected = previous == ConnectionState.CONNECTED
                previous = next
                if (next == ConnectionState.CONNECTED && !wasConnected) {
                    // (Re)connected: pull the document truth and reconcile the
                    // recording lane immediately; the reconnect EDGE (any
                    // non-CONNECTED → CONNECTED) also re-runs the ladder from
                    // the top with cooldowns honored — the live tab's verdict.
                    scope.launch {
                        fetchSettings()
                        fetchLenses()
                        pollRecording()
                    }
                    // A re-pair can land behind a reconnect: same re-bind as
                    // [setActive] activation.
                    refreshCanControl()
                    if (active.get()) {
                        ladder.reset(PlaybackRung.WHEP)
                        startActiveRung()
                        // The polls ride the active console: a console
                        // activated while the link was down ([setActive]
                        // found no client and returned early) has no poll
                        // job — restart it here. startPolls is
                        // cancel-then-relaunch, so an already-running job is
                        // replaced, never duplicated.
                        startPolls()
                    }
                }
            }
        }
    }

    private fun observeStatus() {
        scope.launch {
            session.status.collect { status ->
                // An SSE push may be a sample taken BEFORE an in-flight or
                // just-completed write — never fresh.
                if (status != null) applyStatusSnapshot(status, fresh = false)
            }
        }
    }

    /**
     * The persisted self-timer mode is the countdown's source of truth:
     * flipping it to OFF mid-countdown cancels the countdown, whatever
     * setter path wrote the mode — the same collector the local backend
     * runs (the settings screen writes the same store over both backends).
     */
    private fun observeSelfTimerMode() {
        scope.launch {
            settingsDataStore.selfTimer.collect { mode ->
                if (mode == SelfTimerMode.OFF &&
                    selfTimerRunner.state.value is CountdownState.Counting
                ) {
                    selfTimerRunner.cancelCountdown()
                }
            }
        }
    }

    /**
     * The store's default-first race (CONTEXT.md "Store Hydration"): the
     * [selfTimer] flow replays the OFF placeholder until DataStore's first
     * disk read lands, so a dashboard that opened Ready inside that window
     * could take a shutter press reading OFF and bypass a saved delay. The
     * disk twin is the one hydration signal: [publishCameraState] holds
     * Ready at Initializing until it completes, and the completion
     * re-publishes the current connection truth — a transition that landed
     * mid-window went out through the closed gate, and the connection
     * collector never re-fires on its own (a content heuristic on the
     * replayed value would wedge the gate shut).
     */
    private fun observeSelfTimerHydration() {
        scope.launch {
            settingsDataStore.selfTimerFromDisk()
            selfTimerModeLoaded = true
            publishCameraState(session.connectionState.value)
        }
    }

    /**
     * One connection frame out to the dashboard's camera state. Ready is
     * held at Initializing until the persisted self-timer mode is confirmed
     * loaded — the other states are honest regardless of the store's
     * hydration.
     */
    private fun publishCameraState(connection: ConnectionState) {
        val mapped = DeviceStatusMappers.cameraState(connection)
        _cameraState.value =
            if (mapped is CameraState.Ready && !selfTimerModeLoaded) {
                CameraState.Initializing
            } else {
                mapped
            }
    }

    /**
     * One status frame in. [fresh] marks the frame's provenance: a demand
     * read ([refreshLiveStatus]) is the device's post-write truth for ITS
     * [confirmedLane] only — the read dissolves that lane's accepted-pending
     * hold, and only when the lane's command generation still matches (a
     * fenced lane's values neither publish nor dissolve; the newer command's
     * own confirmation reconciles it). An SSE push is not fresh: an armed
     * hold gets the one-frame grace instead of applying.
     */
    private fun applyStatusSnapshot(
        status: DeviceStatus,
        fresh: Boolean,
        confirmedLane: ConfirmedLane? = null,
        zoomFenced: Boolean = false,
        torchFenced: Boolean = false,
        streamFenced: Boolean = false,
    ) {
        // Stream mirrors, per-field guarded: an in-flight output toggle's
        // optimistic bit survives the frame (the optimistic-write pattern).
        // A stream-fenced frame's output values predate a newer stream
        // command — skipped wholesale: the in-flight guards cannot protect a
        // toggle that already completed, and the newer command's own demand
        // read reconciles the outputs.
        if (!streamFenced) {
            val mapped = DeviceStatusMappers.streamStatus(status.streaming)
            _streamStatus.value = mapped.copy(
                isWebActive = if (webStreamInFlight.get()) _streamStatus.value.isWebActive else mapped.isWebActive,
                isRtspActive = if (rtspStreamInFlight.get()) _streamStatus.value.isRtspActive else mapped.isRtspActive,
            )
        }
        _thermalState.value = DeviceStatusMappers.thermalState(status.thermal)
        _availableIsoRange.value = DeviceStatusMappers.isoRange(status.isoRange)
        _availableZoomRange.value = DeviceStatusMappers.zoomRange(status.zoomRange)
        _availableExposureRange.value =
            DeviceStatusMappers.exposureCompensationRange(status.exposureCompensationRange)
        var holdTorch = false
        var holdZoom = false
        if (fresh) {
            when (confirmedLane) {
                ConfirmedLane.TORCH -> if (!torchFenced) torchAcceptedPending = false
                ConfirmedLane.ZOOM -> if (!zoomFenced) zoomAcceptedPending = false
                ConfirmedLane.STREAM, null -> Unit
            }
            // A hold on any OTHER lane stays armed (its accepted write still
            // awaits ITS confirmation), and a fenced lane's values do not
            // publish — both keep the mirror below.
            holdTorch = torchAcceptedPending || torchFenced
            holdZoom = zoomAcceptedPending || zoomFenced
        } else {
            if (torchAcceptedPending) {
                torchAcceptedPending = false
                holdTorch = true
            }
            if (zoomAcceptedPending) {
                zoomAcceptedPending = false
                holdZoom = true
            }
        }
        // The applied facts become the confirmed live truth (see the fields
        // above); an SSE frame is also the doc-lane provenance mark. A
        // deflected fact is not an observation — the last applied one stands.
        if (!fresh) lastAppliedSseFrame = status
        if (!holdTorch && !torchInFlight.get()) confirmedTorch = status.torchOn
        if (!holdZoom && !zoomInFlight.get()) {
            status.zoomRatio?.toFloat()?.let { confirmedZoom = it }
        }
        publishSettings(status, fresh, holdTorch, holdZoom)
    }

    /**
     * Republishes the settings snapshot. A FRAME caller ([status] non-null —
     * [applyStatusSnapshot]) overlays the frame's own live torch/zoom truth
     * (the frame is the provenance), each field skipped while its own
     * optimistic write is in flight (the flags above) or its hold/fence
     * defers it. The doc-lane callers ([status] null — a settings fetch, a
     * settled write) overlay the CONFIRMED live facts: the session's status
     * sample is used only when it is a frame this console has not applied
     * yet (newer device status, handed over by conflation before the
     * collector ran) — a completed write's confirmed value must not regress
     * to the older pre-write sample the session still holds. Skipped
     * wholesale while a quick-setting write owns the document. Whatever the
     * lane, the pending quick edits re-apply on top, so a refresh landing
     * mid-write carries the edited values.
     */
    private fun publishSettings(
        status: DeviceStatus? = null,
        fresh: Boolean = false,
        holdTorch: Boolean = torchAcceptedPending,
        holdZoom: Boolean = zoomAcceptedPending,
    ) {
        if (settingsWriteInFlight.get()) return
        val doc = _settingsDoc.value ?: return
        val torch = when {
            torchInFlight.get() || holdTorch -> _settings.value.torchEnabled
            status != null -> status.torchOn
            else -> overlayLiveFact(confirmed = confirmedTorch, fallback = doc.torchEnabled) { it.torchOn }
        }
        val zoom = when {
            zoomInFlight.get() || holdZoom -> _settings.value.zoomRatio
            status != null -> status.zoomRatio?.toFloat() ?: doc.zoomRatio
            else -> overlayLiveFact(
                confirmed = confirmedZoom,
                fallback = doc.zoomRatio,
            ) { it.zoomRatio?.toFloat() }
        }
        var published = DeviceStatusMappers.withLiveFacts(doc, torch, zoom.toDouble())
        pendingQuickEdits.forEach { (type, edit) ->
            published = QuickSettingCatalog.descriptorFor(type).write(published, edit.editorValue)
        }
        _settings.value = published
    }

    /**
     * One live fact's doc-lane overlay (see [publishSettings]): the
     * session's sample outranks the confirmed value only when it is a frame
     * this console has not applied (identity differs from the last applied
     * SSE frame — a newer sample); otherwise the confirmed live fact (the
     * last applied observation, demand-read or SSE) stands, and with no
     * observation at all the document's value does.
     */
    private fun <T> overlayLiveFact(
        confirmed: T?,
        fallback: T,
        fact: (DeviceStatus) -> T?,
    ): T {
        val sample = session.status.value
        return when {
            sample != null && sample !== lastAppliedSseFrame -> fact(sample) ?: confirmed ?: fallback
            confirmed != null -> confirmed
            sample != null -> fact(sample) ?: fallback
            else -> fallback
        }
    }

    // ── truth fetches (CONNECTED edge + activate) ──

    private suspend fun fetchSettings() {
        val client = currentApi() ?: return
        settingsLaneMutex.withLock {
            val doc = try {
                client.getSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return // a read: transient failure keeps the last document
            }
            _settingsDoc.value = RemoteSettingsMapper.toCameraSettings(doc.camera)
            publishSettings()
        }
    }

    private suspend fun fetchLenses() {
        val client = currentApi() ?: return
        // Generation-stamped: a selection command bumps the generation, so a
        // read that dialed before the command is discarded instead of
        // overwriting the confirmed selection with the pre-selection truth.
        val generation = lensReadGeneration.get()
        val response = try {
            client.getLenses()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // a read: transient failure keeps the last list
        }
        if (generation != lensReadGeneration.get()) return
        // MappedLens → CameraLensInfo: the placeholder selector is dead
        // weight a remote backend carries only to satisfy the model type
        // (it never binds a local camera — the P2a mapper note).
        _availableLenses.value = response.lenses.map { mapped ->
            val lens = DeviceStatusMappers.lens(mapped)
            CameraLensInfo(
                id = lens.id,
                labelKind = lens.labelKind,
                lensFacing = lens.lensFacing,
                focalLength = lens.focalLength,
                cameraSelector = androidx.camera.core.CameraSelector.Builder().build(),
                physicalCameraId = lens.physicalCameraId,
                zoomRatio = lens.zoomRatio,
            )
        }
        _selectedLensIndex.value = response.selectedIndex
    }

    /**
     * The demand status read that follows a completed control write: for the
     * [confirmed] control its frame is the device's POST-write truth, so it
     * publishes as FRESH (supersedes the optimistic mirror and dissolves that
     * lane's accepted-pending hold). The read is generation-stamped per
     * command lane before its GET: a lane commanded again while the read flew
     * is FENCED — its (pre-command) values neither publish nor dissolve
     * ([applyStatusSnapshot]); confirmations still current are accepted.
     * When the read fails, ONLY the confirmed control's hold arms — the SSE
     * lane's next frame gets the one-frame grace rather than clobbering the
     * accepted value with a possibly pre-write sample. A stream confirmation
     * owns no torch/zoom fact, so its failure arms neither hold.
     */
    private suspend fun refreshLiveStatus(confirmed: ConfirmedLane) {
        val client = currentApi() ?: return
        val zoomGen = zoomCommandGeneration.get()
        val torchGen = torchCommandGeneration.get()
        val streamGen = streamCommandGeneration.get()
        val status = try {
            client.getStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            when (confirmed) {
                ConfirmedLane.TORCH -> torchAcceptedPending = true
                ConfirmedLane.ZOOM -> zoomAcceptedPending = true
                ConfirmedLane.STREAM -> Unit
            }
            return
        }
        applyStatusSnapshot(
            status,
            fresh = true,
            confirmedLane = confirmed,
            zoomFenced = zoomGen != zoomCommandGeneration.get(),
            torchFenced = torchGen != torchCommandGeneration.get(),
            streamFenced = streamGen != streamCommandGeneration.get(),
        )
    }

    // ── polls ──

    private var pollJobs: List<Job> = emptyList()

    private fun startPolls() {
        cancelPolls()
        pollJobs = listOf(
            scope.launch {
                while (currentCoroutineContext().isActive) {
                    pollRecording()
                    delay(timings.recordingPollMs)
                }
            },
        )
    }

    private fun cancelPolls() {
        pollJobs.forEach { it.cancel() }
        pollJobs = emptyList()
    }

    /**
     * The device's recording truth, polled every [ConsoleTimings.recordingPollMs].
     * The wire's [com.raulshma.lenscast.companion.client.dto.RecordingStatus]
     * maps onto the local dashboard model as:
     *
     * - `isRecording` → [RecordingState.Recording] with `startedAtMs`
     *   back-computed from the device's own `elapsedSeconds` (the only field
     *   the wire carries — the local clock's `startedAtMs` has no carrier),
     *   config null (the device's config is its own);
     * - `isScheduled == true` → [RecordingState.Scheduled] at the wire's
     *   `scheduledStartTimeMs` (RecordingToggle treats it as live, so the
     *   dashboard's record button offers a stop);
     * - otherwise [RecordingState.Idle].
     */
    private suspend fun pollRecording() {
        val client = currentApi() ?: return
        // Generation-stamped: a recording command bumps the generation, so a
        // read that started before the command is discarded instead of
        // stomping the command's verdict with the pre-command truth.
        val generation = recordingPollGeneration.get()
        val status = try {
            client.recordingStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // transient read failure leaves the last truth rendered
        }
        if (generation != recordingPollGeneration.get()) return
        _recordingState.value = when {
            status.isRecording -> RecordingState.Recording(
                startedAtMs = nowMs() - status.elapsedSeconds * 1000L,
                config = null,
            )
            status.isScheduled == true -> RecordingState.Scheduled(
                startAtMs = status.scheduledStartTimeMs ?: 0L,
                config = RecordingConfig(),
            )
            else -> RecordingState.Idle
        }
        _recordingElapsedSeconds.value = status.elapsedSeconds
    }

    // ── the control verdict mapper (the live view's runControl discipline) ──

    private fun notice(resId: Int) {
        _consoleEvents.trySend(ConsoleEvent.Notice(resId))
    }

    /**
     * One control command with the full verdict mapping: 403 → the
     * viewer-permissions notice, 401 → the re-pair notice, anything else →
     * the generic control notice — and the caller's [revert] runs on every
     * failure path (no optimistic residue). True on success.
     */
    private suspend fun runControl(
        revert: () -> Unit,
        block: suspend () -> Unit,
    ): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: CompanionApiException.Forbidden) {
        revert()
        notice(R.string.companion_live_forbidden)
        false
    } catch (e: CompanionApiException.Auth) {
        revert()
        notice(R.string.companion_live_control_auth)
        false
    } catch (e: CompanionApiException) {
        revert()
        notice(R.string.companion_live_control_failed)
        false
    } catch (e: IOException) {
        revert()
        notice(R.string.companion_live_control_failed)
        false
    }

    // ── camera controls (canControl-gated writes) ──

    override fun switchCamera() {
        // Pick the OTHER facing's first lens from the enumerated list; no
        // list (or no other facing on this device) is a logged no-op — the
        // device enumerates what it has, and there is nothing to flip.
        val lenses = _availableLenses.value
        val current = lenses.getOrNull(_selectedLensIndex.value)
        if (current == null) {
            Log.i(TAG, "switchCamera: no lens list yet — no-op")
            return
        }
        val otherFacing = if (current.lensFacing == MappedLens.LENS_FACING_FRONT) {
            MappedLens.LENS_FACING_BACK
        } else {
            MappedLens.LENS_FACING_FRONT
        }
        val target = lenses.withIndex().firstOrNull { it.value.lensFacing == otherFacing }
        if (target == null) {
            Log.i(TAG, "switchCamera: no ${if (otherFacing == MappedLens.LENS_FACING_FRONT) "front" else "rear"} lens on the device — no-op")
            return
        }
        selectLens(target.index)
    }

    override fun selectLens(index: Int) {
        val client = currentApi() ?: return
        if (!canControlResolver()) return
        scope.launch {
            // Invalidate any lens read already in flight: a read that started
            // before this command must not publish over the confirmed
            // selection when it completes afterwards.
            lensReadGeneration.incrementAndGet()
            val ok = runControl(revert = {}) { client.setLens(index) }
            if (ok) {
                // Invalidate any read that DIALED while this command was in
                // flight (past the first bump, answered from the
                // pre-selection device): its answer can land after the
                // confirmation read below and overwrite the confirmed
                // selection — the confirmation is the only read allowed to
                // publish from here on (the recording command's idiom).
                lensReadGeneration.incrementAndGet()
                // Re-read the enumeration: the selection (and the device's
                // own list) is the GET's truth, never an optimistic guess.
                fetchLenses()
            }
        }
    }

    /** Normalized (0..1) preview tap coordinates — POST /api/camera/focus. */
    override fun tapToFocus(x: Float, y: Float) {
        val client = currentApi() ?: return
        if (!canControlResolver()) return
        scope.launch {
            runControl(revert = {}) { client.setFocus(x.toDouble(), y.toDouble()) }
        }
    }

    private fun zoomBounds(): ClosedFloatingPointRange<Float> = _availableZoomRange.value

    /**
     * Pinch-zoom path: optimistic ratio, then POST /api/camera/zoom. The
     * write goes through the control's single write owner (the zoom writer
     * below), so a pinch's intermediate steps coalesce into the latest
     * target and the status lane reconciles the accepted ratio only once
     * that target has completed (a device-side clamp shows up as the status
     * snap-back).
     */
    override fun updateZoom(ratio: Float) {
        val client = currentApi() ?: return
        if (!canControlResolver()) return
        // Fence any demand confirmation already in flight: it sampled the
        // device before this command, and its answer must not publish over
        // this command's optimistic mirror ([zoomCommandGeneration]).
        zoomCommandGeneration.incrementAndGet()
        val bounds = zoomBounds()
        val target = ratio.coerceIn(bounds.start, bounds.endInclusive)
        val claimed = zoomWriterRunning.compareAndSet(false, true)
        if (claimed) {
            // Claiming the write: pin the rollback anchor BEFORE the first
            // optimistic stick — the anchor is the device-reported ratio.
            zoomFallback = session.status.value?.zoomRatio?.toFloat() ?: _settings.value.zoomRatio
        }
        zoomInFlight.set(true)
        _settings.value = _settings.value.copy(zoomRatio = target)
        pendingZoomTarget.set(target)
        if (claimed) scope.launch { runZoomWriter() }
    }

    /**
     * The zoom control's single write owner: drains the latest pending
     * target (a pinch's intermediate steps coalesce into the newest one),
     * attempts one POST at a time, and releases ownership only once the
     * latest target has completed — so a stale completion or failure can
     * neither reconcile the status lane early nor roll back a newer target.
     * The client resolves per dequeued target (the session rebuilds its
     * client in place on a transport switch — commanding through the
     * launch-time snapshot would dial a defunct transport); with no client
     * available the dequeued target cannot be sent: ownership and the
     * in-flight flag release so a post-reconnect edit starts a clean writer
     * (the status lane reconciles the mirror on reconnect).
     */
    private suspend fun runZoomWriter() {
        // Train outcome bookkeeping: a coalesced train can succeed some
        // writes and fail a later one — TOTAL failure (no write landed) rolls
        // the mirror back to the pinned pre-train anchor; ANY success leaves
        // the device at its last accepted target, so the train ends in the
        // demand confirmation, never a partial rollback to a ratio the
        // device already moved past.
        var finalAttemptFailed = false
        var anyWriteSucceeded = false
        while (true) {
            val target = pendingZoomTarget.getAndSet(null)
            if (target != null) {
                // The entry gate read the role when this target was queued; a
                // retained console can be re-paired to viewer while it sat —
                // re-read at the writer, or a demoted pairing would send a
                // write its gate no longer allows. Nothing sent, nothing
                // accepted: an untouched train rolls back to the anchor, a
                // partially landed one confirms the device's truth instead.
                if (!canControlResolver()) {
                    zoomWriterRunning.set(false)
                    zoomInFlight.set(false)
                    if (anyWriteSucceeded) {
                        refreshLiveStatus(ConfirmedLane.ZOOM)
                    } else {
                        _settings.value = _settings.value.copy(zoomRatio = zoomFallback)
                        publishSettings()
                    }
                    return
                }
                val client = currentApi()
                if (client == null) {
                    zoomWriterRunning.set(false)
                    zoomInFlight.set(false)
                    publishSettings()
                    return
                }
                val ok = runControl(revert = {}) { client.setZoom(target.toDouble()) }
                if (ok) anyWriteSucceeded = true
                finalAttemptFailed = !ok && pendingZoomTarget.get() == null
                if (finalAttemptFailed && !anyWriteSucceeded) {
                    // Total train failure: roll the mirror back to the pinned
                    // pre-train ratio (a newer target still pending owns the
                    // mirror instead).
                    _settings.value = _settings.value.copy(zoomRatio = zoomFallback)
                }
                continue
            }
            if (pendingZoomTarget.get() != null) continue
            zoomWriterRunning.set(false)
            // Re-claim if a target raced the exit (its caller saw the flag
            // still set and skipped launching its own writer).
            if (pendingZoomTarget.get() != null && zoomWriterRunning.compareAndSet(false, true)) {
                continue
            }
            // Ownership released: confirm the accepted ratio with a DEMAND
            // read — the SSE lane's newest sample may predate the write, so
            // the fresh GET (not the next frame) reconciles the mirror. A
            // failed read arms the zoom accepted-pending hold (one-frame
            // grace). A TOTAL train failure skips the read: the anchor
            // already shows the pre-train truth and the republish renders it.
            zoomInFlight.set(false)
            if (finalAttemptFailed && !anyWriteSucceeded) {
                publishSettings()
            } else {
                refreshLiveStatus(ConfirmedLane.ZOOM)
            }
            return
        }
    }

    /**
     * The quick-setting sheet's single write entry. Routing per
     * [RemoteSettingsMapper.PATCH_EXCLUDED_FIELDS]: torch rides
     * `/api/camera/torch` (no settings-wire carrier — the live fact returns
     * on the status lane) and zoom rides `/api/camera/zoom` (a zoom-only
     * edit is exactly the case the mapper's `toPatch` answers null for).
     * Every other control is a GET-merge-PUT of the whole camera section,
     * re-read after the PUT answer.
     */
    override fun updateQuickSetting(type: QuickSettingType, value: Any) {
        val client = currentApi() ?: return
        if (!canControlResolver()) return
        when (type) {
            QuickSettingType.TORCH -> {
                val editor = QuickSettingCatalog.editorValueFor(type, value)
                if (editor is QuickSettingEditorValue.Toggle) toggleTorch(editor.checked)
            }
            QuickSettingType.ZOOM -> {
                val editor = QuickSettingCatalog.editorValueFor(type, value)
                if (editor is QuickSettingEditorValue.Slider) updateZoom(editor.value)
            }
            else -> {
                val editorValue = QuickSettingCatalog.editorValueFor(type, value) ?: return
                val previous = _settings.value
                val next = QuickSettingCatalog.descriptorFor(type).write(previous, editorValue)
                // The pending edit rides [publishSettings]' re-application
                // until THIS write settles: a doc refresh landing mid-write
                // carries it instead of wiping it, a failure reverts exactly
                // it, and a completion removes ONLY its own entry before
                // republishing — a newer frame-rate edit stays visible while
                // an older write settles.
                val edit = PendingQuickEdit(editorValue)
                pendingQuickEdits[type] = edit
                settingsWriteInFlight.set(true)
                _settings.value = next
                scope.launch {
                    var settled = false
                    try {
                        settled = runControl(revert = {
                            pendingQuickEdits.remove(type, edit)
                            publishSettings()
                        }) {
                            putSettingsSection(client, previous, next)
                        }
                    } finally {
                        settingsWriteInFlight.set(false)
                        // Conditional removal: a newer edit of this type that
                        // queued while this write flew keeps its pending
                        // entry — a stale completion never republishes its
                        // own value over it.
                        pendingQuickEdits.remove(type, edit)
                        if (!settled && _settingsDoc.value == null) {
                            // Unhydrated failure (the base GET failed before
                            // any fetch landed): publishSettings has no
                            // document to restore from and would strand the
                            // optimistic value — restore the pre-edit
                            // snapshot so the dashboard never keeps a value
                            // that never reached the device.
                            _settings.value = previous
                        } else {
                            publishSettings()
                        }
                    }
                }
            }
        }
    }

    /**
     * The settings save discipline ([RemoteSettingsMapper.toPatch]'s
     * contract): a FRESH GET is the patch's base, the camera section PUTs
     * whole, and the document is re-read after the PUT answer so the
     * published snapshot carries the device's accepted values. The section
     * is built by the 3-arg [RemoteSettingsMapper.toPatch] over the RAW wire
     * section — every field the edit never touched keeps the device's
     * verbatim wire value (a stored EDOF focus mode, decoded through the
     * AUTO fallback for display, must not be re-PUT as "AUTO"). When a
     * [settingsWriteGate] is wired, the whole chain runs inside it — the
     * console's edit serializes with the settings screens' source writers
     * (both PUT whole sections; the server's key-level merge cannot save two
     * interleaved chains from a lost update) — and the gated block dials the
     * client DIRECTLY: never a source write path, whose mutex is not
     * reentrant.
     */
    private suspend fun putSettingsSection(
        client: LenscastApiClient,
        previous: CameraSettings,
        next: CameraSettings,
    ) {
        val write: suspend () -> Unit = {
            settingsLaneMutex.withLock {
                val freshDoc = client.getSettings()
                // The RAW wire section (before decoding) is the PUT's base:
                // the 3-arg toPatch applies the edits onto it and keeps every
                // unedited field verbatim, wire-only values included.
                val freshWire = freshDoc.camera
                val fresh = RemoteSettingsMapper.toCameraSettings(freshWire)
                // The edit rides the FRESH document: fields the edit touched take
                // next's value, every untouched wire field takes the fresh read's —
                // an ISO-only edit must not re-PUT the stale frame rate the visible
                // snapshot carried (the device may have moved it since). The merged
                // section is fresh-derived, exactly what toPatch's contract wants.
                val merged = fresh.withEditApplied(previous, next)
                val patch = RemoteSettingsMapper.toPatch(fresh, merged, freshWire)
                if (patch == null) {
                    // Nothing the wire carries changed — no PUT at all — but the
                    // fresh read is still the document truth: publish it so the
                    // dashboard never keeps rendering the stale pre-read snapshot.
                    _settingsDoc.value = merged
                    publishSettings()
                    return@withLock
                }
                client.putSettings(patch)
                // The PUT has committed — from here the edit must NOT be reported
                // as failed because its confirmation read fails: the read is
                // retried on its own (never re-PUT), and if the device stays
                // unreadable the edit itself is published so the optimistic
                // mirror stands until the next fetch reconciles.
                var confirmed: AllSettings? = null
                var attempts = 0
                while (confirmed == null && attempts < CONFIRM_READ_ATTEMPTS) {
                    if (attempts > 0) pauseMs(CONFIRM_READ_RETRY_MS)
                    attempts += 1
                    confirmed = try {
                        client.getSettings()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null // the write is in; only the read-back failed — retry
                    }
                }
                _settingsDoc.value = confirmed?.let { RemoteSettingsMapper.toCameraSettings(it.camera) }
                    ?: merged
            }
        }
        // The gate (when wired) wraps the lane mutex, not the reverse: the
        // source's writers never touch [settingsLaneMutex], so no circular
        // wait exists — and the console's own fetch/write ordering below is
        // unchanged. Null gate: the historical direct invocation.
        val gate = settingsWriteGate
        if (gate != null) gate(write) else write()
    }

    /**
     * The edited section: [fresh] with the previous→next delta applied —
     * each wire-carried field takes next's value only where the edit moved
     * it (previous vs next), fresh's value everywhere else. Zoom is pinned
     * to fresh unconditionally: per [RemoteSettingsMapper.PATCH_EXCLUDED_FIELDS]
     * the ratio only ever moves through `/api/camera/zoom`, never a settings
     * PUT (and the visible snapshot's zoom is a live-fact overlay anyway).
     */
    private fun CameraSettings.withEditApplied(
        previous: CameraSettings,
        next: CameraSettings,
    ): CameraSettings = copy(
        exposureCompensation =
            if (previous.exposureCompensation != next.exposureCompensation) {
                next.exposureCompensation
            } else {
                exposureCompensation
            },
        iso = if (previous.iso != next.iso) next.iso else iso,
        exposureTime = if (previous.exposureTime != next.exposureTime) next.exposureTime else exposureTime,
        focusMode = if (previous.focusMode != next.focusMode) next.focusMode else focusMode,
        focusDistance = if (previous.focusDistance != next.focusDistance) next.focusDistance else focusDistance,
        whiteBalance = if (previous.whiteBalance != next.whiteBalance) next.whiteBalance else whiteBalance,
        colorTemperature =
            if (previous.colorTemperature != next.colorTemperature) next.colorTemperature else colorTemperature,
        zoomRatio = zoomRatio,
        frameRate = if (previous.frameRate != next.frameRate) next.frameRate else frameRate,
        resolution = if (previous.resolution != next.resolution) next.resolution else resolution,
        stabilization = if (previous.stabilization != next.stabilization) next.stabilization else stabilization,
        hdrMode = if (previous.hdrMode != next.hdrMode) next.hdrMode else hdrMode,
        sceneMode = if (previous.sceneMode != next.sceneMode) next.sceneMode else sceneMode,
        nightVisionMode =
            if (previous.nightVisionMode != next.nightVisionMode) next.nightVisionMode else nightVisionMode,
        outputRotation = if (previous.outputRotation != next.outputRotation) next.outputRotation else outputRotation,
        orientationLocked =
            if (previous.orientationLocked != next.orientationLocked) next.orientationLocked else orientationLocked,
    )

    /** The torch flip: optimistic mirror, POST, status-lane reconciliation. */
    private fun toggleTorch(next: Boolean) {
        val client = currentApi() ?: return
        // Fence any demand confirmation already in flight (see updateZoom).
        torchCommandGeneration.incrementAndGet()
        val claimed = torchWriterRunning.compareAndSet(false, true)
        if (claimed) {
            // Claiming the write: pin the rollback anchor BEFORE the first
            // optimistic stick — the anchor is the device-reported fact.
            torchFallback = session.status.value?.torchOn ?: _settings.value.torchEnabled
        }
        torchInFlight.set(true)
        _settings.value = _settings.value.copy(torchEnabled = next)
        pendingTorchTarget.set(next)
        if (claimed) scope.launch { runTorchWriter() }
    }

    /**
     * The torch control's single write owner: drains the latest pending
     * target (rapid flips coalesce into the newest one), attempts one POST
     * at a time, and releases ownership only once the latest target has
     * completed — so a stale completion or failure can neither reconcile the
     * status lane early nor roll back a newer target. The client resolves
     * per dequeued target (the zoom writer's rule); with no client available
     * the dequeued target cannot be sent: ownership and the in-flight flag
     * release so a post-reconnect flip starts a clean writer.
     */
    private suspend fun runTorchWriter() {
        // Train outcome bookkeeping (the zoom writer's discipline): a
        // coalesced train can succeed some writes and fail a later one —
        // TOTAL failure (no write landed) rolls the mirror back to the pinned
        // pre-train anchor; ANY success leaves the device at its last
        // accepted fact, so the train ends in the demand confirmation, never
        // a partial rollback to a fact the device already moved past.
        var finalAttemptFailed = false
        var anyWriteSucceeded = false
        while (true) {
            val target = pendingTorchTarget.getAndSet(null)
            if (target != null) {
                // The entry gate read the role when this target was queued; a
                // retained console can be re-paired to viewer while it sat —
                // re-read at the writer, or a demoted pairing would send a
                // write its gate no longer allows. Nothing sent, nothing
                // accepted: an untouched train rolls back to the anchor, a
                // partially landed one confirms the device's truth instead.
                if (!canControlResolver()) {
                    torchWriterRunning.set(false)
                    torchInFlight.set(false)
                    if (anyWriteSucceeded) {
                        refreshLiveStatus(ConfirmedLane.TORCH)
                    } else {
                        _settings.value = _settings.value.copy(torchEnabled = torchFallback)
                        publishSettings()
                    }
                    return
                }
                val client = currentApi()
                if (client == null) {
                    torchWriterRunning.set(false)
                    torchInFlight.set(false)
                    publishSettings()
                    return
                }
                val ok = runControl(revert = {}) { client.setTorch(target) }
                if (ok) anyWriteSucceeded = true
                finalAttemptFailed = !ok && pendingTorchTarget.get() == null
                if (finalAttemptFailed && !anyWriteSucceeded) {
                    // Total train failure: roll the mirror back to the pinned
                    // pre-train fact (a newer target still pending owns the
                    // mirror instead).
                    _settings.value = _settings.value.copy(torchEnabled = torchFallback)
                }
                continue
            }
            if (pendingTorchTarget.get() != null) continue
            torchWriterRunning.set(false)
            // Re-claim if a target raced the exit (its caller saw the flag
            // still set and skipped launching its own writer).
            if (pendingTorchTarget.get() != null && torchWriterRunning.compareAndSet(false, true)) {
                continue
            }
            // Ownership released: confirm the accepted fact with a DEMAND
            // read — the SSE lane's newest sample may predate the write, so
            // the fresh GET (not the next frame) reconciles the mirror. A
            // failed read arms the torch accepted-pending hold (one-frame
            // grace). A TOTAL train failure skips the read: the anchor
            // already shows the pre-train truth and the republish renders it.
            torchInFlight.set(false)
            if (finalAttemptFailed && !anyWriteSucceeded) {
                publishSettings()
            } else {
                refreshLiveStatus(ConfirmedLane.TORCH)
            }
            return
        }
    }

    override fun togglePreview() {
        scope.launch {
            settingsDataStore.saveShowPreview(!showPreview.value)
        }
    }

    /**
     * Viewer-local no-op, DELIBERATE: the persisted flash mode has no wire
     * carrier for `POST /api/capture` (the capture route takes an empty
     * body — only interval-capture start carries a flashMode), so a remote
     * capture cannot honor it. The chip tap therefore does nothing rather
     * than flipping a pref the device would silently ignore — cycling here
     * would show a change that never reaches a photo. (The local backend
     * keeps its cycle; the capability table has no flash flag for the screen
     * to hide the chip — a P3 rendering concern.)
     */
    override fun cycleFlashMode() {
        Log.i(TAG, "cycleFlashMode: viewer pref without a remote carrier — no-op")
    }

    // ── shutter ──

    /**
     * The shutter press: the countdown machinery is the SHARED
     * [SelfTimerRunner] (bit-identical to the local backend); Fire POSTs
     * `/api/capture` and the flash event emits on success.
     *
     * The mode rides the DISK truth, not the replayed flow value: inside the
     * store's default-first window `.value` holds the OFF placeholder and a
     * press would capture immediately, bypassing a saved delay. The disk
     * twin suspends until the read lands (this is the press-side half of the
     * Ready gate), and the dispatch stays on the main dispatcher so the
     * runner's main-thread assumptions hold.
     */
    override fun onShutterPress() {
        if (currentApi() == null) return // no client: the shutter no-ops
        if (!canControlResolver()) return
        scope.launch {
            selfTimerRunner.onShutterPress(settingsDataStore.selfTimerFromDisk()) {
                scope.launch { capturePhoto() }
            }
        }
    }

    /** A tap while the countdown runs cancels it (the verdict is SelfTimerPolicy's). */
    override fun cancelSelfTimer() {
        selfTimerRunner.cancelSelfTimer()
    }

    private suspend fun capturePhoto() {
        val client = currentApi() ?: return
        if (!canControlResolver()) return
        val ok = runControl(revert = {}) { client.capturePhoto() }
        // The flash rides FIRE time only — a failed capture flashes nothing.
        if (ok) _captureFlashEvents.tryEmit(Unit)
    }

    // ── recording ──

    /**
     * The record button: the stop-vs-start verdict is [RecordingToggle]'s.
     * The mic gate is deliberately NOT consulted — the audio that matters is
     * the DEVICE's mic, not this phone's; the start carries the device's
     * defaults (audio on, unbounded).
     */
    override fun toggleRecording() {
        val client = currentApi() ?: return
        if (!canControlResolver()) return
        // One recording command at a time: the gate claims BEFORE the
        // coroutine launches, so a double-tap's second tap returns instead of
        // reaching RecordingToggle.decide with the still-stale state and
        // racing the first command's POST. Cleared when the command settles
        // on either verdict.
        if (!recordingToggleInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                recordingCommand(client)
            } finally {
                recordingToggleInFlight.set(false)
            }
        }
    }

    /**
     * The toggle command the gate owns: the stop-vs-start verdict is
     * [RecordingToggle]'s, the command POSTs, and the recording poll's
     * generation fencing keeps pre-command reads from stomping the verdict.
     */
    private suspend fun recordingCommand(client: LenscastApiClient) {
        // Invalidate any poll read already in flight: a poll that started
        // before this command must not publish over the command's verdict.
        recordingPollGeneration.incrementAndGet()
        when (val decision = RecordingToggle.decide(
            currentState = _recordingState.value,
            startConfig = RecordingConfig(),
        )) {
            is RecordingToggle.ToggleDecision.Start -> {
                val ok = runControl(revert = {}) { client.recordingStart() }
                if (ok) {
                    // Invalidate any poll that DIALED while this command
                    // was in flight (past the first bump, reading the
                    // pre-command state): its answer can land after the
                    // committed verdict below and stomp it back to idle —
                    // the confirmation poll is the only read allowed to
                    // publish from here on.
                    recordingPollGeneration.incrementAndGet()
                    _recordingState.value =
                        RecordingState.Recording(startedAtMs = nowMs(), config = decision.config)
                    _recordingElapsedSeconds.value = 0
                    // The command has completed — refresh with the device's
                    // authoritative truth (this read is generation-current).
                    pollRecording()
                }
            }
            RecordingToggle.ToggleDecision.Stop -> {
                val ok = runControl(revert = {}) { client.recordingStop() }
                if (ok) {
                    // Same invalidation as the start branch above.
                    recordingPollGeneration.incrementAndGet()
                    _recordingState.value = RecordingState.Idle
                    pollRecording()
                }
            }
        }
    }

    // ── streams / server ──

    override fun toggleWebStreaming() {
        scope.launch { toggleStream(StreamKind.WEB, webStreamInFlight) }
    }

    override fun toggleRtspStreaming() {
        scope.launch { toggleStream(StreamKind.RTSP, rtspStreamInFlight) }
    }

    /**
     * One output toggle: the optimistic mirror flips first (guarded by the
     * kind's own in-flight flag so status frames never stomp it), the POST
     * follows, and the device's answer maps onto the SAME
     * [StreamStartOutcome] verdicts the local backend reports — a 200 with
     * `success:false` is the device-side Disabled/StartFailed verdict (the
     * device's enable gates answer in-band, not as HTTP errors).
     */
    private suspend fun toggleStream(kind: StreamKind, inFlight: AtomicBoolean) {
        val client = currentApi() ?: return
        if (!canControlResolver()) return
        // One toggle per kind at a time: the CAS claims before the mirror
        // flips, so a double-tap's second tap returns instead of re-toggling
        // the flipped mirror into an overlapping command. WEB and RTSP keep
        // independent flags — one kind's in-flight command never gates the
        // other.
        if (!inFlight.compareAndSet(false, true)) return
        // Fence any demand confirmation already in flight (see updateZoom —
        // the stream lane's generation): its pre-toggle answer must not
        // publish over this toggle's verdict.
        streamCommandGeneration.incrementAndGet()
        val active = when (kind) {
            StreamKind.WEB -> _streamStatus.value.isWebActive
            StreamKind.RTSP -> _streamStatus.value.isRtspActive
        }
        val next = !active
        val previous = _streamStatus.value
        _streamStatus.value = when (kind) {
            StreamKind.WEB -> previous.copy(isWebActive = next)
            StreamKind.RTSP -> previous.copy(isRtspActive = next)
        }
        try {
            val started = try {
                val answer = when (kind) {
                    StreamKind.WEB -> client.setWebStreamRunning(next)
                    StreamKind.RTSP -> client.setRtspStreamRunning(next)
                }
                commandOutcome(answer, kind, startIntent = next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompanionApiException.Forbidden) {
                notice(R.string.companion_live_forbidden)
                StreamStartOutcome.StartFailed(kind)
            } catch (e: CompanionApiException.Auth) {
                notice(R.string.companion_live_control_auth)
                StreamStartOutcome.StartFailed(kind)
            } catch (e: CompanionApiException) {
                notice(R.string.companion_live_control_failed)
                StreamStartOutcome.StartFailed(kind)
            } catch (e: IOException) {
                notice(R.string.companion_live_control_failed)
                StreamStartOutcome.StartFailed(kind)
            }
            if (started !is StreamStartOutcome.Started && started !is StreamStartOutcome.Stopped) {
                // The mirror reverts on EVERY non-success — a gate rejection
                // (Disabled) leaves nothing live just like a start failure —
                // AND the outcome rides the shared toast/line mapping. Only
                // THIS kind's optimistic bit restores from the snapshot: the
                // rest of the status may have moved while the command flew
                // (a concurrent output's start, refreshed metadata) and a
                // wholesale revert would erase it.
                _streamStatus.value = when (kind) {
                    StreamKind.WEB -> _streamStatus.value.copy(isWebActive = previous.isWebActive)
                    StreamKind.RTSP -> _streamStatus.value.copy(isRtspActive = previous.isRtspActive)
                }
            }
            handleStreamOutcome(started)
        } finally {
            inFlight.set(false)
            // The verdict has landed: reconcile with a demand status read so
            // the dashboard reflects the real stream state now instead of at
            // the next SSE beat — a frame from an earlier stop could
            // otherwise un-mark a successful start (or leave it unmarked)
            // until another frame arrives. The read publishes fresh, after
            // the flag above cleared, so its stream truth applies. Its lane
            // is STREAM: a failed read arms no torch/zoom hold, and the
            // frame's torch/zoom are samples, not confirmations.
            refreshLiveStatus(ConfirmedLane.STREAM)
        }
    }

    /**
     * The server toggle, mapped onto the device's whole-stream pair
     * (`POST /api/stream/start|stop` — startAll/stopAll, the device-side
     * Stream Toggle ladder). The decision reads the status lane's
     * `isActive` rather than the model's `isServerRunning`: remotely the
     * HTTP listener IS the always-on device (the mapper pins
     * `isServerRunning = true`), so "the server" here is its outputs — the
     * toggle means "everything streaming" vs "nothing streaming". A failed
     * start or a rejected stop lands the whole-server failure in
     * [lastServerError], exactly the local semantics.
     */
    override fun toggleServer() {
        val client = currentApi() ?: return
        if (!canControlResolver()) return
        scope.launch {
            val running = session.status.value?.streaming?.isActive
                ?: _streamStatus.value.isActive
            val outcome = if (running) {
                // The stop's verdict rides the SAME in-band mapping as the
                // start: success is the silent Stopped, a success:false
                // answer (or a failed dial) is the whole-server StartFailed —
                // the runControl ok-Boolean this replaces conflated "command
                // failed" with "stopped" and reported neither.
                val answer = try {
                    client.setAllStreamsRunning(false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CompanionApiException.Forbidden) {
                    notice(R.string.companion_live_forbidden)
                    null
                } catch (e: CompanionApiException.Auth) {
                    notice(R.string.companion_live_control_auth)
                    null
                } catch (e: CompanionApiException) {
                    null
                } catch (e: IOException) {
                    null
                }
                when {
                    answer == null -> StreamStartOutcome.StartFailed(kind = null)
                    else -> commandOutcome(answer, kind = null, startIntent = false)
                }
            } else {
                val answer = try {
                    client.setAllStreamsRunning(true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CompanionApiException.Forbidden) {
                    notice(R.string.companion_live_forbidden)
                    null
                } catch (e: CompanionApiException.Auth) {
                    notice(R.string.companion_live_control_auth)
                    null
                } catch (e: CompanionApiException) {
                    null
                } catch (e: IOException) {
                    null
                }
                when {
                    answer == null -> StreamStartOutcome.StartFailed(kind = null)
                    else -> commandOutcome(answer, kind = null, startIntent = true)
                }
            }
            // Both verdicts are total now — a stop rejection reports through
            // the same StartFailed mapping instead of collapsing into null.
            handleStreamOutcome(outcome)
        }
    }

    /**
     * The device's in-band verdict for a stream command: a 200 answer whose
     * payload carries `success:false` is the device-side gate/failure —
     * "…disabled" maps to [StreamStartOutcome.Disabled] (the gate rejection
     * wording the device itself chose), anything else to
     * [StreamStartOutcome.StartFailed]. A `success:true` answer is
     * [StreamStartOutcome.Started] or [StreamStartOutcome.Stopped] by the
     * caller's intent. The whole-stream start (kind null) has no single
     * output kind for the Disabled verdict — its gate rejection folds into
     * the whole-server StartFailed so the response reaches the dashboard's
     * failure line instead of throwing.
     */
    private fun commandOutcome(
        answer: CommandResponse,
        kind: StreamKind?,
        startIntent: Boolean,
    ): StreamStartOutcome = when {
        answer.success -> if (startIntent) StreamStartOutcome.Started else StreamStartOutcome.Stopped
        answer.error?.contains(DISABLED_ERROR_HINT, ignoreCase = true) == true && kind != null ->
            StreamStartOutcome.Disabled(kind)
        else -> StreamStartOutcome.StartFailed(kind)
    }

    /**
     * The stream outcome mapping — the LOCAL backend's
     * [StreamToggle]/toast semantics verbatim: a successful start clears the
     * server panel's stale failure line, a stop leaves it alone, a gate
     * rejection toasts the disabled wording, and a start failure reports
     * through the one failure surface (outputs toast; the whole-server start
     * fills [lastServerError]).
     */
    private fun handleStreamOutcome(outcome: StreamStartOutcome) {
        when (outcome) {
            StreamStartOutcome.Started -> _lastServerError.value = null
            StreamStartOutcome.Stopped -> Unit
            is StreamStartOutcome.Disabled ->
                toast(StreamStartOutcome.disabledMessage(outcome.kind))
            is StreamStartOutcome.StartFailed ->
                reportStartFailure(outcome.kind, cause = null)
            is StreamStartOutcome.BeginFailedRolledBack ->
                reportStartFailure(outcome.kind, outcome.cause)
        }
    }

    private fun reportStartFailure(kind: StreamKind?, cause: Exception?) {
        if (cause != null) {
            val subject = if (kind == null) "server" else "stream"
            Log.e(TAG, "Streaming session setup failed; $subject rolled back", cause)
        }
        val message = StreamStartOutcome.startFailedMessage(kind)
        if (kind == null) {
            _lastServerError.value = message
        } else {
            toast(message)
        }
    }

    // ── URL copy (the DEVICE's addresses) ──

    override fun copyStreamUrl() {
        UrlClipboard.copy(
            context = appContext,
            url = _streamStatus.value.url,
            fallback = currentApi()?.streamUrl().orEmpty(),
            clipLabel = "Stream URL",
            copiedMessage = "Stream URL copied",
        )
    }

    override fun copyRtspUrl() {
        UrlClipboard.copy(
            context = appContext,
            url = _streamStatus.value.rtspUrl,
            fallback = "",
            clipLabel = "RTSP URL",
            copiedMessage = "RTSP URL copied",
        )
    }

    override fun copyHlsUrl() {
        UrlClipboard.copy(
            context = appContext,
            url = currentApi()?.hlsPlaylistUrl().orEmpty(),
            fallback = "",
            clipLabel = "HLS URL",
            copiedMessage = "HLS URL copied",
        )
    }

    // ── local-only stubs (loud where silent would be wrong) ──

    /**
     * LOUD stub: this phone serves no viewers of its own — the connect
     * sheet's data model has no remote answer, and every caller is gated off
     * by [ConsoleCapabilities.serverPanel] = false. Throwing beats silently
     * returning wrong data (a fabricated address would be copied/shared as
     * truth).
     */
    override fun getConnectInfo(): StreamingManager.ConnectInfo =
        throw IllegalStateException(
            "getConnectInfo is local-only: the remote console serves no viewers " +
                "(capabilities.serverPanel = false gates the only callers off).",
        )

    /** LOUD stub, same reasoning as [getConnectInfo]. */
    override fun tlsCertificateFingerprint(): String =
        throw IllegalStateException(
            "tlsCertificateFingerprint is local-only: the remote console has no " +
                "TLS identity of its own (capabilities.serverPanel = false gates the only callers off).",
        )

    // Permission plumbing — no-ops: the remote cameraState never enters
    // RequestPermission, so the screens that call these never render.
    override fun onPermissionResult(cameraGranted: Boolean, audioGranted: Boolean) = Unit
    override fun onAudioPermissionResult(granted: Boolean) = Unit
    override fun markNotificationPermissionAsked() = Unit

    /**
     * The error screen's retry lever: remotely "the camera" is the session
     * link, and `start()` is the session's own manual retry (idempotent; a
     * fresh run from IDLE/OFFLINE/AUTH_FAILED).
     */
    override fun retryCameraInit() {
        session.start()
    }

    /**
     * Local-only by signature: the remote preview renders through its own
     * player surface ([RemotePreviewStage] inside the dashboard's preview
     * box) — a [androidx.camera.view.PreviewView] is never bound.
     */
    override fun startPreview(previewView: androidx.camera.view.PreviewView, lifecycleOwner: androidx.lifecycle.LifecycleOwner) = Unit

    override fun stopPreview() = Unit

    // ── playback ladder (the live tab's logic, concrete-surface) ──

    private val _playback = MutableStateFlow(ConsolePlayback(rung = ladder.current))
    val playback: StateFlow<ConsolePlayback> = _playback.asStateFlow()

    /** The active HLS player, for the preview stage's PlayerView host (null off the HLS rung). */
    private val _hlsPlayer = MutableStateFlow<Player?>(null)
    val hlsPlayer: StateFlow<Player?> = _hlsPlayer.asStateFlow()

    /** Serializes demote→restart chains so error bursts cannot double-demote. */
    private val demoteGate = AtomicBoolean(false)

    private var observation: Job? = null
    private var frameMirror: Job? = null
    private var activeWhep: WhepViewer? = null
    private var activeHls: HlsSession? = null
    private var activeMjpeg: MjpegPlayer? = null

    /**
     * The active WHEP viewer, published for the preview stage's renderer
     * binding. The ViewModel never retains the render surface itself: the
     * renderer is created, bound and released by [RemotePreviewStage] for
     * its own composition lifecycle — the stage re-binds through this flow
     * whenever a rung (re)start produces a new viewer, and detaches through
     * [detachWhepSurface] before it releases the renderer.
     */
    private val _whepViewer = MutableStateFlow<WhepViewer?>(null)
    val whepViewer: StateFlow<WhepViewer?> = _whepViewer.asStateFlow()

    /**
     * The playback generation: bumped by every [startActiveRung] (each call
     * replaces the playing session). Delayed restarts — the terminal-rung
     * auto-retry — stamp it, so a retry that fires against a playback
     * REPLACED mid-pause (a user cycle, a reconnect re-run) stands down
     * instead of restarting the later session.
     */
    private val playbackGeneration = AtomicLong(0)

    /** The manual toggle: one rung down, wrapping MJPEG → WHEP, cooldown-blind. */
    fun onCycleRung() {
        if (currentApi() == null) return
        ladder.cycle()
        Log.i(TAG, "onCycleRung → ${ladder.current}")
        startActiveRung()
    }

    /** Detaches the active WHEP viewer's render sink — the preview stage's teardown seam. */
    fun detachWhepSurface() {
        activeWhep?.detachRenderer()
    }

    /**
     * Starts playback on the ladder's current rung, stopping whatever ran.
     * [preserveFailureReason] carries the failed rung's reason across the
     * replacement so the fallback's CONNECTING beats keep showing WHY the
     * ladder fell; an independent attempt (default) starts clean.
     */
    private fun startActiveRung(preserveFailureReason: Boolean = false) {
        val client = currentApi() ?: return
        Log.i(TAG, "startActiveRung → ${ladder.current}")
        playbackGeneration.incrementAndGet()
        stopRungs(preserveFailureReason)
        when (val rung = ladder.current) {
            PlaybackRung.WHEP -> {
                val viewer = whepFactory(client)
                activeWhep = viewer
                _whepViewer.value = viewer
                observation = scope.launch {
                    viewer.state.collect { state ->
                        if (activeWhep !== viewer || ladder.current != PlaybackRung.WHEP) return@collect
                        when (state) {
                            is WhepState.Connecting -> setRungPhase(rung, RungPhase.CONNECTING)
                            is WhepState.Live -> setRungPhase(rung, RungPhase.LIVE)
                            is WhepState.Failed -> {
                                Log.w(TAG, "WHEP rung failed: ${state.reason}")
                                setRungPhase(rung, RungPhase.FAILED, state.reason)
                                onRungFailed()
                            }
                            is WhepState.Idle -> Unit
                        }
                    }
                }
                // The render surface rides the stage's binding (it attaches
                // through the whepViewer flow for this composition's
                // lifecycle) — the viewer starts surface-less here.
                viewer.start()
            }
            PlaybackRung.HLS -> {
                val created = runCatching { hlsFactory(client) }
                created.onSuccess { hls ->
                    activeHls = hls
                    _hlsPlayer.value = hls.player
                    hls.player.addListener(hlsPhaseListener)
                    observation = scope.launch {
                        hls.errors.collect { error ->
                            if (activeHls === hls && ladder.current == PlaybackRung.HLS) {
                                setRungPhase(rung, RungPhase.FAILED, error.message)
                                onRungFailed()
                            }
                        }
                    }
                }.onFailure {
                    // Player construction failed (e.g. codec/datasource) — a
                    // rung failure like any other: demote. The exception
                    // rides the log: a silent swallow here strands a device
                    // on the terminal rung with no trace of why.
                    Log.w(TAG, "HLS factory failed: $it")
                    setRungPhase(rung, RungPhase.FAILED, it.message)
                    onRungFailed()
                    return
                }
            }
            PlaybackRung.MJPEG -> {
                val player = mjpegFactory(client)
                activeMjpeg = player
                observation = scope.launch {
                    player.state.collect { state ->
                        if (activeMjpeg !== player || ladder.current != PlaybackRung.MJPEG) return@collect
                        when (state) {
                            is MjpegState.Connecting ->
                                setRungPhase(rung, RungPhase.CONNECTING)
                            is MjpegState.Live ->
                                setRungPhase(rung, RungPhase.LIVE)
                            is MjpegState.Failed -> {
                                setRungPhase(rung, RungPhase.FAILED, state.reason)
                                onRungFailed()
                            }
                            is MjpegState.Idle -> Unit
                        }
                    }
                }
                launchFrameMirror(player)
                player.start()
            }
        }
        publishRung()
    }

    /**
     * Mirrors the active MJPEG rung's decoded frames into the playback state
     * — a separate job so the phase collector stays small; cancelled with
     * the rung via [stopRungs].
     */
    private fun launchFrameMirror(player: MjpegPlayer) {
        frameMirror?.cancel()
        frameMirror = scope.launch {
            player.frame.collect { frame ->
                if (activeMjpeg === player && ladder.current == PlaybackRung.MJPEG) {
                    _playback.value = _playback.value.copy(mjpegFrame = frame)
                }
            }
        }
    }

    /**
     * The HLS player's phase beats: READY+playing = LIVE, everything else
     * connecting — except a clean STATE_ENDED, which is a dead rung and
     * demotes like any failure ([updateHlsPhase]).
     */
    private val hlsPhaseListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            updateHlsPhase()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateHlsPhase()
        }
    }

    private fun updateHlsPhase() {
        val hls = activeHls ?: return
        if (ladder.current != PlaybackRung.HLS) return
        val player = hls.player
        if (player.playbackState == Player.STATE_ENDED) {
            // The live window closed cleanly (the device stopped streaming):
            // neither onPlayerError nor the BUFFERING-only stall watchdog
            // fires on a clean end, and the session never restarts itself —
            // mapped as CONNECTING this rung would sit on "Connecting…"
            // forever. Route it into the same demote the error lane uses.
            setRungPhase(PlaybackRung.HLS, RungPhase.FAILED, "HLS stream ended")
            onRungFailed()
            return
        }
        val phase = if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            RungPhase.LIVE
        } else {
            RungPhase.CONNECTING
        }
        setRungPhase(PlaybackRung.HLS, phase)
    }

    /**
     * One rung failure: demote and restart on the next rung; at the terminal
     * rung (demote is a no-op) schedule the web's slow auto-retry so the
     * stream never sticks. The gate collapses an error burst into one chain.
     */
    private fun onRungFailed() {
        if (!active.get()) return
        if (!demoteGate.compareAndSet(false, true)) return
        try {
            val before = ladder.current
            ladder.demote()
            Log.w(TAG, "onRungFailed: $before → ${ladder.current}")
            if (ladder.current == before) {
                // Terminal rung: schedule the slow auto-retry, stamped with
                // the playback generation. The active-rung check alone can
                // not distinguish sessions (a user cycle or a reconnect
                // re-run can land back on the same rung with a NEW player
                // session) — only the session that actually failed may
                // restart, and any playback replacement bumps the generation.
                val generation = playbackGeneration.get()
                scope.launch {
                    pauseMs(RetryBudget.MAX_EXHAUSTED_RETRY_MS)
                    if (active.get() && playbackGeneration.get() == generation) {
                        startActiveRung(preserveFailureReason = true)
                    }
                }
            } else {
                // The restart runs with the gate OPEN: startActiveRung can hit
                // a synchronous construction failure (HLS on a player-less
                // device) whose handler re-enters here and must demote onward
                // — holding the gate would swallow the nested demote and
                // strand the chain one rung short of the terminal one.
                demoteGate.set(false)
                // The demoted restart carries the failure reason: the next
                // rung's CONNECTING phase must keep showing WHY the ladder
                // fell (only LIVE or an independent attempt clears it).
                startActiveRung(preserveFailureReason = true)
            }
        } finally {
            demoteGate.set(false)
        }
    }

    private fun stopRungs(preserveFailureReason: Boolean = false) {
        observation?.cancel()
        observation = null
        frameMirror?.cancel()
        frameMirror = null
        activeWhep?.stop()
        activeWhep = null
        _whepViewer.value = null
        activeHls?.let { hls ->
            runCatching { hls.player.removeListener(hlsPhaseListener) }
            runCatching { hls.release() }
        }
        activeHls = null
        _hlsPlayer.value = null
        activeMjpeg?.stop()
        activeMjpeg = null
        _playback.value = ConsolePlayback(
            rung = ladder.current,
            failureReason = if (preserveFailureReason) _playback.value.failureReason else null,
        )
    }

    private fun publishRung() {
        _playback.value = _playback.value.copy(rung = ladder.current, mjpegFrame = null)
    }

    /**
     * Writes a phase only while the reported rung is still the ladder's
     * current one. A phase without an explicit reason keeps the previous
     * one — the fallback's CONNECTING beats must not erase the failed
     * rung's WHY; LIVE always clears it.
     */
    private fun setRungPhase(rung: PlaybackRung, phase: RungPhase, failureReason: String? = null) {
        if (rung != ladder.current) return
        _playback.value = _playback.value.copy(
            rung = rung,
            phase = phase,
            failureReason = when {
                phase == RungPhase.LIVE -> null
                failureReason != null -> failureReason
                else -> _playback.value.failureReason
            },
            mjpegFrame = if (rung != PlaybackRung.MJPEG) null else _playback.value.mjpegFrame,
        )
    }

    // ── live-audio listen lane (concrete surface; RoleGatePolicy: a read) ──

    private val _listening = MutableStateFlow(false)

    /** The live listen lane is on (device mic → this phone's playback). */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var audioListener: AudioListener? = null
    private var audioJob: Job? = null

    fun startListen() {
        val client = currentApi() ?: return
        if (audioListener != null) return
        val listener = audioFactory(client)
        audioListener = listener
        _listening.value = true
        audioJob = scope.launch {
            listener.state.collect { audio ->
                when (audio) {
                    is AudioListenerState.Failed -> {
                        stopAudio()
                        notice(R.string.companion_live_audio_failed)
                    }
                    is AudioListenerState.Playing -> _listening.value = true
                    else -> Unit
                }
            }
        }
        listener.start()
    }

    fun stopListen() {
        stopAudio()
    }

    private fun stopAudio() {
        audioJob?.cancel()
        audioJob = null
        audioListener?.stop()
        audioListener = null
        _listening.value = false
    }

    // ── push-to-talk lane (concrete surface; RoleGatePolicy: viewer-allowed write) ──

    private val _talkbackActive = MutableStateFlow(false)

    /** A push-to-talk hold is open. */
    val talkbackActive: StateFlow<Boolean> = _talkbackActive.asStateFlow()

    private val _talkbackNeedsLogin = MutableStateFlow(false)

    /** The last talkback dial failed on the cookie-only WS gate (re-pair/sign-in cue). */
    val talkbackNeedsLogin: StateFlow<Boolean> = _talkbackNeedsLogin.asStateFlow()

    private var talkback: TalkbackSession? = null
    private var talkJobs: List<Job> = emptyList()
    private var mic: MicTalkbackSource? = null

    /** The mic warn-and-degrade gate — THIS phone's mic, freshly read per consult. */
    private val micGate = MicGate(
        context = appContext,
        refreshGranted = {
            _hasAudioPermission.value = micGranted()
            _hasAudioPermission.value
        },
    )

    /**
     * Press: opens the push-to-talk hold — gated through [MicGate] (talkback
     * without this phone's mic is silence, so a degrade ends the attempt
     * with the shared warning surfaced), then dials the uplink WS. The
     * device's WS gate reads ONLY the session cookie, and paired token/basic
     * credentials mint no cookie — the dial is attempted anyway (the honest
     * probe) and its failure maps to the sign-in-required flag + notice when
     * stored credentials exist, the generic failure notice otherwise.
     */
    fun startTalkback() {
        val client = currentApi() ?: return
        if (session.connectionState.value != ConnectionState.CONNECTED) return
        if (_talkbackActive.value) return
        val decision = micGate.consult(featureEnabled = true, featureLabel = "Talkback")
        if (decision is MicStartDecision.Degrade) return
        val uplink = talkbackConnect(client)
        talkback = uplink
        _talkbackNeedsLogin.value = false
        _talkbackActive.value = true
        talkJobs = listOf(
            scope.launch {
                uplink.state.collect { st ->
                    if (st == TalkbackState.FAILED) {
                        val needsLogin = hasStoredCredential
                        endTalk()
                        // The flag rides the notice: the row hint shows until
                        // the next hold attempt clears it (startTalkback).
                        if (needsLogin) _talkbackNeedsLogin.value = true
                        notice(
                            if (needsLogin) R.string.companion_live_talkback_needs_login
                            else R.string.companion_live_talkback_failed,
                        )
                    }
                }
            },
        )
        // connect() can hand back an ALREADY-FAILED session (the WS handshake
        // can die on OkHttp's thread before the call even returns): the
        // collector above then processes FAILED as its first StateFlow value
        // — inline, on Main.immediate — and tears the hold down while this
        // function is still setting up. Do not start the mic into a hold
        // that is already over; endTalk releases the (cancelled) failure
        // collector and the dead session's slot with it.
        if (!_talkbackActive.value) {
            endTalk()
            return
        }
        val source = micFactory?.invoke()
        if (source != null) {
            mic = source
            talkJobs += scope.launch {
                source.chunks.collect { chunk ->
                    runCatching { uplink.sendPcm(chunk) }
                }
            }
            source.start()
        }
    }

    /** Release: the `stop` text + close handshake ride [TalkbackSession.stop]. */
    fun stopTalkback() {
        endTalk()
    }

    private fun endTalk() {
        talkJobs.forEach { it.cancel() }
        talkJobs = emptyList()
        mic?.stop()
        mic = null
        talkback?.stop()
        talkback = null
        _talkbackActive.value = false
    }

    private companion object {
        const val TAG = "DeviceConsole"
        const val DISABLED_ERROR_HINT = "disabled"

        /** The post-PUT confirmation read's bounded attempts (the write itself is never resent). */
        const val CONFIRM_READ_ATTEMPTS = 3

        /** The pause between confirmation-read attempts. */
        const val CONFIRM_READ_RETRY_MS = 250L
    }

    // ── factory ──

    /**
     * The screen-backend Factory idiom: resolves the collaborators — the
     * device via
     * `roster.find(session.deviceId)` (the write gate's role source), the
     * client via the session's own `api()` (so a transport switch keeps the
     * console on the session's rebuilt client), the playback factories over
     * the session's shared, pinned engine (`AndroidWhepPeer(appContext)`
     * included). The graph's [com.raulshma.lenscast.companion.di.CompanionGraph.consoleFactory]
     * supplies [settingsDataStore] (the viewer-local preference lanes) and
     * the app context.
     */
    class Factory(
        private val companion: com.raulshma.lenscast.companion.di.CompanionGraph,
        private val session: DeviceSession,
        private val appContext: Context,
        private val settingsDataStore: SettingsDataStore,
    ) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val device = companion.roster.find(session.deviceId)
            val client = try {
                session.api()
            } catch (_: IllegalStateException) {
                null // no roster entry / credentials: every remote call no-ops
            }
            val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            return DeviceConsoleViewModel(
                session = session,
                api = client,
                // Per-command resolution: a transport switch (or re-pair)
                // rebuilds the session's client in place — the console must
                // command through the currently selected transport, not this
                // construction-time snapshot.
                clientResolver = {
                    try {
                        session.api()
                    } catch (_: IllegalStateException) {
                        null // no roster entry / credentials: every remote call no-ops
                    }
                },
                canControl = device?.role != "viewer",
                // Per-command role resolution (the clientResolver precedent):
                // a retained console can outlive a re-pair that moved the
                // device's role — the gate re-reads the roster live instead
                // of holding the construction-time verdict.
                canControlResolver = {
                    companion.roster.find(session.deviceId)?.role != "viewer"
                },
                // The dashboard's quick-edit GET-merge-PUT must serialize with
                // the settings screens' source writers — both sides PUT whole
                // sections, so interleaved chains can lose an update past the
                // server's key-level merge. The gate is the cached source's
                // write mutex ([RemoteSettingsSource.withSettingsWriteLock]);
                // the safe cast keeps a hypothetical non-remote seam gate-less
                // (null → the console's lane mutex alone) instead of crashing.
                settingsWriteGate =
                    (companion.settingsSource(session.deviceId, appContext)
                        as? com.raulshma.lenscast.companion.console.RemoteSettingsSource)
                        ?.let { source ->
                            // The explicit type: a nested lambda taking a
                            // suspend parameter cannot infer its function
                            // shape inline in this position.
                            val gate: suspend (suspend () -> Unit) -> Unit = { block ->
                                source.withSettingsWriteLock { block() }
                            }
                            gate
                        },
                settingsDataStore = settingsDataStore,
                appContext = appContext.applicationContext,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                playerScope = playerScope,
                ladder = PlayerLadder(),
                whepFactory = { api ->
                    WhepViewer(api, peerFactory = { AndroidWhepPeer(appContext) }, scope = playerScope)
                },
                hlsFactory = { api -> HlsRung.create(appContext, api) },
                mjpegFactory = { api -> MjpegPlayer(MjpegPlayer.transport(api), scope = playerScope) },
                audioFactory = { api -> AudioListener(api, scope = playerScope) },
                talkbackConnect = { api ->
                    TalkbackClient(api.sharedEngine())
                        .connect(api.talkbackWsUrl(), api.sharedAuthHeaders())
                },
                micFactory = { MicTalkbackSource(scope = playerScope) },
                hasStoredCredential = companion.credentials.get(session.deviceId) != null,
            ) as T
        }
    }
}
