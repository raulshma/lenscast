import { Show, createSignal, createMemo, createEffect, onCleanup } from 'solid-js'
import type { DeviceStatus, StreamingSettings } from '../types'
import { useZoomable } from '../hooks/useZoomable'
import ConnectionQualityIndicator from './ConnectionQualityIndicator'
import { tapToFocus as apiTapToFocus, setZoom as apiSetZoom, setTorch as apiSetTorch, pushTalkback } from '../api/client'
import { API_DEFAULTS } from '../api/defaults'
import { createH264Player, h264Supported, wsBaseUrl } from '../video/h264Player'
import { cyclePlayerMode, hlsSupported, nextPlayerMode, type PlayerMode } from '../video/playerLadder'
import type Hls from 'hls.js'

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
  handleStartWebStream: () => void
  handleStopWebStream: () => void
  handleStartRtspStream: () => void
  handleStopRtspStream: () => void
  setPreviewVisible: (v: boolean) => void
  overlaySettings: () => StreamingSettings | null
}

// The talkback mic tap. AudioWorklet (the modern replacement for the
// deprecated ScriptProcessorNode) is served from a blob so the dashboard
// needs no extra asset; browsers without worklet support take the legacy
// processor. Either way the graph dead-ends in a zero-gain node — the mic
// must never be monitored out loud.
const PCM_TAP_WORKLET = `
class PcmTap extends AudioWorkletProcessor {
  process(inputs) {
    const input = inputs[0]
    if (input && input[0]) this.port.postMessage(input[0].slice(0))
    return true
  }
}
registerProcessor('pcm-tap', PcmTap)
`

async function tapPcm16(
  ctx: AudioContext,
  source: MediaStreamAudioSourceNode,
  onChunk: (chunk: Float32Array) => void,
): Promise<void> {
  const sink = ctx.createGain()
  sink.gain.value = 0
  sink.connect(ctx.destination)
  if (ctx.audioWorklet) {
    const blobUrl = URL.createObjectURL(new Blob([PCM_TAP_WORKLET], { type: 'application/javascript' }))
    try {
      await ctx.audioWorklet.addModule(blobUrl)
    } finally {
      URL.revokeObjectURL(blobUrl)
    }
    const node = new AudioWorkletNode(ctx, 'pcm-tap')
    node.port.onmessage = (ev) => onChunk(ev.data as Float32Array)
    source.connect(node)
    node.connect(sink)
    return
  }
  const proc = ctx.createScriptProcessor(2048, 1, 1)
  proc.onaudioprocess = (ev) => onChunk(ev.inputBuffer.getChannelData(0))
  source.connect(proc)
  proc.connect(sink)
}

export default function StreamPreview(props: Props) {
  const st = () => props.status()
  const isActive = () => !!st()?.streaming?.isActive
  const webStreamingEnabled = () => st()?.streaming?.webStreamingEnabled ?? API_DEFAULTS.webStreamingEnabled
  const webActive = () => st()?.streaming?.webStreamingActive ?? API_DEFAULTS.webStreamingActive
  const rtspEnabled = () => st()?.streaming?.rtspEnabled ?? API_DEFAULTS.rtspEnabled
  const rtspActive = () => st()?.streaming?.rtspStreamingActive ?? API_DEFAULTS.rtspStreamingActive

  const [focusIndicator, setFocusIndicator] = createSignal<{ x: number; y: number; visible: boolean }>({
    x: 0,
    y: 0,
    visible: false,
  })

  const [previewErrorCount, setPreviewErrorCount] = createSignal(0)
  const MAX_PREVIEW_ERRORS = 5
  const [playerMode, setPlayerMode] = createSignal<PlayerMode>(
    nextPlayerMode('mjpeg', !h264Supported(), false, hlsSupported()),
  )
  const [h264Canvas, setH264Canvas] = createSignal<HTMLCanvasElement | null>(null)
  const h264 = createH264Player({
    onStatus: (s) => {
      // Fall down the ladder when the WebSocket or decoder gives up:
      // MJPEG next, HLS beyond it if MJPEG is also out of play.
      if (s === 'error' && playerMode() === 'h264') {
        setPlayerMode(nextPlayerMode('h264', true, false, hlsSupported()))
      }
    },
  })
  createEffect(() => {
    const target = h264Canvas()
    const active = props.previewVisible() && webActive()
    if (playerMode() === 'h264' && active && target) {
      h264.start(target)
    } else {
      h264.stop()
    }
  })
  onCleanup(() => h264.stop())

  // ── HLS rung: native `<video>` on Safari/iOS, hls.js everywhere else. ──
  const [hlsVideo, setHlsVideo] = createSignal<HTMLVideoElement | null>(null)
  let hlsPlayer: Hls | null = null
  function teardownHls() {
    if (hlsPlayer) {
      hlsPlayer.destroy()
      hlsPlayer = null
    }
  }
  createEffect(() => {
    const el = hlsVideo()
    if (playerMode() !== 'hls' || !el) {
      teardownHls()
      return
    }
    if (el.canPlayType('application/vnd.apple.mpegurl')) {
      el.src = '/hls/playlist.m3u8'
      void el.play().catch(() => { })
      return
    }
    void import('hls.js').then(({ default: Hls }) => {
      if (hlsVideo() !== el || playerMode() !== 'hls') return
      hlsPlayer = new Hls({
        lowLatencyMode: true,
        enableWorker: true,
        backBufferLength: 10,
        // The server playlist is a 5x2s sliding window (~10s behind live,
        // no LL-HLS parts): join 2 segments from the live edge and cap
        // forward buffering instead of piling up the full window.
        liveSyncDurationCount: 2,
        maxBufferLength: 6,
      })
      hlsPlayer.on(Hls.Events.ERROR, (_event, data) => {
        if (!data.fatal) return
        teardownHls()
        // Fatal: destroy and wrap to the top of the ladder (h264), which
        // may well have recovered since it was skipped.
        if (playerMode() === 'hls') setPlayerMode(cyclePlayerMode('hls'))
      })
      hlsPlayer.attachMedia(el)
      hlsPlayer.loadSource('/hls/playlist.m3u8')
    })
  })
  onCleanup(() => teardownHls())

  const [zoomRatio, setZoomRatio] = createSignal(1)
  const [torchOn, setTorchOn] = createSignal(false)
  const [talking, setTalking] = createSignal(false)

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
  const overlayEnabled = () => overlay()?.overlayEnabled ?? API_DEFAULTS.overlayEnabled
  const overlayPosition = () => overlay()?.overlayPosition ?? API_DEFAULTS.overlayPosition
  const overlayTextColor = () => overlay()?.overlayTextColor ?? API_DEFAULTS.overlayTextColor
  const overlayBgColor = () => overlay()?.overlayBackgroundColor ?? API_DEFAULTS.overlayBackgroundColor
  const overlayFontSize = () => overlay()?.overlayFontSize ?? API_DEFAULTS.overlayFontSize
  const overlayPadding = () => overlay()?.overlayPadding ?? API_DEFAULTS.overlayPadding
  const overlayLineHeight = () => overlay()?.overlayLineHeight ?? API_DEFAULTS.overlayLineHeight

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
      const clientCount = st()?.streaming?.clientCount ?? API_DEFAULTS.clientCount
      if (clientCount > 0) statusParts.push(`${clientCount} viewer${clientCount !== 1 ? 's' : ''}`)
      if (statusParts.length > 0) lines.push(statusParts.join('  '))
    }

    if (o.showCustomText && o.customText) {
      lines.push(o.customText)
    }

    return lines
  })

  // ── Continuous push-to-talk: mic → PCM16 chunks → WS /ws/talkback. ──
  // getUserMedia is secure-context-only, so on a plain-HTTP origin this
  // cannot run; the legacy one-shot uplink below still covers the case the
  // WS sidecar is down but the mic is up.
  let pttCtx: AudioContext | null = null
  let pttSocket: WebSocket | null = null
  let pttStream: MediaStream | null = null

  async function startPtt() {
    try {
      pttSocket = new WebSocket(`${wsBaseUrl()}/ws/talkback`)
      pttSocket.binaryType = 'arraybuffer'
      pttStream = await navigator.mediaDevices.getUserMedia({ audio: { sampleRate: 16000, channelCount: 1 } })
      pttCtx = new AudioContext({ sampleRate: 16000 })
      const source = pttCtx.createMediaStreamSource(pttStream)
      await tapPcm16(pttCtx, source, (chunk) => {
        if (!pttSocket || pttSocket.readyState !== WebSocket.OPEN) return
        pttSocket.send(floatChunkToPcm16(chunk))
      })
    } catch (err) {
      console.warn('PTT unavailable (mic or WS); falling back to one-shot capture', err)
      // Legacy one-shot: 1.5s POST upload — no mic streaming required beyond
      // the same getUserMedia this block already tried, so this only helps
      // when the WS sidecar is down but the mic is up.
      await stopPttCleanup()
      const captured = await captureOneShotPcm()
      if (captured) await pushTalkback(captured)
      setTalking(false)
    }
  }

  async function stopPtt() {
    try {
      pttSocket?.send('stop')
    } catch { }
    await stopPttCleanup()
    setTalking(false)
  }

  async function stopPttCleanup() {
    pttStream?.getTracks().forEach((t) => t.stop())
    pttStream = null
    if (pttCtx && pttCtx.state !== 'closed') await pttCtx.close().catch(() => {})
    pttCtx = null
    try { pttSocket?.close() } catch { }
    pttSocket = null
  }

  function floatChunkToPcm16(input: Float32Array): ArrayBuffer {
    const pcm = new Int16Array(input.length)
    for (let i = 0; i < input.length; i++) pcm[i] = Math.max(-32768, Math.min(32767, input[i] * 32768))
    return pcm.buffer
  }

  async function captureOneShotPcm(): Promise<ArrayBuffer | null> {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { sampleRate: 16000, channelCount: 1 } })
      const ctx = new AudioContext({ sampleRate: 16000 })
      const src = ctx.createMediaStreamSource(stream)
      const chunks: ArrayBuffer[] = []
      await tapPcm16(ctx, src, (chunk) => chunks.push(floatChunkToPcm16(chunk).slice(0)))
      await new Promise((r) => setTimeout(r, 1500))
      stream.getTracks().forEach((t) => t.stop())
      await ctx.close()
      const total = chunks.reduce((n, c) => n + c.byteLength, 0)
      const merged = new Uint8Array(total)
      let off = 0
      for (const c of chunks) {
        merged.set(new Uint8Array(c), off)
        off += c.byteLength
      }
      return merged.byteLength > 0 ? merged.buffer : null
    } catch {
      return null
    }
  }

  return (
    <section class="preview-section" id="preview-section">
      <div
        class="preview-container zoomable-container"
        classList={{ 'preview-active': webActive() && props.previewVisible() }}
        ref={zoom.containerRef}
      >
        {props.previewVisible() && webActive() ? (
          <Show when={playerMode() !== 'mjpeg'} fallback={
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
                // MJPEG used up its retries: drop down the ladder to HLS
                // (muxed A/V). With no HLS rung available, keep the slow
                // MJPEG auto-retry so the stream isn't permanently stuck.
                const next = nextPlayerMode('mjpeg', true, true, hlsSupported())
                setPreviewErrorCount(0)
                if (next === 'mjpeg') {
                  setTimeout(() => {
                    if (isActive()) {
                      props.setPreviewVisible(true)
                    }
                  }, 15_000)
                } else if (playerMode() === 'mjpeg') {
                  setPlayerMode(next)
                  if (isActive()) {
                    props.setPreviewVisible(true)
                  }
                }
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
          }>
          <Show when={playerMode() === 'h264'} fallback={
            <video
              ref={(el) => setHlsVideo(el)}
              class="preview-img zoomable-content"
              controls
              autoplay
              muted
              playsinline
              style={{ width: '100%', 'background-color': '#000' }}
            />
          }>
          <canvas
            ref={(el) => setH264Canvas(el)}
            class="preview-img zoomable-content"
            style={{ width: '100%', 'background-color': '#000' }}
          />
          </Show>
          </Show>
        ) : (
          <div class="preview-placeholder">
            <div class="preview-placeholder-icon">
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
                <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                <circle cx="12" cy="13" r="4" />
              </svg>
            </div>
            <span class="preview-placeholder-text">{webActive() ? 'Connecting...' : isActive() ? 'Stream error' : 'No active stream'}</span>
            <span class="preview-placeholder-sub">
              {!webStreamingEnabled() && !rtspEnabled()
                ? 'Enable Web Stream or RTSP in settings to start'
                : webStreamingEnabled() ? 'Click Web Stream to start the live feed' : 'Web streaming is disabled in settings'}
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
        <Show when={webActive() && props.previewVisible()}>
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
            class="action-btn action-btn-ghost"
            onClick={() => setPlayerMode(cyclePlayerMode(playerMode()))}
            title="Cycle player (H.264 → MJPEG → HLS)"
          >
            <span>{cyclePlayerMode(playerMode()).toUpperCase()}</span>
          </button>
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

          {webActive() ? (
            <button
              class="action-btn action-btn-warning"
              onClick={props.handleStopWebStream}
              disabled={props.streamActionLoading()}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="6" y="6" width="12" height="12" rx="2" />
              </svg>
              <span>Stop Web</span>
            </button>
          ) : (
            <button
              class="action-btn action-btn-success"
              onClick={props.handleStartWebStream}
              disabled={props.streamActionLoading() || !webStreamingEnabled()}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polygon points="5 3 19 12 5 21 5 3" />
              </svg>
              <span>Web Stream</span>
            </button>
          )}

          {rtspActive() ? (
            <button
              class="action-btn action-btn-warning"
              onClick={props.handleStopRtspStream}
              disabled={props.streamActionLoading()}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="6" y="6" width="12" height="12" rx="2" />
              </svg>
              <span>Stop RTSP</span>
            </button>
          ) : (
            <button
              class="action-btn action-btn-success"
              onClick={props.handleStartRtspStream}
              disabled={props.streamActionLoading() || !rtspEnabled()}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polygon points="5 3 19 12 5 21 5 3" />
              </svg>
              <span>RTSP Stream</span>
            </button>
          )}

          <a id="snapshot-btn" class="action-btn action-btn-ghost" href="/snapshot?highres=1&save=1" target="_blank" rel="noopener noreferrer" title="Download High-Res Snapshot">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            <span>Snap</span>
          </a>

          <label class="action-btn action-btn-ghost" title="Remote zoom">
            <span>{zoomRatio().toFixed(1)}x</span>
            <input
              type="range"
              min="1"
              max="8"
              step="0.5"
              value={zoomRatio()}
              onInput={async (e) => {
                const v = parseFloat(e.currentTarget.value)
                setZoomRatio(v)
                try {
                  await apiSetZoom(v)
                } catch (err) {
                  console.error('Remote zoom failed:', err)
                }
              }}
            />
          </label>

          <button
            class="action-btn action-btn-ghost"
            onClick={async () => {
              const next = !torchOn()
              setTorchOn(next)
              try {
                await apiSetTorch(next)
              } catch (err) {
                console.error('Torch failed:', err)
                setTorchOn(!next)
              }
            }}
            title="Toggle torch"
          >
            <span>{torchOn() ? 'Torch ON' : 'Torch'}</span>
          </button>

          <button
            class="action-btn action-btn-ghost"
            onPointerDown={async () => {
              setTalking(true)
              try {
                await startPtt()
              } catch (err) {
                console.error('Talkback failed:', err)
                await stopPtt()
              }
            }}
            onPointerUp={() => { void stopPtt() }}
            onPointerLeave={() => { if (talking()) void stopPtt() }}
            title="Hold to talk"
          >
            <span>{talking() ? 'Talking…' : 'Talk'}</span>
          </button>
        </div>

        <div class="preview-actions-right">
          <Show when={props.captureMsg()}>
            <span class="capture-msg">{props.captureMsg()}</span>
          </Show>
        </div>
      </div>

      {/* Audio status */}
      <Show when={webActive() && st()?.streaming?.audioEnabled}>
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
      <Show when={webActive() && st()?.streaming?.url}>
        <div class="stream-url-bar">
          <code>{st()!.streaming.url}</code>
        </div>
      </Show>

      {/* RTSP URL */}
      <Show when={rtspActive() && st()?.streaming?.rtspUrl}>
        <div class="stream-url-bar">
          <code>{st()!.streaming.rtspUrl}</code>
        </div>
      </Show>
    </section>
  )
}
