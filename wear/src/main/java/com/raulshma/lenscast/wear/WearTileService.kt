package com.raulshma.lenscast.wear

import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders
import androidx.wear.tiles.DeviceParametersBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import androidx.wear.tiles.material.Chip
import androidx.wear.tiles.material.ChipDefaults
import androidx.wear.tiles.material.Text
import androidx.wear.tiles.material.layouts.PrimaryLayout
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The watch-face tile: the phone's stream-state line (web / RTSP, read off
 * the cached last status), the app identity, and a "Tap to open" chip into
 * the remote. Deliberately thin — every decision lives in the pure
 * [WearTileModels] mapper (JVM-tested); this class is only the tiles-
 * material translation plus the manifest-registered service entry point.
 *
 * Data path: the resumed status poll pushes each result through
 * [WearTileStatusCache]; when the tile's stream-state line actually changes,
 * the poller asks the system for an immediate re-render via
 * `TileService.getUpdater(...).requestUpdate(...)`. A 30-minute freshness
 * interval is the periodic fallback so the tile also self-heals when no app
 * screen ever resumes. After process death the cache is empty and the tile
 * honestly renders "Phone status unknown" rather than a stale guess.
 */
class WearTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        ImmediateFuture(buildTile(requestParams))

    private fun buildTile(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val deviceParameters = requestParams.deviceParameters
            ?: DeviceParametersBuilders.DeviceParameters.Builder().build()
        val view = WearTileModels.viewState(WearTileStatusCache.current())

        // The tile-equivalent of a PendingIntent: LaunchAction opens the
        // main activity in-process, remotely hosted — the tiles-idiomatic
        // click-to-open (see README).
        val openRemote = ModifiersBuilders.Clickable.Builder()
            .setId(CLICK_ID)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        val root = PrimaryLayout.Builder(deviceParameters)
            .setPrimaryLabelTextContent(
                Text.Builder(this, getString(view.line.titleRes))
                    .setColor(ColorBuilders.argb(getColor(view.line.colorRes)))
                    .build()
            )
            .setContent(
                Chip.Builder(this, openRemote, deviceParameters)
                    .setPrimaryLabelContent(getString(R.string.tile_tap_to_open))
                    .setChipColors(ChipDefaults.PRIMARY_COLORS)
                    .build()
            )
            .build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder().setRoot(root).build()
                            )
                            .build()
                    )
                    .build()
            )
            .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MS)
            .build()
    }

    private companion object {
        const val CLICK_ID = "open_remote"
        const val RESOURCES_VERSION = "1"

        /** The periodic fallback refresh; the push path is the primary one. */
        const val FRESHNESS_INTERVAL_MS = 30 * 60 * 1_000L
    }
}

/**
 * The in-process hand-off between the resumed poll loop and the tile
 * service (same process by default): the latest status plus the published
 * state line, so the publisher can decide whether the tile needs a
 * re-render at all. Volatile fields — written from the poll coroutine,
 * read from the service's binder thread.
 */
object WearTileStatusCache {

    @Volatile
    private var latest: WearStatus? = null

    @Volatile
    private var publishedLine: WearTileModels.StateLine? = null

    /**
     * Publishes one poll result; true when the tile's stream-state line
     * changed and a re-render is warranted.
     */
    fun publish(status: WearStatus): Boolean {
        val line = WearTileModels.stateLine(status)
        val changed = line != publishedLine
        latest = status
        publishedLine = line
        return changed
    }

    /** The last published status, or null before the first poll of this process. */
    fun current(): WearStatus? = latest
}

/** The stream-state line → the tile strings/colors, one home for the ladder. */
private val WearTileModels.StateLine.titleRes: Int
    get() = when (this) {
        WearTileModels.StateLine.UNKNOWN -> R.string.tile_state_unknown
        WearTileModels.StateLine.IDLE -> R.string.tile_state_idle
        WearTileModels.StateLine.WEB -> R.string.tile_state_web
        WearTileModels.StateLine.RTSP -> R.string.tile_state_rtsp
        WearTileModels.StateLine.WEB_AND_RTSP -> R.string.tile_state_both
    }

private val WearTileModels.StateLine.colorRes: Int
    get() = when (this) {
        WearTileModels.StateLine.UNKNOWN -> R.color.tile_state_unknown
        WearTileModels.StateLine.IDLE -> R.color.tile_state_idle
        WearTileModels.StateLine.WEB -> R.color.tile_state_live
        WearTileModels.StateLine.RTSP -> R.color.tile_state_live
        WearTileModels.StateLine.WEB_AND_RTSP -> R.color.tile_state_live
    }

/**
 * A ready-value ListenableFuture — the tiles service contract without
 * pulling Guava into the watch APK for one factory call. The value is set
 * before the future is ever returned, so listeners can run inline.
 */
private class ImmediateFuture<T>(private val value: T) : ListenableFuture<T> {
    override fun addListener(listener: Runnable, executor: Executor) {
        executor.execute(listener)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = true
    override fun get(): T = value
    override fun get(timeout: Long, unit: TimeUnit): T = value
}
