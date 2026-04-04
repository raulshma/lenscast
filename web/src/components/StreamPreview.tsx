import { Show, createSignal, createMemo } from 'solid-js'
import type { DeviceStatus, StreamingSettings } from '../types'
import { useZoomable } from '../hooks/useZoomable'
import ConnectionQualityIndicator from './ConnectionQualityIndicator'
import { tapToFocus as apiTapToFocus } from '../api/client'

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
  overlaySettings: () => StreamingSettings | null
}

export default function StreamPreview(props: Props) {
  const st = () => props.status()
  const isActive = () => !!st()?.streaming?.isActive
  const webStreamingEnabled = () => st()?.streaming?.webStreamingEnabled ?? true

  const [focusIndicator, setFocusIndicator] = createSignal<{ x: number; y: number; visible: boolean }>({
    x: 0,
    y: 0,
    visible: false,
  })

  const [previewErrorCount, setPreviewErrorCount] = createSignal(0)
  const MAX_PREVIEW_ERRORS = 5

  const handleStreamClick = async (e: MouseEvent) => {
    const container = e.currentTarget as HTMLElement
    const rect = container.getBoundingClientRect()
    const x = (e.clientX - rect.left) / rect.width
    const y = (e.clientY - rect.top) / rect.height

    setFocusIndicator({ x, y, visible: true })
    setTimeout(() => setFocusIndicator((prev) => ({ ...prev, visible: false })), 1500)

    try {
      await apiTapToFocus(x, y)
    } catch (err) {
      console.error('Tap to focus failed:', err)
    }
  }

  const zoom = useZoomable({
    minScale: 1,
    maxScale: 10,
    wheelZoomFactor: 0.15,
  })

  const overlay = () => props.overlaySettings()
  const overlayEnabled = () => overlay()?.overlayEnabled ?? false
  const overlayPosition = () => overlay()?.overlayPosition ?? 'TOP_LEFT'
  const overlayTextColor = () => overlay()?.overlayTextColor ?? '#FFFFFF'
  const overlayBgColor = () => overlay()?.overlayBackgroundColor ?? '#80000000'
  const overlayFontSize = () => overlay()?.overlayFontSize ?? 28
  const overlayPadding = () => overlay()?.overlayPadding ?? 8
  const overlayLineHeight = () => overlay()?.overlayLineHeight ?? 4

  const positionStyles: Record<string, { top?: string; right?: string; bottom?: string; left?: string }> = {
    TOP_LEFT: { top: '12px', left: '12px' },
    TOP_RIGHT: { top: '12px', right: '12px' },
    BOTTOM_LEFT: { bottom: '12px', left: '12px' },
    BOTTOM_RIGHT: { bottom: '12px', right: '12px' },
  }

  const overlayLines = createMemo(() => {
    const lines: string[] = []
    const o = overlay()
    if (!o) return lines

    if (o.showTimestamp) {
      const now = new Date()
      const fmt = o.timestampFormat || 'yyyy-MM-dd HH:mm:ss'
      const formatted = fmt
        .replace('yyyy', String(now.getFullYear()))
        .replace('MM', String(now.getMonth() + 1).padStart(2, '0'))
        .replace('dd', String(now.getDate()).padStart(2, '0'))
        .replace('HH', String(now.getHours()).padStart(2, '0'))
        .replace('mm', String(now.getMinutes()).padStart(2, '0'))
        .replace('ss', String(now.getSeconds()).padStart(2, '0'))
      lines.push(formatted)
    }

    if (o.showBranding && o.brandingText) {
      lines.push(o.brandingText)
    }

    if (o.showStatus) {
      const statusParts: string[] = []
      if (props.isRecording()) statusParts.push('REC')
      const clientCount = st()?.streaming?.clientCount ?? 0
      if (clientCount > 0) statusParts.push(`${clientCount} viewer${clientCount !== 1 ? 's' : ''}`)
      if (statusParts.length > 0) lines.push(statusParts.join('  '))
    }

    if (o.showCustomText && o.customText) {
      lines.push(o.customText)
    }

    return lines
  })

  return (
    <section class="preview-section" id="preview-section">
      <div
        class="preview-container zoomable-container"
        classList={{ 'preview-active': isActive() && props.previewVisible() }}
        ref={zoom.containerRef}
      >
        {props.previewVisible() && isActive() ? (
          <img
            class="preview-img zoomable-content"
            src={`/stream?t=${props.streamNonce()}`}
            alt="Live camera stream"
            draggable={false}
            loading="eager"
            decoding="async"
            onClick={handleStreamClick}
            onError={() => {
              const count = previewErrorCount() + 1
              setPreviewErrorCount(count)
              if (count >= MAX_PREVIEW_ERRORS) {
                return
              }
              props.setPreviewVisible(false)
              setTimeout(() => {
                if (isActive()) {
                  props.setPreviewVisible(true)
                }
              }, 2000 * Math.min(count, 4))
            }}
            style={{
              transform: `scale(${zoom.scale()}) translate(${zoom.translateX()}px, ${zoom.translateY()}px)`,
            }}
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
            <span class="preview-placeholder-sub">
              {webStreamingEnabled() ? 'Start streaming to see the live feed' : 'Web streaming is disabled in settings'}
            </span>
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

        {/* Connection quality indicator */}
        <Show when={isActive() && props.previewVisible() && st()?.adaptiveBitrate?.enabled && st()?.connectionQuality}>
          <div style={{
            position: 'absolute',
            top: '12px',
            right: '12px',
            'z-index': '10',
          }}>
            <ConnectionQualityIndicator status={() => st()?.connectionQuality} />
          </div>
        </Show>

        <Show when={focusIndicator().visible}>
          <div
            class="focus-indicator"
            style={{
              position: 'absolute',
              left: `${focusIndicator().x * 100}%`,
              top: `${focusIndicator().y * 100}%`,
              transform: 'translate(-50%, -50%)',
              width: '60px',
              height: '60px',
              border: '2px solid #4ade80',
              'border-radius': '4px',
              'z-index': '15',
              'pointer-events': 'none',
              animation: 'focusPulse 1.5s ease-out forwards',
            }}
          />
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
              disabled={props.streamActionLoading() || !webStreamingEnabled()}
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

          <a id="snapshot-btn" class="action-btn action-btn-ghost" href="/snapshot?highres=1" target="_blank" rel="noopener noreferrer" title="Download High-Res Snapshot">
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
