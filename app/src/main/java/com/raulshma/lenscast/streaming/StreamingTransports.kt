package com.raulshma.lenscast.streaming

import com.raulshma.lenscast.camera.model.StreamToggle

/**
 * The one [StreamToggle.Transports] adapter over the [StreamingManager] and
 * the [StreamingSession] — previously re-typed by the Web API Stream Handler
 * and the CameraViewModel, whose copy read a derived status snapshot instead
 * of the manager's flows and drifted stale on the toggle gates. The gate
 * getters read the manager's live StateFlows (the source of truth for "is
 * this output active/enabled"), so every Stream Toggle consumer — camera
 * screen and Web API alike — gates against the same state.
 */
class StreamingTransports(
    private val streamingManager: StreamingManager,
    private val streamingSession: StreamingSession,
) : StreamToggle.Transports {
    override val webEnabled: Boolean get() = streamingManager.isWebEnabled.value
    override val rtspEnabled: Boolean get() = streamingManager.isRtspEnabled.value
    override val webActive: Boolean get() = streamingManager.isWebStreamingActive.value
    override val rtspActive: Boolean get() = streamingManager.isRtspRunning.value
    override fun startWeb(): Boolean = streamingManager.startWebStreaming()
    override fun stopWeb() = streamingManager.stopWebStreaming()
    override fun startRtsp(): Boolean = streamingManager.startRtspStreaming()
    override fun stopRtsp() = streamingManager.stopRtspStreaming()
    override fun stopServer() = streamingManager.stopStreaming()
    override suspend fun beginSession() = streamingSession.begin()
    override suspend fun endSession() = streamingSession.end()
}
