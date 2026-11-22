package com.raulshma.lenscast.companion.console

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The remote gallery's one authed bitmap fetcher: OkHttp GETs the device's
 * media route (riding the session's shared engine — the same pool, budgets
 * and pin decision as the JSON lane) with the client's live auth headers, so
 * a token/basic/cookie device's thumbnails decode exactly like the web's
 * `<img src="/api/media/…">` — which needs no headers because the browser
 * already holds the cookie.
 *
 * The device's images can be several megabytes; the thumbnail path scales
 * down at decode time (`inSampleSize` off the decoded bounds) so a grid page
 * of tiles costs tile-sized bitmaps, and a [Semaphore] caps in-flight fetches
 * so one screenful of tiles cannot open forty sockets against the device's
 * single-threaded NanoHTTPD lane. Instances are compose-scoped (remembered
 * per content composition), never singletons across screens.
 *
 * The app's own gallery loads local files through Coil; the remote gallery
 * loads authed routes instead (Coil here has no network fetcher, and adding
 * one is a dependency change), so this class is the same job done over OkHttp
 * — the media lane the client stack already owns. Re-homed from the retired
 * companion media tab, whose grid proved the path.
 */
internal class AuthedBitmapLoader(
    private val engine: OkHttpClient,
    /**
     * The live auth-header ladder — evaluated per load, not captured at
     * construction, so a credential refresh applies to the next tile fetch
     * exactly like it applies to the JSON lane's per-request reads.
     */
    private val authHeaders: () -> Map<String, String>,
) {

    private val slots = Semaphore(MAX_CONCURRENT_FETCHES)

    /**
     * The engine variant the media calls ride: redirects DISABLED. The
     * shared engine keeps OkHttp's default redirect-following, and OkHttp
     * re-sends the request's headers (X-Api-Token, Cookie, the Basic
     * Authorization) on every hop — a 3xx from the device would hand the
     * paired credentials to the redirect's origin. The media routes never
     * legitimately redirect (the device serves its bytes directly), so a
     * 3xx simply fails the load and the tile keeps its placeholder; the
     * derived engine shares the parent's pool and trust decision.
     */
    private val noRedirectEngine: OkHttpClient = engine.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Fetches and decodes [url], or null on any failure (a tile keeps its
     * placeholder; there is no retry loop — the next composition or refresh
     * retries by construction).
     */
    suspend fun load(url: String, thumbnail: Boolean): ImageBitmap? = slots.withPermit {
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url)
                authHeaders().forEach { (name, value) -> builder.header(name, value) }
                val call = noRedirectEngine.newCall(builder.build())
                // One cancellable round trip: cancelling the slot cancels the
                // OkHttp call, breaking a blocked execute()/body read instead
                // of letting it run to its own timeout. The response still
                // closes (`use`), and the permit releases on every exit path.
                suspendCancellableCoroutine<ImageBitmap?> { cont ->
                    cont.invokeOnCancellation { call.cancel() }
                    try {
                        val result = call.execute().use { response ->
                            if (!response.isSuccessful) return@use null
                            val bytes: ByteArray
                            if (thumbnail) {
                                // The thumbnail flow reads a BOUNDED body: the
                                // device's own route downscales photos to a
                                // 512px JPEG (tens of KB), so a Content-Length
                                // far above that is not a thumbnail — reject it
                                // before reading instead of trusting the
                                // response shape. The header is advisory, so the
                                // read itself is capped at LIMIT+1 bytes and the
                                // extra byte vetoes: a header-less (or lying)
                                // oversized answer is rejected before any full
                                // buffering, while a within-limit body decodes
                                // with or without the header. The full-size path
                                // is the viewer's decode-the-original flow and
                                // stays unbounded by design.
                                val declared = response.header("Content-Length")?.toLongOrNull() ?: 0L
                                if (declared > MAX_THUMBNAIL_BYTES) return@use null
                                // peekBody pulls at most the requested bytes off
                                // the wire without consuming the body; `use`
                                // closes the response either way.
                                val peeked = response.peekBody(MAX_THUMBNAIL_BYTES + 1).bytes()
                                if (peeked.size > MAX_THUMBNAIL_BYTES) return@use null
                                bytes = peeked
                            } else {
                                bytes = response.body?.bytes() ?: return@use null
                            }
                            if (!thumbnail) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } else {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                val options = BitmapFactory.Options().apply {
                                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                                }
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
                            }
                        }
                        if (cont.isActive) cont.resume(result)
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }.getOrNull()
        }
    }

    /** The power-of-two downscale that lands the decode near the tile size. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / (sample * 2) >= TARGET_EDGE_PX) sample *= 2
        return sample
    }

    companion object {
        /** In-flight fetch cap — see the class KDoc's NanoHTTPD note. */
        const val MAX_CONCURRENT_FETCHES = 4

        /** The tile decode target (the grid renders ~110 dp cells). */
        const val TARGET_EDGE_PX = 256

        /**
         * The thumbnail body's abuse bound, enforced before buffering: the
         * server's own route answers a 512px JPEG (tens of KB), so anything
         * near this is not a thumbnail response. Generous — the bound exists
         * so a wrong/untrusted answer cannot balloon four parallel tiles'
         * memory, not to police the real route.
         */
        const val MAX_THUMBNAIL_BYTES = 8L * 1024L * 1024L
    }
}

/**
 * One authed media image slot: decodes [url] through [loader] (the media
 * route with the live auth headers) into an [Image], holding a themed type
 * placeholder until the decode lands (or forever on failure — an unreachable
 * tile is not an error surface). The request restarts when the URL changes
 * and cancels with the composition; [refresh] (the gallery's refresh signal)
 * retries only slots with no decoded image yet, so a refresh re-dials failed
 * tiles without re-decoding the ones that landed.
 */
@Composable
internal fun AuthedMediaImage(
    url: String?,
    loader: AuthedBitmapLoader?,
    isVideo: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    thumbnail: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
    refresh: Any? = null,
) {
    // The image resets on any request-shape change — url, loader, or the
    // thumbnail flag — so a re-paired source's new loader (or a slot flipped
    // between the grid's thumbnail and the viewer's full-size route) cannot
    // keep a stale decode or skip the reload. [refresh] stays an effect key
    // ONLY: it re-runs the load without resetting the state, so a retry
    // never clears an image that landed.
    var image by remember(url, loader, thumbnail) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, loader, thumbnail, refresh) {
        if (url == null || loader == null) return@LaunchedEffect
        if (image != null) return@LaunchedEffect // already decoded — a refresh retries failures only
        image = loader.load(url, thumbnail)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val decoded = image
        if (decoded != null) {
            Image(
                bitmap = decoded,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (isVideo) Icons.Filled.Movie else Icons.Filled.ImageIcon,
                contentDescription = contentDescription,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
