import { Show } from 'solid-js'
import type { DeviceStatus } from '../types'

interface Props {
  status: () => DeviceStatus | null
  previewVisible: () => boolean
  streamNonce: () => number
  streamActionLoading: () => boolean
  isRecording: () => boolean
  captureMsg: () => string
  liveAudioStatus: () => 'idle' | 'connecting' | 'live' | 'error'
  recordingTimer: { formatElapsed: () => string }
  handleCapture: () => void
  handleStopStream: () => void
  handleResumeStream: () => void
  setPreviewVisible: (v: boolean) => void
}

export default function StreamPreview(props: Props) {
  const st = () => props.status()
  const isActive = () => !!st()?.streaming?.isActive

  return (
    <section class="preview-section" id="preview-section">
      <div class="preview-container" classList={{ 'preview-active': isActive() && props.previewVisible() }}>
        {props.previewVisible() && isActive() ? (
          <img
            class="preview-img"
            src={`/stream?t=${props.streamNonce()}`}
            alt="Live camera stream"
            draggable={false}
            loading="eager"
            decoding="async"
            onError={() => props.setPreviewVisible(false)}
          />
        ) : (
          <div class="preview-placeholder">
            <div class="preview-placeholder-icon">
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
                <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                <circle cx="12" cy="13" r="4" />
              </svg>
            </div>
            <span class="preview-placeholder-text">{isActive() ? 'Stream error' : 'Stream not active'}</span>
            <span class="preview-placeholder-sub">Start streaming to see the live feed</span>
          </div>
        )}

        {/* Recording overlay */}
        <Show when={props.isRecording()}>
          <div class="recording-timer-overlay">
            <span class="recording-dot" />
            <span class="recording-time">{props.recordingTimer.formatElapsed()}</span>
          </div>
        </Show>

        {/* Live badge */}
        <Show when={isActive() && props.previewVisible()}>
          <div class="live-badge">
            <span class="live-badge-dot" />
            LIVE
          </div>
        </Show>
      </div>

      {/* Action Bar */}
      <div class="preview-actions">
        <div class="preview-actions-left">
          <button
            id="capture-btn"
            class="action-btn action-btn-primary"
            onClick={props.handleCapture}
            disabled={!isActive()}
            title="Capture Photo"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10" />
              <circle cx="12" cy="12" r="4" />
            </svg>
            <span>Capture</span>
          </button>

          {isActive() ? (
            <button
              id="stop-stream-btn"
              class="action-btn action-btn-warning"
              onClick={props.handleStopStream}
              disabled={props.streamActionLoading()}
            >
              <Show when={props.streamActionLoading()} fallback={
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="6" y="6" width="12" height="12" rx="2" />
                </svg>
              }>
                <span class="btn-spinner" />
              </Show>
              <span>{props.streamActionLoading() ? 'Stopping...' : 'Stop'}</span>
            </button>
          ) : (
            <button
              id="resume-stream-btn"
              class="action-btn action-btn-success"
              onClick={props.handleResumeStream}
              disabled={props.streamActionLoading()}
            >
              <Show when={props.streamActionLoading()} fallback={
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <polygon points="5 3 19 12 5 21 5 3" />
                </svg>
              }>
                <span class="btn-spinner" />
              </Show>
              <span>{props.streamActionLoading() ? 'Starting...' : 'Resume'}</span>
            </button>
          )}

          <a id="snapshot-btn" class="action-btn action-btn-ghost" href="/snapshot" target="_blank" download="" title="Download Snapshot">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            <span>Snap</span>
          </a>
        </div>

        <div class="preview-actions-right">
          <Show when={props.captureMsg()}>
            <span class="capture-msg">{props.captureMsg()}</span>
          </Show>
        </div>
      </div>

      {/* Audio status */}
      <Show when={isActive() && st()?.streaming?.audioEnabled}>
        <div class="audio-status-bar">
          <div class="audio-status-indicator" classList={{
            'audio-live': props.liveAudioStatus() === 'live',
            'audio-connecting': props.liveAudioStatus() === 'connecting',
            'audio-error': props.liveAudioStatus() === 'error',
          }}>
            <svg class="audio-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z" />
              <path d="M19 10v2a7 7 0 01-14 0v-2" />
              <line x1="12" y1="19" x2="12" y2="23" />
              <line x1="8" y1="23" x2="16" y2="23" />
            </svg>
            <span>
              {props.liveAudioStatus() === 'live' ? 'Audio Live' :
                props.liveAudioStatus() === 'connecting' ? 'Connecting...' :
                  props.liveAudioStatus() === 'error' ? 'Audio Error' :
                    'Audio Idle'}
            </span>
          </div>
        </div>
      </Show>

      {/* Stream URL */}
      <Show when={isActive() && st()?.streaming?.url}>
        <div class="stream-url-bar">
          <code>{st()!.streaming.url}</code>
        </div>
      </Show>

      {/* RTSP URL */}
      <Show when={isActive() && st()?.streaming?.rtspEnabled && st()?.streaming?.rtspUrl}>
        <div class="stream-url-bar">
          <code>{st()!.streaming.rtspUrl}</code>
        </div>
      </Show>
    </section>
  )
}
