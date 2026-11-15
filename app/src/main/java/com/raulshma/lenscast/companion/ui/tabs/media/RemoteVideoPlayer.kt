package com.raulshma.lenscast.companion.ui.tabs.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
 * The in-tab viewer's video surface: one ExoPlayer per [url], fed by an
 * OkHttp datasource over the session's shared engine (pin decision and pool
 * ride along) carrying the device's auth headers as default request
 * properties — the same ladder the JSON lane presents, so a token/basic/
 * cookie device's capture plays exactly like the web viewer's authed fetch.
 *
 * The long-lived engine derivation mirrors the session's SSE lane: the JSON
 * lane's 8 s read / 12 s call budgets would kill every healthy playback
 * mid-stream, so the player's engine zeroes them (the 3 s connect budget
 * stays, so a dead device fails a dial in seconds instead of hanging).
 *
 * The player is composition-scoped: `remember` builds it once per URL, and
 * the [DisposableEffect] stops AND releases it on dispose (viewer closed,
 * tab left, screen gone) — never leaked across recompositions. Tests keep
 * their hands off it: the player exists only behind `state.viewer` (a video
 * target), and the tests exercise presence/absence of that state, not frames.
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
    val player = remember(url) {
        val longLived = engine.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(longLived)
            .setDefaultRequestProperties(authHeaders)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(url) {
        onDispose {
            player.stop()
            player.release()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
    )
}
