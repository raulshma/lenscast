package com.raulshma.lenscast.companion.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.Player.PositionInfo
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.raulshma.lenscast.companion.client.LenscastApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The HLS rung's tuning, expressed as pure data so the JVM tests can pin the
 * hls.js → ExoPlayer translation (StreamPreview.tsx's `new Hls({...})` block)
 * without instantiating a player:
 *
 * | hls.js (StreamPreview.tsx)     | value      | ExoPlayer (this config)                          | value      |
 * |--------------------------------|-----------|--------------------------------------------------|-----------|
 * | `backBufferLength: 10`         | 10 s kept behind | [DefaultLoadControl.Builder.setBackBuffer] | [backBufferMs] = 10 s |
 * | `liveSyncDurationCount: 2`     | join 2 segments (2×2 s = 4 s) from the live edge | [MediaItem.LiveConfiguration.targetOffsetMs] | [targetOffsetMs] = 4 s |
 * | (hls.js computes the sync position from the count; no explicit bounds) | — | [minOffsetMs] / [maxOffsetMs] | 2 s / 8 s (1..4 segments) |
 * | `maxBufferLength: 6`           | ≤ 6 s forward buffer | [bufferDurations maxBufferMs]            | [maxBufferMs] = 6 s |
 * | (start margin ≈ 1 segment)     | —          | [bufferForPlaybackMs]                            | [bufferForPlaybackMs] = 2 s |
 * | (rejoin margin after a stall)  | —          | [bufferForPlaybackAfterRebufferMs]               | [bufferForPlaybackAfterRebufferMs] = 2 s |
 * | `lowLatencyMode: true` / `enableWorker: true` | hls.js parse/worker machinery | — (ExoPlayer has no equivalent; its loader is already async) | — |
 *
 * Everything is deliberately modest: the rung buffers seconds, not the whole
 * 5×2s sliding window, because a companion viewer values memory and join
 * latency over stall immunity — and the ladder demotes a starving rung
 * anyway. ExoPlayer's LoadControl invariants are asserted in [validate] so a
 * future retune fails a test, not a live player ([DefaultLoadControl] throws
 * on min > max / playback > min at construction time on-device only).
 */
data class HlsRungConfig(
    /** Retained already-played buffer, mirroring hls.js `backBufferLength: 10` (seconds → ms). */
    val backBufferMs: Long,
    /**
     * Distance from the playlist's live edge the player targets, mirroring
     * hls.js `liveSyncDurationCount: 2` (2 segments × the server's 2 s
     * segment duration = 4 s). The playlist itself is a 5×2s sliding window,
     * so the wall-clock position settles ≈ one window behind true live
     * (segmenter cadence + this offset ≈ the ~10 s the web client shows).
     */
    val targetOffsetMs: Long,
    /** Lower bound on the live offset (1 segment): never target closer than this. */
    val minOffsetMs: Long,
    /** Upper bound (4 segments): a starved window may push the offset out, never past this. */
    val maxOffsetMs: Long,
    /** Forward-buffer floor (must stay ≤ [maxBufferMs]); sits at the target offset. */
    val minBufferMs: Long,
    /** Forward-buffer ceiling, mirroring hls.js `maxBufferLength: 6` (seconds → ms). */
    val maxBufferMs: Long,
    /** Buffered media needed to start (1 segment). */
    val bufferForPlaybackMs: Long,
    /** Buffered media needed to resume after a rebuffer. */
    val bufferForPlaybackAfterRebufferMs: Long,
) {

    /** [DefaultLoadControl]'s on-device construction invariants, checked pure. */
    fun validate() {
        require(minBufferMs <= maxBufferMs) { "minBufferMs ($minBufferMs) must be ≤ maxBufferMs ($maxBufferMs)" }
        require(bufferForPlaybackMs <= minBufferMs) {
            "bufferForPlaybackMs ($bufferForPlaybackMs) must be ≤ minBufferMs ($minBufferMs)"
        }
        require(bufferForPlaybackAfterRebufferMs <= maxBufferMs) {
            "bufferForPlaybackAfterRebufferMs ($bufferForPlaybackAfterRebufferMs) must be ≤ maxBufferMs ($maxBufferMs)"
        }
        require(minOffsetMs <= targetOffsetMs && targetOffsetMs <= maxOffsetMs) {
            "live offset bounds inverted: min $minOffsetMs, target $targetOffsetMs, max $maxOffsetMs"
        }
    }

    companion object {
        /** The StreamPreview.tsx tuning, translated (see the class KDoc table). */
        fun live(): HlsRungConfig = HlsRungConfig(
            backBufferMs = 10_000,
            targetOffsetMs = 4_000,
            minOffsetMs = 2_000,
            maxOffsetMs = 8_000,
            minBufferMs = 4_000,
            maxBufferMs = 6_000,
            bufferForPlaybackMs = 2_000,
            bufferForPlaybackAfterRebufferMs = 2_000,
        )
    }
}

/**
 * The HLS rung: an ExoPlayer over the device's `/hls/playlist.m3u8`, built on
 * the shared OkHttp engine (playlist AND segment fetches ride it, so the
 * token/basic/cookie auth headers and the TLS fingerprint pinning apply to
 * every request — media3's own HTTP stack would bypass both).
 *
 * Why this rung exists: muxed A/V (the only mid-ladder rung that keeps the
 * audio track without the WebRTC stack), hardware H.264 decode, and a
 * resilient segmented transport — at the cost of ≈10 s behind live (the
 * server's 5×2s sliding window; see [HlsRungConfig.targetOffsetMs]). It sits
 * between WHEP (sub-second) and MJPEG (live but video-only) in
 * [PlaybackRung].
 *
 * Threading: created on the caller's thread; [ExoPlayer] demands a single
 * application thread for all method calls — the wave-3 Live tab drives it
 * from the main thread (state reads, release). Playback errors surface
 * through [HlsSession.errors] (`onPlayerError`): the ladder's demotion
 * signal. A re-pair mid-session is NOT reflected — auth headers are a
 * snapshot at create; the next ladder attempt builds a fresh session.
 */
object HlsRung {

    /**
     * Builds and prepares the player for [api]'s HLS playlist. `playWhenReady`
     * is set immediately — the rung's whole job is playing. Returns the
     * [HlsSession]; the caller owns [HlsSession.release].
     *
     * media3's builder surface (OkHttpDataSource.Factory, DefaultLoadControl,
     * DefaultMediaSourceFactory, ExoPlayer.Builder.setLoadControl) is
     * `@UnstableApi`-annotated — the one deliberate opt-in in the rung.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun create(context: Context, api: LenscastApiClient, config: HlsRungConfig = HlsRungConfig.live()): HlsSession {
        config.validate()
        val dataSource = OkHttpDataSource.Factory(api.sharedEngine())
            .setDefaultRequestProperties(api.sharedAuthHeaders())
        // The TUNED LoadControl and live offsets — the defaults are wrong for
        // this window on two counts (both pinned by the loopback E2E):
        //  - DefaultLoadControl's bufferForPlaybackMs defaults to 50 SECONDS;
        //    the server's sliding window is ~10s, so the player can never
        //    buffer enough to start and sits in BUFFERING at the window's
        //    front edge forever (the position slides negative as the window
        //    moves; the decoder even initializes — media is READ, never PLAYED).
        //  - No explicit live target: the playlist-derived offset over-shoots
        //    the 5x2s window. The config's 4s target (hls.js
        //    liveSyncDurationCount: 2) joins ~2 segments behind live.
        // The earlier "tuned config stalls" verdict was the PSI CRC bug wearing
        // a disguise (TsPacketizer.crc32) — with the CRC fixed, the tuning is
        // required again.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                config.minBufferMs.toInt(),
                config.maxBufferMs.toInt(),
                config.bufferForPlaybackMs.toInt(),
                config.bufferForPlaybackAfterRebufferMs.toInt(),
            )
            .setBackBuffer(config.backBufferMs.toInt(), true)
            .build()
        // media3 1.11's DefaultMediaSourceFactory: (DataSource.Factory,
        // ExtractorsFactory) with a default extractors arg — the same
        // single-factory shape the gallery's MediaViewerScreen uses.
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource)
        val player = ExoPlayer.Builder(context, mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
        // The MIME type is pinned: URI inference would also work (.m3u8), but
        // a device that ever serves the playlist without the extension must
        // not strand the rung on an unrecognized-media failure. The live
        // configuration rides the item: the server's PROGRAM-DATE-TIME anchor
        // (HlsManager's windowStartEpochMs) defines the absolute timeline the
        // offsets are measured on.
        val item = MediaItem.Builder()
            .setUri(api.hlsPlaylistUrl())
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(config.targetOffsetMs)
                    .setMinOffsetMs(config.minOffsetMs)
                    .setMaxOffsetMs(config.maxOffsetMs)
                    .build(),
            )
            .build()
        return HlsSession(player).apply {
            player.setMediaItem(item)
            // State/load breadcrumbs at debug level: the rung demotes only on
            // a PlayerError — a player that silently never starts (suppression,
            // stalled playlist loader) was undiscoverable from a device.
            // The watchdog rides the same listener and routes a silent strand
            // into [HlsSession.reportStall] — the demote lane onPlayerError
            // can't reach.
            val watchdog = HlsStallWatchdog()
            player.addAnalyticsListener(hlsDebugListener(player, watchdog) { reportStall(it) })
            player.prepare()
            player.playWhenReady = true
        }
    }
}

/**
 * The debug lifecycle trail for the rung: playback-state beats, play-when-ready
 * flips (with the suppression reason when playback is held), and every load
 * start. Debug-level so a field build stays quiet; on a bench device this is
 * the difference between "Connecting… forever" and a fixable verdict.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun hlsDebugListener(
    player: ExoPlayer,
    watchdog: HlsStallWatchdog,
    onStall: (String) -> Unit,
) = object : AnalyticsListener {
    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        Log.d("HlsRung", "HLS state → $state at pos=${eventTime.eventPlaybackPositionMs}")
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) {
        Log.d("HlsRung", "HLS first frame rendered at pos=${eventTime.eventPlaybackPositionMs}")
    }

    override fun onRendererReadyChanged(
        eventTime: AnalyticsListener.EventTime,
        rendererIndex: Int,
        trackType: Int,
        allowsPlayback: Boolean,
    ) {
        Log.d("HlsRung", "HLS renderer[$rendererIndex] type=$trackType ready=$allowsPlayback")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializationDurationMs: Long,
    ) {
        Log.d("HlsRung", "HLS audio decoder ready: $decoderName")
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedingMs: Long,
    ) {
        Log.d("HlsRung", "HLS audio underrun: bufMs=$bufferSizeMs sinceFeed=$elapsedSinceLastFeedingMs")
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: PositionInfo,
        newPosition: PositionInfo,
        reason: Int,
    ) {
        Log.d("HlsRung", "HLS pos ${oldPosition.positionMs} → ${newPosition.positionMs} reason=$reason")
    }

    override fun onPlayWhenReadyChanged(
        eventTime: AnalyticsListener.EventTime,
        playWhenReady: Boolean,
        reason: Int,
    ) {
        Log.d("HlsRung", "HLS playWhenReady → $playWhenReady reason=$reason")
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        // The watchdog heartbeat: segment loads feed it (progress), playlist
        // refreshes consult it — the strand's exact signature is refreshes
        // without loads while BUFFERING.
        if (loadEventInfo.uri.lastPathSegment?.endsWith(".ts") == true) {
            watchdog.onMediaLoaded()
        } else if (watchdog.onPlaylistLoaded(player.playbackState)) {
            onStall("BUFFERING with no segment load for ${HlsStallWatchdog.STALL_AFTER_MS}ms")
        }
        Log.d(
            "HlsRung",
            "HLS loaded ${loadEventInfo.uri.lastPathSegment} bytes=${loadEventInfo.bytesLoaded}" +
                " trackType=${mediaLoadData.trackType} mime=${mediaLoadData.trackFormat?.sampleMimeType}" +
                " pos=${eventTime.eventPlaybackPositionMs}",
        )
        Log.d(
            "HlsRung",
            "HLS buf: state=${player.playbackState} pos=${player.currentPosition}" +
                " buf=${player.bufferedPosition} liveOffset=${player.currentLiveOffset}" +
                " loading=${player.isLoading} suppr=${player.playbackSuppressionReason}" +
                " tracks=${player.currentTracks.groups.joinToString { g ->
                    "type=${g.type} sel=${g.isSelected} n=${g.length}"
                }}",
        )
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializationDurationMs: Long,
    ) {
        Log.d("HlsRung", "HLS video decoder ready: $decoderName")
    }

    override fun onVideoSizeChanged(
        eventTime: AnalyticsListener.EventTime,
        videoSize: androidx.media3.common.VideoSize,
    ) {
        Log.d("HlsRung", "HLS video size: ${videoSize.width}x${videoSize.height}")
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        Log.d("HlsRung", "HLS dropped $droppedFrames video frames over ${elapsedMs}ms")
    }

    override fun onVideoFrameProcessingOffset(
        eventTime: AnalyticsListener.EventTime,
        totalProcessingOffsetUs: Long,
        frameCount: Int,
    ) {
        Log.d("HlsRung", "HLS rendered $frameCount video frames (offsetUs=$totalProcessingOffsetUs)")
    }

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        Log.d("HlsRung", "HLS load: ${loadEventInfo.uri.lastPathSegment ?: loadEventInfo.uri}")
    }

    override fun onTimelineChanged(
        eventTime: AnalyticsListener.EventTime,
        reason: Int,
    ) {
        // The live-window geometry behind every position number: a player
        // pinned at (or behind) the window's front edge shows up here as a
        // startTime that advances under a stationary position.
        val window = eventTime.timeline.getWindow(0, androidx.media3.common.Timeline.Window())
        Log.d(
            "HlsRung",
            "HLS timeline: winStart=${window.windowStartTimeMs} dur=${window.durationMs} " +
                "live=${window.isLive()} defaultPos=${window.getDefaultPositionMs()} " +
                "pos=${eventTime.eventPlaybackPositionMs}",
        )
    }
}

/**
 * The silent-strand detector: media3 can strand a live HLS player without
 * ever calling onPlayerError — a position left before the window start (a
 * join during an encoder stall, a window that jumps past the position)
 * sits in BUFFERING, refreshing the playlist every target duration and
 * loading NOTHING, with no error to demote on. The loopback E2E stranded
 * there for six minutes on "Connecting…".
 *
 * Decision, pure so the JVM tests can pin it: at every playlist refresh, a
 * player still BUFFERING with no segment load for [stallAfterMs] is
 * stalled. A segment load re-arms the window and clears a prior trip (a
 * recovered rung may strand again later); READY refreshes never trip — the
 * healthy live edge waits at the playlist end in READY, not BUFFERING.
 * Single-threaded by construction: analytics callbacks ride the player's
 * application thread.
 */
class HlsStallWatchdog(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val stallAfterMs: Long = STALL_AFTER_MS,
) {

    private var lastMediaLoadMs = nowMs()
    private var tripped = false

    fun onMediaLoaded() {
        lastMediaLoadMs = nowMs()
        tripped = false
    }

    /** [playbackState] is `Player.STATE_*`; true exactly on the buffering-and-starved verdict. */
    fun onPlaylistLoaded(playbackState: Int): Boolean {
        if (playbackState != Player.STATE_BUFFERING) return false
        if (tripped) return false
        if (nowMs() - lastMediaLoadMs < stallAfterMs) return false
        tripped = true
        return true
    }

    companion object {
        /**
         * Above the playlist's ~3s refresh cadence (a healthy join loads its
         * first segment within ~1s), above observed encoder stalls (6-10s —
         * buffering through one is normal), below "noticeably broken to a
         * user" (the web's NO_HLS_RETRY_MS is 15s).
         */
        const val STALL_AFTER_MS = 12_000L
    }
}

/**
 * One live HLS session: the prepared [player] plus the error lane the ladder
 * demotes on. Wave 3 renders it through a media3 PlayerView (or its own
 * surface wiring) and collects [errors].
 */
class HlsSession internal constructor(
    val player: ExoPlayer,
) {

    private companion object {
        const val TAG = "HlsRung"
    }

    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)

    /** Player-level failures (network, playlist, decode) — collect and demote. */
    val errors: Flow<Throwable> = _errors.asSharedFlow()

    /**
     * The strand lane: media3 can park a live player in BUFFERING forever
     * without an onPlayerError (position before the window, zero segment
     * loads) — [HlsStallWatchdog] detects it and this surfaces the same
     * failure the ladder demotes on, with the strand's signature in the log.
     */
    internal fun reportStall(reason: String) {
        Log.w(TAG, "HLS rung demoted: silent stall ($reason)")
        _errors.tryEmit(IllegalStateException("HLS silent strand: $reason"))
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // The breadcrumb rides the emit: the ladder demotes on this
            // silently, and which PlaybackException killed the rung is
            // otherwise undiscoverable from a device. An IO failure names
            // its HTTP verdict (401 vs 404 vs 503 decides the fix).
            val cause = error.cause
            val detail = when (cause) {
                is InvalidResponseCodeException -> "${cause.javaClass.simpleName} code=${cause.responseCode}"
                else -> cause?.javaClass?.simpleName ?: "no cause"
            }
            Log.w(TAG, "HLS rung demoted: ${error.errorCodeName} ($detail)")
            // tryEmit: the 8-slot buffer absorbs a burst while the collector
            // is busy; a dropped duplicate error is harmless to the ladder.
            _errors.tryEmit(error)
        }
    }

    init {
        player.addListener(listener)
    }

    /**
     * Stops playback and releases the player (and its loader threads).
     * Idempotent; the session is dead afterwards.
     */
    fun release() {
        player.removeListener(listener)
        player.release()
    }
}
