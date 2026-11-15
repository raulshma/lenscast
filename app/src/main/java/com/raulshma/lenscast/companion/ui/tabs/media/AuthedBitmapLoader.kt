package com.raulshma.lenscast.companion.ui.tabs.media

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The media tab's one authed bitmap fetcher: OkHttp GETs the device's media
 * route (the tab rides the session's shared engine — the same pool, budgets
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
 * The app's own gallery loads local files through Coil; the companion tab
 * loads remote, authed routes instead (Coil here has no network fetcher, and
 * adding one is a dependency change), so this class is the same job done over
 * OkHttp — the media lane the client stack already owns.
 */
internal class AuthedBitmapLoader(
    private val engine: OkHttpClient,
    private val authHeaders: Map<String, String>,
) {

    private val slots = Semaphore(MAX_CONCURRENT_FETCHES)

    /**
     * Fetches and decodes [url], or null on any failure (a tile keeps its
     * placeholder; there is no retry loop — the next composition or refresh
     * retries by construction).
     */
    suspend fun load(url: String, thumbnail: Boolean): ImageBitmap? = slots.withPermit {
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(url)
                authHeaders.forEach { (name, value) -> builder.header(name, value) }
                engine.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val bytes = response.body?.bytes() ?: return@runCatching null
                    if (!thumbnail) return@runCatching BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
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
    }
}

/**
 * One authed media image slot: decodes [url] through [loader] (the media
 * route with auth headers) into an [Image], holding a themed type placeholder
 * until the decode lands (or forever on failure — an unreachable tile is not
 * an error surface). The request restarts when the URL changes and cancels
 * with the composition.
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
) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, loader) {
        if (url == null || loader == null) return@LaunchedEffect
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
