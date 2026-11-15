package com.raulshma.lenscast.companion.ui.tabs.live

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import com.raulshma.lenscast.R
import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.LenscastApiClient
import com.raulshma.lenscast.companion.client.dto.DeviceStatus
import com.raulshma.lenscast.companion.client.dto.StreamClientsInfo
import com.raulshma.lenscast.companion.client.ws.TalkbackClient
import com.raulshma.lenscast.companion.client.ws.TalkbackSession
import com.raulshma.lenscast.companion.client.ws.TalkbackState
import com.raulshma.lenscast.companion.di.CompanionGraph
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The per-refresh poll cadence of the live tab, one injectable record (the
 * [DeviceSession]'s `SessionTimings` precedent: this package's tests run on
 * real threads, so a shrunken record is the determinism seam — never a
 * virtual clock):
 *
 * | Field              | Default    | Web origin                                  |
 * |--------------------|------------|---------------------------------------------|
 * | [clientsRefreshMs] | 15 000 ms  | the dashboard's clients-card refresh cadence |
 * | [recordingPollMs]  | 5 000 ms   | the dashboard's recording elapsed readout    |
 */
data class LiveTimings(
    val clientsRefreshMs: Long = 15_000L,
    val recordingPollMs: Long = 5_000L,
)

/**
 * The live tab's state owner. Owns the playback ladder (WHEP → HLS → MJPEG),
 * the admin controls strip, the client roster + kick, the live-audio lane,
 * and push-to-talk — strict MVI: one [state] flow, atomic `update` writes,
 * one-shot [effects] over a buffered channel.
 *
 * Playback (the [PlayerLadder] / web/src/video/playerLadder.ts parity):
 * starts at WHEP; a rung failure demotes (the failed rung is stamped with the
 * ladder's cooldown so a hasty reset cannot bounce straight back);
 * [onCycleRung] is the manual, cooldown-blind wrap; a reconnect edge
 * (non-CONNECTED → CONNECTED) re-runs `reset(WHEP)`, honoring cooldowns.
 * Each rung is created through an injectable factory so JVM tests drive the
 * whole ladder with fake peers/transports and a fake clock. A terminal rung
 * (MJPEG, nothing below it) keeps the web's slow auto-retry
 * ([RetryBudget.MAX_EXHAUSTED_RETRY_MS]) so the stream is never stuck.
 *
 * Controls are admin-only ([canControl] = the roster role is not "viewer");
 * every command is optimistic (the status mirror flips first, the POST
 * follows) and reverts on any failure — a `CompanionApiException.Forbidden`
 * publishes the viewer-permissions notice and nothing else mutates. Status
 * snapshots never stomp an in-flight optimistic write (per-field in-flight
 * flags).
 *
 * Talkback: the device's WS gate reads ONLY the session cookie, and paired
 * token/basic credentials mint no cookie — the dial is attempted anyway (the
 * honest probe) and its failure maps to the sign-in-required notice when
 * stored credentials exist ([hasStoredCredential]), the generic failure
 * notice otherwise.
 *
 * The tab does NOT own the session lifecycle — the DeviceScreen shell starts
 * and stops it. The ViewModel only mirrors its flows and gates activity:
 * [setActive] (composition visibility) pauses polls and tears playback down;
 * on re-activate the ladder re-enters at the best eligible rung.
 */
class LiveViewModel(
    private val session: DeviceSession,
    /** The session's API client; null → [LiveUiState.Unavailable] (no roster entry). */
    private val api: LenscastApiClient?,
    /** The roster role's write gate (viewer hides the controls strip). */
    private val canControl: Boolean,
    /** Whether the pairing stored a token/basic secret (no session cookie → talkback gate). */
    private val hasStoredCredential: Boolean,
    /** The state/effects scope (Main.immediate in production). */
    private val scope: CoroutineScope,
    /** The players' home scope; cancelled on [onCleared]. */
    private val playerScope: CoroutineScope,
    /** The playback ladder decision math. */
    private val ladder: PlayerLadder,
    private val timings: LiveTimings = LiveTimings(),
    /** The pacing seam (tests advance a fake clock; production sleeps). */
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
    private val whepFactory: (LenscastApiClient) -> WhepViewer,
    private val hlsFactory: (LenscastApiClient) -> HlsSession,
    private val mjpegFactory: (LenscastApiClient) -> MjpegPlayer,
    private val audioFactory: (LenscastApiClient) -> AudioListener,
    private val talkbackConnect: (LenscastApiClient) -> TalkbackSession,
    /** The mic source per hold; null in tests that only exercise the uplink dial. */
    private val micFactory: (() -> MicTalkbackSource)? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<LiveUiState>(LiveUiState.Unavailable)
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private val _effects = Channel<LiveEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** The active HLS player, for the PlayerView host (null unless the HLS rung runs). */
    private val _hlsPlayer = MutableStateFlow<Player?>(null)
    val hlsPlayer: StateFlow<Player?> = _hlsPlayer.asStateFlow()

    /** Composition visibility: playback + polls run only while the tab shows. */
    private val active = AtomicBoolean(false)

    /** Serializes demote→restart chains so error bursts cannot double-demote. */
    private val demoteGate = AtomicBoolean(false)

    /** Per-field optimistic-write flags: status frames never stomp in-flight commands. */
    private val torchInFlight = AtomicBoolean(false)
    private val sirenInFlight = AtomicBoolean(false)
    private val streamInFlight = AtomicBoolean(false)
    private val zoomInFlight = AtomicBoolean(false)

    private var observation: Job? = null
    private var activeWhep: WhepViewer? = null
    private var activeHls: HlsSession? = null
    private var activeMjpeg: MjpegPlayer? = null

    @Volatile private var whepSurface: SurfaceViewRenderer? = null

    private var pollJobs: List<Job> = emptyList()
    private var audioListener: AudioListener? = null
    private var audioJob: Job? = null
    private var talkback: TalkbackSession? = null
    private var talkJobs: List<Job> = emptyList()
    private var mic: MicTalkbackSource? = null

    init {
        if (api == null) {
            _state.value = LiveUiState.Unavailable
        } else {
            _state.value = LiveUiState.Ready(canControl = canControl, rung = ladder.current)
            observeConnection()
            observeStatus()
        }
    }

    // ── lifecycle ──

    /**
     * Composition visibility (the shell keeps the session itself running):
     * true enters playback at the best eligible rung and starts the polls;
     * false stops both and closes the audio/talkback lanes.
     */
    fun setActive(isActive: Boolean) {
        if (active.get() == isActive) return
        active.set(isActive)
        if (isActive) {
            if (api == null) return
            ladder.reset(PlaybackRung.WHEP)
            startActiveRung()
            startPolls()
        } else {
            stopRungs()
            cancelPolls()
            stopAudio()
            endTalk()
        }
    }

    override fun onCleared() {
        active.set(false)
        stopRungs()
        cancelPolls()
        stopAudio()
        endTalk()
        val reason = CancellationException("LiveViewModel cleared")
        playerScope.cancel(reason)
        scope.cancel(reason)
    }

    // ── playback ladder ──

    /** The manual toggle: one rung down, wrapping MJPEG → WHEP, cooldown-blind. */
    fun onCycleRung() {
        if (api == null) return
        ladder.cycle()
        Log.i(TAG, "onCycleRung → ${ladder.current}")
        startActiveRung()
    }

    /** Binds (or unbinds) the WHEP render surface — called by the AndroidView host. */
    fun attachWhepSurface(renderer: SurfaceViewRenderer?) {
        if (whepSurface === renderer) return
        whepSurface = renderer
        val viewer = activeWhep ?: return
        if (renderer == null) viewer.detachRenderer() else viewer.attachRenderer(renderer)
    }

    /** Starts playback on the ladder's current rung, stopping whatever ran. */
    private fun startActiveRung() {
        val client = api ?: return
        Log.i(TAG, "startActiveRung → ${ladder.current}")
        stopRungs()
        when (val rung = ladder.current) {
            PlaybackRung.WHEP -> {
                val viewer = whepFactory(client)
                activeWhep = viewer
                observation = scope.launch {
                    viewer.state.collect { state ->
                        if (activeWhep !== viewer || ladder.current != PlaybackRung.WHEP) return@collect
                        when (state) {
                            is WhepState.Connecting -> setRungPhase(rung, RungPhase.CONNECTING)
                            is WhepState.Live -> setRungPhase(rung, RungPhase.LIVE)
                            is WhepState.Failed -> {
                                Log.w(TAG, "WHEP rung failed: ${state.reason}")
                                setRungPhase(rung, RungPhase.FAILED)
                                onRungFailed()
                            }
                            is WhepState.Idle -> Unit
                        }
                    }
                }
                viewer.start(whepSurface)
            }
            PlaybackRung.HLS -> {
                val created = runCatching { hlsFactory(client) }
                created.onSuccess { hls ->
                    activeHls = hls
                    _hlsPlayer.value = hls.player
                    hls.player.addListener(hlsPhaseListener)
                    observation = scope.launch {
                        hls.errors.collect {
                            if (activeHls === hls && ladder.current == PlaybackRung.HLS) {
                                setRungPhase(rung, RungPhase.FAILED)
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
                    setRungPhase(rung, RungPhase.FAILED)
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
                            is MjpegState.Connecting -> setRungPhase(rung, RungPhase.CONNECTING)
                            is MjpegState.Live -> setRungPhase(rung, RungPhase.LIVE)
                            is MjpegState.Failed -> {
                                setRungPhase(rung, RungPhase.FAILED)
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
     * Mirrors the active MJPEG rung's decoded frames into the state — a
     * separate job so the phase collector stays small; cancelled with the
     * rung via [stopRungs]'s frame-mirror job slot.
     */
    private fun launchFrameMirror(player: MjpegPlayer) {
        frameMirror?.cancel()
        frameMirror = scope.launch {
            player.frame.collect { frame ->
                if (activeMjpeg === player && ladder.current == PlaybackRung.MJPEG) {
                    updateReady { it.copy(mjpegFrame = frame) }
                }
            }
        }
    }

    private var frameMirror: Job? = null

    /** The HLS player's phase beats: READY+playing = LIVE, everything else connecting. */
    private val hlsPhaseListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // The full snapshot rides the transition: a player that parks in
            // BUFFERING (or is suppressed) is otherwise opaque from a device.
            activeHls?.let { hls ->
                val p = hls.player
                Log.d(
                    TAG,
                    "HLS phase: state=$playbackState pos=${p.currentPosition} buf=${p.bufferedPosition}" +
                        " dur=${p.duration} liveOffset=${p.currentLiveOffset}" +
                        " pw=${p.playWhenReady} suppr=${p.playbackSuppressionReason}",
                )
            }
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
                val rung = before
                scope.launch {
                    pauseMs(RetryBudget.MAX_EXHAUSTED_RETRY_MS)
                    // A user cycle mid-pause wins: only restart what failed.
                    if (active.get() && ladder.current == rung) startActiveRung()
                }
            } else {
                // The restart runs with the gate OPEN: startActiveRung can hit
                // a synchronous construction failure (HLS on a player-less
                // device) whose handler re-enters here and must demote onward
                // — holding the gate would swallow the nested demote and
                // strand the chain one rung short of the terminal one.
                demoteGate.set(false)
                startActiveRung()
            }
        } finally {
            demoteGate.set(false)
        }
    }

    private fun stopRungs() {
        observation?.cancel()
        observation = null
        frameMirror?.cancel()
        frameMirror = null
        activeWhep?.stop()
        activeWhep = null
        activeHls?.let { hls ->
            runCatching { hls.player.removeListener(hlsPhaseListener) }
            runCatching { hls.release() }
        }
        activeHls = null
        _hlsPlayer.value = null
        activeMjpeg?.stop()
        activeMjpeg = null
        updateReady { it.copy(rungPhase = RungPhase.CONNECTING, mjpegFrame = null) }
    }

    private fun publishRung() {
        updateReady { it.copy(rung = ladder.current) }
    }

    /** Writes a phase only while the reported rung is still the ladder's current one. */
    private fun setRungPhase(rung: PlaybackRung, phase: RungPhase) {
        updateReady { ready ->
            if (ready.rung == rung && ladder.current == rung) ready.copy(rungPhase = phase) else ready
        }
    }

    // ── session mirrors ──

    private fun observeConnection() {
        scope.launch {
            var previous = session.connectionState.value
            session.connectionState.collect { next ->
                updateReady { it.copy(connection = next) }
                val wasConnected = previous == ConnectionState.CONNECTED
                previous = next
                if (next == ConnectionState.CONNECTED && !wasConnected && active.get()) {
                    // Reconnect: best eligible rung from the top, cooldowns
                    // honored — the web's `nextPlayerMode` verdict.
                    ladder.reset(PlaybackRung.WHEP)
                    startActiveRung()
                }
            }
        }
    }

    private fun observeStatus() {
        scope.launch {
            session.status.collect { status ->
                if (status != null) applyStatusSnapshot(status)
            }
        }
    }

    private fun applyStatusSnapshot(status: DeviceStatus) {
        updateReady { cur ->
            cur.copy(
                statusReady = true,
                torchOn = if (torchInFlight.get()) cur.torchOn else status.torchOn,
                sirenActive = if (sirenInFlight.get()) cur.sirenActive else status.sirenActive,
                webStreamActive = if (streamInFlight.get()) cur.webStreamActive else status.streaming.webStreamingActive,
                zoomRatio = if (zoomInFlight.get()) cur.zoomRatio else status.zoomRatio,
                zoomRange = status.zoomRange ?: cur.zoomRange,
            )
        }
    }

    // ── polls ──

    private fun startPolls() {
        cancelPolls()
        val jobs = mutableListOf(
            scope.launch {
                while (currentCoroutineContext().isActive) {
                    fetchClients()
                    delay(timings.clientsRefreshMs)
                }
            },
        )
        if (canControl) {
            jobs += scope.launch {
                while (currentCoroutineContext().isActive) {
                    pollRecording()
                    delay(timings.recordingPollMs)
                }
            }
        }
        pollJobs = jobs
    }

    private fun cancelPolls() {
        pollJobs.forEach { it.cancel() }
        pollJobs = emptyList()
    }

    private suspend fun pollRecording() {
        val client = api ?: return
        val status = try {
            client.recordingStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // transient read failure leaves the last truth on the strip
        }
        updateReady { it.copy(recording = RecordingView(status.isRecording, status.elapsedSeconds)) }
    }

    // ── controls (admin-only) ──

    fun onTorchToggle() {
        val client = api ?: return
        if (!canControl) return
        val ready = currentReady() ?: return
        val next = !ready.torchOn
        torchInFlight.set(true)
        updateReady { it.copy(torchOn = next) }
        scope.launch {
            try {
                runControl(revert = { updateReady { it.copy(torchOn = !next) } }) {
                    client.setTorch(next)
                }
            } finally {
                torchInFlight.set(false)
            }
        }
    }

    fun onSirenToggle() {
        val client = api ?: return
        if (!canControl) return
        val ready = currentReady() ?: return
        val next = !ready.sirenActive
        sirenInFlight.set(true)
        updateReady { it.copy(sirenActive = next) }
        scope.launch {
            try {
                runControl(revert = { updateReady { it.copy(sirenActive = !next) } }) {
                    client.setSiren(next)
                }
            } finally {
                sirenInFlight.set(false)
            }
        }
    }

    fun onWebStreamToggle() {
        val client = api ?: return
        if (!canControl) return
        val ready = currentReady() ?: return
        val next = !ready.webStreamActive
        streamInFlight.set(true)
        updateReady { it.copy(webStreamActive = next) }
        scope.launch {
            try {
                runControl(revert = { updateReady { it.copy(webStreamActive = !next) } }) {
                    client.setWebStreamRunning(next)
                }
            } finally {
                streamInFlight.set(false)
            }
        }
    }

    fun onCapturePhoto() {
        val client = api ?: return
        if (!canControl) return
        scope.launch {
            val ok = runControl(revert = {}) { client.capturePhoto() }
            if (ok) notice(R.string.companion_live_capture_ok)
        }
    }

    /** Slider drag: preview only, bounded to the device's reported range. */
    fun onZoomPreview(ratio: Double) {
        val (min, max) = zoomBounds()
        updateReady { it.copy(zoomRatio = ratio.coerceIn(min, max)) }
    }

    fun onZoomCommit() {
        val client = api ?: return
        if (!canControl) return
        val ready = currentReady() ?: return
        val target = ready.zoomRatio
        val fallback = session.status.value?.zoomRatio ?: target
        zoomInFlight.set(true)
        scope.launch {
            try {
                runControl(revert = { updateReady { it.copy(zoomRatio = fallback) } }) {
                    client.setZoom(target)
                }
            } finally {
                zoomInFlight.set(false)
            }
        }
    }

    fun onRecordingToggle() {
        val client = api ?: return
        if (!canControl) return
        val current = currentReady()?.recording?.isRecording ?: false
        val next = !current
        scope.launch {
            val ok = runControl(
                revert = { updateReady { it.copy(recording = RecordingView(current, 0)) } },
            ) {
                if (next) client.recordingStart() else client.recordingStop()
            }
            if (ok) updateReady { it.copy(recording = RecordingView(next, 0)) }
        }
    }

    private fun zoomBounds(): Pair<Double, Double> {
        val range = currentReady()?.zoomRange
        return (range?.min ?: DEFAULT_ZOOM_MIN) to (range?.max ?: DEFAULT_ZOOM_MAX)
    }

    // ── clients card ──

    fun onRequestKick(clientId: String?) {
        updateReady { it.copy(pendingKickId = clientId) }
    }

    fun onConfirmKick() {
        val client = api ?: return
        if (!canControl) return
        val id = currentReady()?.pendingKickId ?: return
        updateReady { it.copy(pendingKickId = null) }
        scope.launch {
            val ok = runControl(
                revert = {},
                failRes = R.string.companion_live_kick_failed,
            ) { client.kickClient(id) }
            if (ok) {
                notice(R.string.companion_live_kick_ok)
                fetchClients()
            }
        }
    }

    /** The clients card's one refresh — also the polls' body. */
    fun refreshClients() {
        scope.launch { fetchClients() }
    }

    private suspend fun fetchClients() {
        val client = api ?: return
        val info = try {
            client.getStreamClients()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // a read: transient failure leaves the last roster shown
        }
        updateReady { it.copy(clients = info.toRows()) }
    }

    private fun StreamClientsInfo.toRows(): List<StreamClientRow> = buildList {
        httpClients.forEach { address ->
            add(StreamClientRow(id = address, label = address, kind = StreamClientRow.ClientKind.WEB, connectedAtMs = null))
        }
        rtspClients.forEach { client ->
            add(
                StreamClientRow(
                    id = client.id,
                    label = client.remoteAddress,
                    kind = StreamClientRow.ClientKind.RTSP,
                    connectedAtMs = client.connectedAtMs,
                ),
            )
        }
    }

    // ── audio row ──

    fun onListenToggle() {
        val client = api ?: return
        if (audioListener != null) {
            stopAudio()
            return
        }
        val listener = audioFactory(client)
        audioListener = listener
        updateReady { it.copy(listening = true) }
        audioJob = scope.launch {
            listener.state.collect { audio ->
                when (audio) {
                    is AudioListenerState.Failed -> {
                        stopAudio()
                        notice(R.string.companion_live_audio_failed)
                    }
                    is AudioListenerState.Playing -> updateReady { it.copy(listening = true) }
                    else -> Unit
                }
            }
        }
        listener.start()
    }

    private fun stopAudio() {
        audioJob?.cancel()
        audioJob = null
        audioListener?.stop()
        audioListener = null
        updateReady { it.copy(listening = false) }
    }

    // ── push-to-talk ──

    /** Press: dial the uplink WS (+ open the mic when the factory provides one). */
    fun onTalkStart() {
        val client = api ?: return
        if (currentReady()?.connection != ConnectionState.CONNECTED) return
        if (talkback != null) return
        val uplink = talkbackConnect(client)
        talkback = uplink
        updateReady { it.copy(talkbackActive = true, talkbackNeedsLogin = false) }
        talkJobs = listOf(
            scope.launch {
                uplink.state.collect { st ->
                    if (st == TalkbackState.FAILED) {
                        val needsLogin = hasStoredCredential
                        endTalk()
                        // The flag rides the notice: the row hint shows until
                        // the next hold attempt clears it (onTalkStart).
                        if (needsLogin) updateReady { it.copy(talkbackNeedsLogin = true) }
                        notice(
                            if (needsLogin) R.string.companion_live_talkback_needs_login
                            else R.string.companion_live_talkback_failed,
                        )
                    }
                }
            },
        )
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
    fun onTalkEnd() {
        endTalk()
    }

    private fun endTalk() {
        talkJobs.forEach { it.cancel() }
        talkJobs = emptyList()
        mic?.stop()
        mic = null
        talkback?.stop()
        talkback = null
        updateReady { it.copy(talkbackActive = false) }
    }

    // ── helpers ──

    private fun currentReady(): LiveUiState.Ready? = _state.value as? LiveUiState.Ready

    private inline fun updateReady(transform: (LiveUiState.Ready) -> LiveUiState.Ready) {
        _state.update { current ->
            if (current is LiveUiState.Ready) transform(current) else current
        }
    }

    private fun notice(@StringRes resId: Int, arg: String? = null) {
        _effects.trySend(LiveEffect.Notice(resId, arg))
    }

    /**
     * One control command with the full verdict mapping: 403 → the
     * viewer-permissions notice, 401 → the re-pair notice, anything else →
     * [failRes] — and the caller's [revert] runs on every failure path (no
     * optimistic residue). True on success.
     */
    private suspend fun runControl(
        revert: () -> Unit,
        failRes: Int = R.string.companion_live_control_failed,
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
        notice(failRes)
        false
    } catch (e: IOException) {
        revert()
        notice(failRes)
        false
    }

    private companion object {
        const val TAG = "LiveViewModel"
        const val DEFAULT_ZOOM_MIN = 1.0
        const val DEFAULT_ZOOM_MAX = 8.0
    }

    // ── factory ──

    /**
     * The DevicesViewModel.Factory idiom: resolves the collaborators off the
     * companion graph — the device via `roster.find(session.deviceId)` (the
     * write gate's role source), the client via the session's own `api()`
     * (so a transport switch keeps this tab on the session's rebuilt client).
     */
    class Factory(
        private val companion: CompanionGraph,
        private val session: DeviceSession,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val device = companion.roster.find(session.deviceId)
            val client = try {
                session.api()
            } catch (_: IllegalStateException) {
                null // no roster entry / credentials: the Unavailable state
            }
            val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            return LiveViewModel(
                session = session,
                api = client,
                canControl = device?.role != "viewer",
                hasStoredCredential = companion.credentials.get(session.deviceId) != null,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                playerScope = playerScope,
                ladder = PlayerLadder(),
                whepFactory = { api -> WhepViewer(api, peerFactory = { AndroidWhepPeer(appContext) }, scope = playerScope) },
                hlsFactory = { api -> HlsRung.create(appContext, api) },
                mjpegFactory = { api -> MjpegPlayer(MjpegPlayer.transport(api), scope = playerScope) },
                audioFactory = { api -> AudioListener(api, scope = playerScope) },
                talkbackConnect = { api ->
                    TalkbackClient(api.sharedEngine())
                        .connect(api.talkbackWsUrl(), api.sharedAuthHeaders())
                },
                micFactory = { MicTalkbackSource(scope = playerScope) },
            ) as T
        }
    }
}
