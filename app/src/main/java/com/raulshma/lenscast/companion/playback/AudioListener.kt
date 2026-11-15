package com.raulshma.lenscast.companion.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.LenscastApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The live-audio lane: streams the device's `/audio` chunked PCM body into an
 * [AudioTrack] — the sink half of web/src/audio/LiveAudioPlayer.ts (the Web
 * Audio stack it needs has no JVM equivalent, which is why this class, unlike
 * the reader it feeds, is thin and JVM-untested by construction; the pure
 * decode decisions live in [LiveAudioReader] and are pinned there).
 *
 * Wire contract (MediaResponder.serveAudio): a chunked body of raw
 * little-endian PCM16, self-described by `X-Audio-Sample-Rate` /
 * `X-Audio-Channels` — the headers are read off the response and handed to
 * [LiveAudioReader.read], which carries the server defaults when they are
 * absent; the actual rate/channels come back inside every [PcmChunk], so the
 * [AudioTrack] is built lazily on the first chunk with the truth.
 *
 * Sink config, pinned: [AudioAttributes] USAGE_MEDIA + CONTENT_TYPE_MUSIC
 * (the dashboard's live mic, not an alarm/voice lane), MODE_STREAM (we push
 * small 40 ms blocks), buffer = max(minBufferSize, ~100 ms worth) — tight
 * enough to stay near the live edge, wide enough that scheduler hiccups do
 * not starve it. Writes are WRITE_BLOCKING on a dedicated [Dispatchers.IO]
 * collection: blocking is the correct pacing for a live stream (a full buffer
 * means we are ahead; parking is free), and underruns are inherently
 * tolerated — the track plays silence until the next chunk lands, and a
 * non-sample `write` verdict (< 0, e.g. a recycled track) is skipped rather
 * than escalated, the next chunk retries.
 *
 * Lifecycle: [start] opens one GET; [stop] cancels it (cancellation closes
 * the response body — the await adapter's `invokeOnCancellation` kills the
 * socket — and releases the track). Non-200 answers surface as
 * [AudioListenerState.Failed] with the [CompanionApiException] taxonomy;
 * transport deaths likewise. The lane is NOT a ladder rung: no internal
 * reconnect — wave 3 pairs the Failed state with its own re-arm cadence, the
 * way the web's LiveAudioPlayer retries on `ended`.
 */
class AudioListener(
    private val api: LenscastApiClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _state = MutableStateFlow<AudioListenerState>(AudioListenerState.Idle)
    val state: StateFlow<AudioListenerState> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        stop()
        _state.value = AudioListenerState.Connecting
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (_state.value != AudioListenerState.Idle) _state.value = AudioListenerState.Idle
    }

    private suspend fun runLoop() {
        try {
            withContext(Dispatchers.IO) {
                openAndPump()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            _state.value = AudioListenerState.Failed(t.message ?: "Live audio failed")
        }
    }

    private suspend fun openAndPump() {
        val request = Request.Builder()
            .url(api.audioUrl())
            .apply { for ((name, value) in api.sharedAuthHeaders()) header(name, value) }
            .build()
        // The audio body is endless: like the MJPEG lane, the engine view
        // raises only the read timeout so a dead device fences within one
        // read while the live one never trips the whole-call budget.
        val engine = api.sharedEngine()
            .newBuilder()
            .readTimeout(STREAM_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val response = engine.awaitCancellable(request)
        if (!response.isSuccessful) {
            response.use { answered ->
                throw when (answered.code) {
                    401 -> CompanionApiException.Auth()
                    403 -> CompanionApiException.Forbidden(api.device().role)
                    else -> CompanionApiException.Http(
                        answered.code,
                        answered.body?.string().orEmpty(),
                    )
                }
            }
        }
        val body = response.body ?: throw IOException("Audio answer carried no body")
        val sampleRate = response.header("X-Audio-Sample-Rate")?.trim()?.toIntOrNull()
        val channels = response.header("X-Audio-Channels")?.trim()?.toIntOrNull()

        val reader = LiveAudioReader()
        var track: AudioTrack? = null
        try {
            // Cancellation closes the body (invokeOnCancellation) → the
            // reader's blocking read throws → the flow unwinds → finally.
            reader.read(body.byteStream(), sampleRate, channels).collect { chunk ->
                val sink = track ?: buildTrack(chunk.sampleRateHz, chunk.channels).also {
                    track = it
                    it.play()
                    _state.value = AudioListenerState.Playing(chunk.sampleRateHz, chunk.channels)
                }
                val written = sink.write(chunk.samples, 0, chunk.samples.size, AudioTrack.WRITE_BLOCKING)
                // WRITE_BLOCKING returns the sample count, or a negative
                // ERROR_* (a dead/recycled track) — skipping one verdict is
                // the underrun tolerance; the next chunk retries.
                if (written < 0) {
                    throw IOException("AudioTrack write failed: $written")
                }
            }
        } finally {
            track?.let { sink ->
                runCatching { sink.pause() }
                runCatching { sink.flush() }
                runCatching { sink.stop() }
                runCatching { sink.release() }
            }
        }
    }

    /** Builds the stream-mode track: USAGE_MEDIA/MUSIC, ~100 ms of PCM16. */
    private fun buildTrack(sampleRateHz: Int, channels: Int): AudioTrack {
        val channelMask = if (channels >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val bytesPerSampleFrame = 2 * channels
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(1)
        val bufferBytes = maxOf(minBuffer, sampleRateHz * bytesPerSampleFrame * TARGET_BUFFER_MS / 1000)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
    }

    private companion object {
        /** Between-chunks death fence: see [AudioListener] class KDoc. */
        const val STREAM_READ_TIMEOUT_MS = 10_000L

        /** The sink's target cushion, per the class KDoc's live-edge math. */
        const val TARGET_BUFFER_MS = 100
    }
}

/** The live-audio lane's states — see [AudioListener]. */
sealed interface AudioListenerState {
    data object Idle : AudioListenerState
    data object Connecting : AudioListenerState

    /** The track is playing; the headers' resolved truth is surfaced for the UI. */
    data class Playing(val sampleRateHz: Int, val channels: Int) : AudioListenerState
    data class Failed(val reason: String) : AudioListenerState
}
