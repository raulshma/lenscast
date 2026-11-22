package com.raulshma.lenscast.companion.console

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The remote viewer's video surface: one ExoPlayer per [url], fed by an
 * OkHttp datasource over the session's shared engine (pin decision and pool
 * ride along) carrying the device's auth headers as default request
 * properties — the same ladder the JSON lane presents, so a token/basic/
 * cookie device's capture plays exactly like the web viewer's authed fetch.
 *
 * The long-lived engine derivation mirrors the session's SSE lane: the JSON
 * lane's 8 s read / 12 s call budgets would kill every healthy playback
 * mid-stream, so the player's engine zeroes them (the 3 s connect budget
 * stays, so a dead device fails a dial in seconds instead of hanging) — and
 * refuses redirects like the thumbnail lane's engine: OkHttp re-sends the
 * request's headers (X-Api-Token, Cookie, the Basic Authorization) on every
 * hop, so a 3xx would hand the paired credentials to the redirect's origin.
 * The media routes never legitimately redirect, so a 3xx simply fails the
 * stream instead of leaving the engine one flag away from that leak.
 *
 * The player is composition-scoped and BORN in the committed effect below —
 * never during remember (`prepare()` dials the device, a side effect
 * composition must not run, and an effect-born instance is the one the
 * dispose releases): one player per URL, per engine AND per auth-header set.
 * A refreshed credential set or a re-paired source's replacement engine
 * replaces the player so the datasource re-reads both (an unchanged triple
 * keeps the same instance), and the effect stops AND releases the same
 * instance it created whenever it is disposed (new URL, new engine, new
 * headers, viewer closed, screen gone) — never leaked across recompositions.
 * Tests keep their hands off it: the player renders only inside the gallery
 * seam's video slot, and the tests exercise presence/absence of the slot's
 * inputs, not frames.
 * Re-homed from the retired companion media tab, whose in-tab viewer proved
 * the datasource ladder.
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun RemoteVideoPlayer(
    url: String,
    engine: OkHttpClient,
    authHeaders: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The effect is the player's whole lifecycle: it creates, prepares and
    // publishes the instance (the first pass renders null; the published
    // value recomposes the view below), and it releases the SAME instance on
    // disposal — keyed on every dial-shape input, so any replacement tears
    // the old player down before the next one dials.
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(url, engine, authHeaders) {
        val longLived = engine.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val created = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(longLived)))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
        player = created
        onDispose {
            created.stop()
            created.release()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
            }
        },
        // Assign the CURRENT player on every recomposition: the factory runs
        // once, so a replaced player (new URL, new engine, refreshed
        // credentials) would otherwise never reach the already-built
        // PlayerView.
        update = { view -> view.player = player },
    )
}
