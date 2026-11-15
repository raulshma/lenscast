package com.raulshma.lenscast.companion.playback

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The mic capture half of push-to-talk: records 16 kHz mono PCM16 in ~30 ms
 * chunks (480 samples — the TalkbackSocket contract is exactly 16 kHz mono
 * PCM16; the chunk size is this source's own cadence, sized between the 20
 * and 40 ms the wave-3 wiring tolerates so a WebSocket round trip per chunk
 * stays cheap without adding perceptible delay).
 *
 * **SECURITY — the runtime permission is the CALLER's job.** This class never
 * touches [android.Manifest.permission.RECORD_AUDIO]: the wave-3 Live tab
 * must have secured the grant BEFORE [start] (a permission-denied mic is a
 * UI flow — rationale sheet, system dialog, post-grant retry — and belongs at
 * the tab, where the talk affordance lives). Without the grant the
 * [AudioRecord] construction fails and the failure surfaces as the
 * [chunks] flow terminating with a [MicPermissionDenied] failure.
 *
 * **Known limitation — no acoustic echo at this layer:** the uplink is raw
 * mic capture; there is no AEC/NS here (the server's WHEP path runs its own
 * audio processing, but a talkback uplink is a plain AudioRecord). While the
 * device's SPEAKER is playing the camera's live audio ([AudioListener], WHEP
 * audio), the mic re-captures it and the remote hears itself — the classic
 * speakerphone echo. The wave-3 tab owns the mitigation: pause/duck the live
 * audio lane during a hold, or require a headset. Amplitude is likewise
 * unstaged: capture gain is whatever the device's AGC leaves it at, and the
 * device plays it back as-is — speak level varies by hardware.
 *
 * Lifecycle: ONE hold per instance (PTT is a press-hold-release). [start]
 * opens the mic and pumps chunks through a CONFLATED channel — a stalled
 * TalkbackClient collector (slow WS writes) conflates to the newest chunk
 * rather than queueing stale audio, the same live-edge philosophy as
 * LiveAudioPlayer. [stop] stops and releases the mic and completes [chunks].
 * Reconnecting after a hold: construct a fresh source (cheap — the mic is
 * released in between so arbitration stays honest).
 */
class MicTalkbackSource(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Samples per emitted chunk (480 = 30 ms at 16 kHz). */
    private val chunkSamples: Int = DEFAULT_CHUNK_SAMPLES,
) {

    private val channel = Channel<ShortArray>(Channel.CONFLATED)

    /** The captured mic chunks — connect into TalkbackClient's send loop. */
    val chunks: Flow<ShortArray> = channel.receiveAsFlow()

    private var pump: Job? = null

    @Volatile
    private var record: AudioRecord? = null

    /**
     * Opens the mic and starts pumping. The runtime permission must already
     * be granted (see the class KDoc); the failure surfaces through [chunks],
     * not a throw, so the tab's collect handles the UI side.
     */
    @SuppressLint("MissingPermission") // the contract IS the caller's grant; see KDoc
    fun start() {
        check(pump == null) { "MicTalkbackSource is single-hold; construct a fresh one" }
        pump = scope.launch { runPump() }
    }

    /** Stops the capture and releases the mic; [chunks] completes. Idempotent. */
    fun stop() {
        pump?.cancel()
        pump = null
        // release() parks the blocking read (≤ one chunk interval) and the
        // pump's finally closes the channel — but a start() failure before
        // any pump means nobody will; close defensively here.
        record?.let { runCatching { it.stop() } }
        runCatching { record?.release() }
        record = null
        channel.close()
    }

    private suspend fun runPump() {
        var recorder: AudioRecord? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) throw IOException("AudioRecord refused to size a capture buffer ($minBuffer)")
            val bufferBytes = maxOf(minBuffer, chunkSamples * 2 * 2) // ≥ 2 chunks of headroom
            val built = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (e: SecurityException) {
                throw MicPermissionDenied(e.message ?: "RECORD_AUDIO not granted")
            } catch (e: IllegalArgumentException) {
                // Some OEMs signal the missing grant this way instead of a
                // SecurityException — same verdict, same UI.
                throw MicPermissionDenied(e.message ?: "AudioRecord construction refused")
            }
            if (built.state != AudioRecord.STATE_INITIALIZED) {
                // The read() half also throws IllegalStateException when the
                // source never initialized — fail at the construction site.
                runCatching { built.release() }
                throw MicPermissionDenied("AudioRecord not initialized (state ${built.state})")
            }
            recorder = built
            record = built
            built.startRecording()

            val scratch = ShortArray(chunkSamples)
            while (currentCoroutineContext().isActive) {
                // Blocking read on the pump's IO dispatcher; it parks ≤ one
                // chunk interval past a stop(), well inside the buffer bound.
                val read = built.read(scratch, 0, scratch.size)
                if (read <= 0) {
                    // 0/negative: the capture died (source stolen by a higher-
                    // priority user, e.g. a phone call) — end the hold honestly.
                    throw IOException("AudioRecord read failed ($read)")
                }
                // CONFLATED: never suspends — the newest chunk wins when the
                // TalkbackClient side is slower than the mic.
                channel.send(scratch.copyOf(read))
            }
        } catch (e: MicPermissionDenied) {
            // The documented failure surface is the [chunks] flow completing —
            // NOT a rethrow: this coroutine rides an unhandled-exception scope,
            // and a throw here crashes the process. Found on the loopback E2E:
            // AudioRecord.read threw mid-hold ("read failed (0)") and took the
            // app down on a user action. The ended hold is the UI signal.
            Log.w(TAG, "Talkback mic unavailable: ${e.message}")
        } catch (e: IOException) {
            Log.w(TAG, "Talkback mic read failed; ending the hold: ${e.message}")
        } finally {
            recorder?.let { mic ->
                runCatching { mic.stop() }
                runCatching { mic.release() }
            }
            record = null
            channel.close()
        }
    }

    companion object {
        private const val TAG = "MicTalkback"

        /** The TalkbackSocket contract: 16 kHz mono PCM16. */
        const val SAMPLE_RATE_HZ = 16_000

        /** 480 samples = 30 ms — see the class KDoc's cadence note. */
        const val DEFAULT_CHUNK_SAMPLES = 480
    }
}

/** The mic could not be opened because RECORD_AUDIO is not granted (or the OEM refused). */
class MicPermissionDenied(message: String) : IOException(message)
