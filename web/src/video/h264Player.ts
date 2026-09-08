import { createSignal, onCleanup } from 'solid-js'

/**
 * H.264-over-WebSocket decoder for the dashboard (WebCodecs `VideoDecoder`),
 * the low-latency replacement for the MJPEG `<img>`: sub-second glass-to-glass
 * at a fraction of MJPEG's bandwidth. Server protocol (see WsVideoProtocol.kt):
 *   'LCCF' + avcC bytes  → decoder configuration
 *   'LCV1' + AVCC AU     → one decodable access unit
 * WebCodecs is not secure-context-gated, so it works on the plain-HTTP LAN
 * origin; browsers without support fall back to MJPEG (caller decides).
 */

export function h264Supported(): boolean {
  return typeof window !== 'undefined' &&
    'VideoDecoder' in window &&
    typeof (window as any).VideoDecoder === 'function'
}

// The WS sidecar listens one port above the main HTTP server. Hand-maintained
// mirror of StreamingManager's WS_PORT_OFFSET (the types.ts lockstep rule).
export const WS_PORT_OFFSET = 1

export function wsBaseUrl(): string {
  const loc = window.location
  const scheme = loc.protocol === 'https:' ? 'wss:' : 'ws:'
  const wsPort = parseInt(loc.port || '80', 10) + WS_PORT_OFFSET
  return `${scheme}//${loc.hostname}:${wsPort}`
}

interface FrameEnvelope {
  magic: string
  payload: ArrayBuffer
}

function parseEnvelope(buffer: ArrayBuffer): FrameEnvelope | null {
  if (buffer.byteLength < 8) return null
  const view = new DataView(buffer)
  const bytes = new Uint8Array(buffer, 0, 4)
  const magic = String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3])
  const length = view.getUint32(4)
  if (buffer.byteLength < 8 + length) return null
  return { magic, payload: buffer.slice(8, 8 + length) }
}

/** The server answers an open socket with the cached avcC config at once;
 *  silence past this window means the H.264 path is not producing (e.g. the
 *  RTSP output is off) — reported as an error so the caller can fall back. */
const CONFIG_TIMEOUT_MS = 5_000

export function createH264Player(options: { onStatus?: (s: 'idle' | 'playing' | 'error') => void } = {}) {
  const [status, setStatus] = createSignal<'idle' | 'playing' | 'error'>('idle')
  let socket: WebSocket | null = null
  let decoder: any = null
  let canvas: HTMLCanvasElement | null = null
  let renderCtx: CanvasRenderingContext2D | null = null
  let configTimer: ReturnType<typeof setTimeout> | null = null

  function clearConfigTimer() {
    if (configTimer !== null) {
      clearTimeout(configTimer)
      configTimer = null
    }
  }

  function handle(buffer: ArrayBuffer) {
    const envelope = parseEnvelope(buffer)
    if (!envelope) return
    if (envelope.magic === 'LCCF') {
      clearConfigTimer()
      configureDecoder(new Uint8Array(envelope.payload))
    } else if ((envelope.magic === 'LCV1' || envelope.magic === 'LCK1') && decoder) {
      decodeFrame(new Uint8Array(envelope.payload), envelope.magic === 'LCK1')
    }
  }

  function configureDecoder(description: Uint8Array) {
    try {
      decoder?.close()
    } catch { }
    decoder = new (window as any).VideoDecoder({
      output: (frame: any) => {
        renderFrame(frame)
        frame.close()
      },
      error: (e: any) => {
        console.error('H264 decoder error:', e)
        setStatus('error')
        options.onStatus?.('error')
      },
    })
    decoder.configure({
      codec: 'avc1.640028',
      description: description.slice().buffer,
      optimizeForLatency: true,
    })
  }

  function decodeFrame(avcc: Uint8Array, isKey: boolean) {
    if (!decoder || decoder.state !== 'configured') return
    try {
      decoder.decode(new (window as any).EncodedVideoChunk({
        type: isKey ? 'key' : 'delta',
        timestamp: performance.now() * 1000,
        data: avcc.slice().buffer,
      }))
    } catch (e) {
      console.warn('Decode failed:', e)
    }
  }

  function renderFrame(frame: any) {
    if (!canvas) return
    const width = frame.displayWidth || frame.codedWidth
    const height = frame.displayHeight || frame.codedHeight
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width
      canvas.height = height
    }
    renderCtx?.drawImage(frame, 0, 0, width, height)
  }

  function start(targetCanvas: HTMLCanvasElement) {
    canvas = targetCanvas
    renderCtx = canvas.getContext('2d')
    const url = `${wsBaseUrl()}/ws/video`
    try {
      socket = new WebSocket(url)
    } catch (e) {
      setStatus('error')
      options.onStatus?.('error')
      return
    }
    socket.binaryType = 'arraybuffer'
    socket.onopen = () => {
      setStatus('playing')
      options.onStatus?.('playing')
      // Open ≠ media: arm the config watchdog so a connected-but-silent
      // stream surfaces as an error (caller falls back to MJPEG) instead of
      // an endlessly black canvas.
      clearConfigTimer()
      configTimer = setTimeout(() => {
        if (!decoder) {
          setStatus('error')
          options.onStatus?.('error')
          try { socket?.close() } catch { }
        }
      }, CONFIG_TIMEOUT_MS)
    }
    socket.onmessage = (event) => {
      if (event.data instanceof ArrayBuffer) handle(event.data)
    }
    socket.onerror = () => {
      clearConfigTimer()
      setStatus('error')
      options.onStatus?.('error')
    }
    socket.onclose = () => {
      clearConfigTimer()
      if (status() === 'playing') {
        setStatus('idle')
        options.onStatus?.('idle')
      }
    }
  }

  function stop() {
    clearConfigTimer()
    try { socket?.close() } catch { }
    socket = null
    try { decoder?.close() } catch { }
    decoder = null
    setStatus('idle')
    options.onStatus?.('idle')
  }

  onCleanup(stop)

  return { status, start, stop }
}
