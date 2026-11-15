package com.raulshma.lenscast.companion.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.raulshma.lenscast.companion.client.CompanionApiException
import com.raulshma.lenscast.companion.client.LenscastApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One decoded, ready-to-render frame. (`DecodedFrame`, not `MjpegFrame` —
 * that name is [MjpegFrame]'s, the raw JPEG payload this stage consumes.)
 * [sequence] is the source's cross-reconnect counter, so the UI can tell a
 * fresh frame from a conflated repeat; [atMs] is the decode wall-clock.
 */
data class DecodedFrame(val bitmap: Bitmap, val sequence: Long, val atMs: Long)

/**
 * The decode seam — the only Android-bound step of the MJPEG rung, so the
 * pipeline's decisions (budget pacing, terminal verdicts, emission) stay
 * JVM-testable with a fake, while [BitmapFrameDecoder] carries the real
 * BitmapFactory path. The decode suspends so a slow decoder parks its own
 * collector (the parse pump conflates ahead of it) instead of occupying a
 * worker thread per frame.
 */
fun interface MjpegFrameDecoder {

    /**
     * Decodes one JPEG payload into a publishable frame, or null to drop it
     * (garbage bytes, implausible markers). [reuse] is the previous frame's
     * bitmap, offered as the inBitmap candidate.
     */
    suspend fun decode(jpeg: ByteArray, sequence: Long, atMs: Long, reuse: Bitmap?): DecodedFrame?
}

/**
 * The Android decode stage: `BitmapFactory.decodeByteArray` with an inBitmap
 * pool of exactly one bitmap.
 *
 * **The pool-of-1 decision, stated:** the previous frame's bitmap is reused
 * as the decode target (`inMutable` + `inBitmap`), so a running viewer holds
 * ONE bitmap allocation for the whole session instead of one per frame —
 * at ~24 fps that is the difference between steady-state memory and GC
 * churn. The cost is honest and accepted: the reuse decodes INTO the bitmap
 * the UI may still be drawing, so a frame can tear for one paint cycle
 * before the new [DecodedFrame] emission triggers recomposition — a live
 * preview reads the newest pixels, the web `<img>` behaves the same way.
 *
 * **RGB_565, justified:** camera MJPEG frames are opaque YUV-derived pixels
 * (no alpha to preserve) bound for a fullscreen preview; 565 halves both the
 * retained bitmap and the per-frame texture upload, and its banding is not
 * legible at preview scale. 565 also keeps the decode software-side (mutable
 * inBitmap targets cannot be hardware bitmaps), which is what reuse needs.
 *
 * A dimension change (the device restarted with a new resolution) invalidates
 * reuse: the decode retries fresh and the pool adopts the new bitmap. Nothing
 * is ever recycled — the StateFlow may still hold the old frame for the UI,
 * and a recycled bitmap under a live Compose render crashes; unreferenced
 * bitmaps are GC'd through the normal native-allocation registry instead.
 */
class BitmapFrameDecoder : MjpegFrameDecoder {

    override suspend fun decode(jpeg: ByteArray, sequence: Long, atMs: Long, reuse: Bitmap?): DecodedFrame? {
        // Cheap bounds probe first: a garbage payload fails here without
        // allocating, and the reuse verdict needs the real dimensions anyway.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Dimensions + mutability decide the reuse verdict here; a config
        // mismatch surfaces as the IllegalArgumentException decodeWith
        // already catches, so no config check is duplicated (platforms are
        // inconsistent about honoring inPreferredConfig, and the fallback
        // decode below is the safety net).
        val reusable = reuse?.takeIf { candidate ->
            candidate.isMutable &&
                candidate.width == bounds.outWidth &&
                candidate.height == bounds.outHeight
        }
        val decoded = decodeWith(jpeg, reusable) ?: decodeWith(jpeg, null) ?: return null
        return DecodedFrame(decoded, sequence, atMs)
    }

    /** One decode attempt; null on failure (an inBitmap mismatch throws on some OEMs). */
    private fun decodeWith(jpeg: ByteArray, reuse: Bitmap?): Bitmap? = try {
        BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.RGB_565
                if (reuse != null) inBitmap = reuse
            },
        )
    } catch (_: IllegalArgumentException) {
        null // reuse rejected (dimension/config mismatch) — the caller retries fresh
    }
}

/** The MJPEG rung's lifecycle states, surfaced beside the frames for the rung badge. */
sealed interface MjpegState {

    /** Not started (or stopped). The last decoded frame stays on [MjpegPlayer.frame]. */
    data object Idle : MjpegState

    /** An attempt is opening (or backoff-parked inside the budget) — no frames yet/again. */
    data object Connecting : MjpegState

    /** Frames are flowing; [MjpegPlayer.frame] updates at the camera's cadence. */
    data object Live : MjpegState

    /**
     * The [RetryBudget] is exhausted — terminal, and the caller demotes the
     * ladder ([PlayerLadder.demote]). [reason] is the last transport failure.
     */
    data class Failed(val reason: String) : MjpegState
}

/**
 * The MJPEG rung's decode stage: [MjpegFrameSource]'s transport-agnostic
 * parser wired to a paced reconnecting transport and a [RetryBudget],
 * publishing decoded bitmaps for the UI.
 *
 * Division of labor (the ts `<img>` rung's translation): the source parses
 * multipart, reconnects with NO delay of its own, and never paces — "the
 * pacing decision belongs to the transport, which knows the failure
 * history". THIS class is that transport, and owns every decision around
 * the source:
 *  - the raw open ([transport] in production: OkHttp GET on `streamUrl()`
 *    with the shared engine's auth + pinning, a ~10s socket read timeout —
 *    D's note: the timeout gives tight cancellation between frames, and
 *    covers the parked mid-frame read — a [CompanionApiException] for every
 *    non-200, and a refusal for any 200 that is NOT a multipart body, per
 *    the source's contract that a returned stream means "the camera is
 *    live"; returning a wrong-type stream would make the parser spin);
 *  - the pacing ([pacedOpenStream]): every raw-open failure is one
 *    [RetryBudget] error, taken BEFORE the source's instant reconnect runs
 *    — under budget the next open waits the verdict's backoff, past it the
 *    rung fails terminally ([MjpegState.Failed]) and the CALLER demotes.
 *    A frame arrival clears the budget (`recordSuccess` — errors never
 *    accumulate across a recovery window), so a stream that delivered
 *    frames before dying restarts its escalation from 2s;
 *  - the decode ([MjpegFrameDecoder], on [decodeDispatcher] — Default, not
 *    IO: decode is CPU-bound bitmap work, and parking it off the IO pool
 *    keeps the audio/SSE lanes' sockets fed; the parse pump conflates behind
 *    the collector so decode slowness drops frames, never queues them).
 *
 * Threading/cancellation: [start] launches one supervised loop into [scope];
 * [stop] cancels it. Cancellation lands between frames (the CONFLATED sends
 * never suspend), inside the backoff park, or — if parked reading a silent
 * socket — at the 10s read timeout; the current stream closes on the way
 * out. [state] settles on [MjpegState.Idle] and the last [frame] stays up
 * for the UI. Restartable: start after stop relaunches the loop.
 *
 * The rung is video-only by design (the ladder keeps muxed A/V in HLS); the
 * audio lane rides [AudioListener] beside it.
 */
class MjpegPlayer(
    /**
     * The RAW transport closure — one open, no pacing (production:
     * [transport]; tests: synthetic fixtures). The budget/backoff wrapping
     * is [MjpegPlayer]'s job ([pacedOpenStream]), not the closure's.
     */
    private val openStream: suspend () -> MjpegStream,
    private val decoder: MjpegFrameDecoder = BitmapFrameDecoder(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** The pacing seam: production delays; tests advance a fake clock (no virtual time). */
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
    private val budget: RetryBudget = RetryBudget(),
) {

    private val source = MjpegFrameSource()

    private val _state = MutableStateFlow<MjpegState>(MjpegState.Idle)
    val state: StateFlow<MjpegState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<DecodedFrame?>(null)

    /** The newest decoded frame (conflated — always the freshest decode). */
    val frame: StateFlow<DecodedFrame?> = _frame.asStateFlow()

    private var loop: Job? = null

    /** The decode pool: the bitmap handed back as the next frame's inBitmap candidate. */
    private var pool: Bitmap? = null

    fun start() {
        stop()
        budget.recordSuccess() // a restart is a fresh budget, exactly like a recovery window
        _state.value = MjpegState.Connecting
        loop = scope.launch { runLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        if (_state.value != MjpegState.Idle) _state.value = MjpegState.Idle
    }

    private suspend fun runLoop() {
        try {
            // The source reconnects instantly and never completes on its own;
            // the pacing lives in the closure below. The flow ends only
            // through cancellation or the terminal budget exhaustion.
            source.frames { pacedOpenStream() }.collect { raw ->
                budget.recordSuccess() // a frame arrived: the budget resets (StreamPreview onLoad)
                if (_state.value != MjpegState.Live) _state.value = MjpegState.Live
                val at = clockMs()
                val decoded = withContext(decodeDispatcher) {
                    decoder.decode(raw.jpeg, raw.sequence, at, pool)
                }
                if (decoded != null) {
                    pool = decoded.bitmap
                    _frame.value = decoded
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            _state.value = MjpegState.Failed(t.message ?: "MJPEG stream failed")
        }
    }

    /**
     * The paced transport the source actually sees: a raw-open failure costs
     * one budget error and (under budget) delays the source's next open by
     * the verdict's backoff; the spent budget fails the whole flow
     * terminally — the ladder's demotion signal. A SUCCESSFUL open is not a
     * verdict; only a frame arrival resets the count (a stream that opens
     * and dies without frames keeps escalating).
     */
    private suspend fun pacedOpenStream(): MjpegStream {
        while (true) {
            try {
                return openStream()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                when (val verdict = budget.recordError()) {
                    is RetryBudget.RetryVerdict.RetryWithinRung -> pauseMs(verdict.delayMs)
                    is RetryBudget.RetryVerdict.Exhausted ->
                        throw BudgetExhausted("MJPEG budget exhausted after ${verdict.errorCount} attempts")
                }
            }
        }
    }

    /** The spent-budget terminal failure — [MjpegState.Failed]'s reason, caller demotes. */
    private class BudgetExhausted(message: String) : Exception(message)

    companion object {

        /**
         * The production transport closure: GET `streamUrl()` over the shared
         * (pinned, authed) engine with a dedicated ~10s read timeout, built
         * per attempt so a live re-pair's headers ride the next reconnect.
         * Throws on any non-200 AND on any 200 that is not a multipart body —
         * the source's contract: a RETURNED stream means a real multipart
         * feed follows, and anything else must surface as a failure so the
         * budget paces the retry (returning it would make the parser spin).
         */
        fun transport(api: LenscastApiClient): suspend () -> MjpegStream = {
            openDeviceStream(api)
        }

        private suspend fun openDeviceStream(api: LenscastApiClient): MjpegStream =
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(api.streamUrl())
                    .apply { for ((name, value) in api.sharedAuthHeaders()) header(name, value) }
                    .build()
                // A read-timeout-bounded engine view of the shared engine:
                // the stream is an endless body, so the CALL budgets (8s
                // whole-call) do not apply — the socket must be allowed to
                // stay open between frames while still fencing a dead camera
                // within one frame interval plus slack (D's cancellation note).
                val engine = api.sharedEngine()
                    .newBuilder()
                    .readTimeout(STREAM_READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                val response = engine.awaitCancellable(request)
                if (response.code != 200) {
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
                val contentType = response.header("Content-Type").orEmpty()
                if (MjpegFrameSource.parseMultipartBoundary(contentType) == null) {
                    // A 200 carrying an error page / wrong type (a proxy in
                    // the path): a failed attempt, not a parseable feed.
                    response.use { throw IOException("MJPEG answer is not multipart (type: $contentType)") }
                }
                val body = response.body ?: throw IOException("MJPEG answer carried no body")
                // Ownership moves to the MjpegStream: closing the byte stream
                // closes the body and the connection (MjpegFrameSource uses it
                // in a `use` block, on cancellation included).
                MjpegStream(body.byteStream(), contentType)
            }

        /** Between-frames cancellation fence: see [transport]. */
        const val STREAM_READ_TIMEOUT_MS = 10_000L
    }
}
