package com.raulshma.lenscast.wear

/**
 * The tile's pure half: the last cached status → the tile's view state.
 * No Android, no tiles types — the service ([WearTileService]) keeps only
 * the translation onto tiles-material layouts and this object stays
 * JVM-testable (WearTileModelsTest, no Robolectric).
 */
object WearTileModels {

    /**
     * The stream-state line the tile renders, read off the per-transport
     * live flags of the cached status. [UNKNOWN] means no status has ever
     * landed (fresh process, phone unreachable since) — the tile then reads
     * as a plain "open the app" affordance instead of a stale guess.
     */
    enum class StateLine { UNKNOWN, IDLE, WEB, RTSP, WEB_AND_RTSP }

    /** One immutable tile render input. */
    data class TileViewState(
        val line: StateLine,
        val clientCount: Int,
    )

    /** The cached-status → view-state mapping (the one tile decision). */
    fun viewState(status: WearStatus?): TileViewState = TileViewState(
        line = if (status == null) StateLine.UNKNOWN else stateLine(status),
        clientCount = status?.clientCount ?: 0,
    )

    /** The line for one known status: any transport live wins over Idle. */
    fun stateLine(status: WearStatus): StateLine = when {
        status.webStreamingActive && status.rtspStreamingActive -> StateLine.WEB_AND_RTSP
        status.webStreamingActive -> StateLine.WEB
        status.rtspStreamingActive -> StateLine.RTSP
        else -> StateLine.IDLE
    }
}
