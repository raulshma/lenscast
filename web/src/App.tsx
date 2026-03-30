import { createEffect, createSignal, onCleanup, Show } from 'solid-js'
import * as api from './api/client'
import Gallery from './Gallery'
import { createRecordingTimer } from './RecordingTimer'
import type {
  AllSettings, DeviceStatus, LensInfo, CameraSettings,
  FocusMode, WhiteBalance, Resolution, HdrMode,
  IntervalCaptureConfig, RecordingConfig,
  CaptureMode, FlashMode, RecordingQuality,
} from './types'
import {
  RESOLUTION_LABELS, FOCUS_MODE_LABELS, WB_LABELS, HDR_LABELS, FRAME_RATE_OPTIONS,
  SCENE_MODE_OPTIONS, CAPTURE_MODE_LABELS, FLASH_MODE_LABELS, RECORDING_QUALITY_LABELS,
} from './types'
import './App.css'

function App() {
  const [authRequired, setAuthRequired] = createSignal(false)
  const [authenticated, setAuthenticated] = createSignal(false)
  const [loginUser, setLoginUser] = createSignal('')
  const [loginPass, setLoginPass] = createSignal('')
  const [loginError, setLoginError] = createSignal('')
  const [loginLoading, setLoginLoading] = createSignal(false)

  const [settings, setSettings] = createSignal<AllSettings | null>(null)
  const [status, setStatus] = createSignal<DeviceStatus | null>(null)
  const [lenses, setLenses] = createSignal<LensInfo[]>([])
  const [error, setError] = createSignal('')
  const [captureMsg, setCaptureMsg] = createSignal('')
  const [saving, setSaving] = createSignal(false)
  const [previewVisible, setPreviewVisible] = createSignal(true)
  const [streamActionLoading, setStreamActionLoading] = createSignal(false)
  const [streamNonce, setStreamNonce] = createSignal(0)
  const [showGallery, setShowGallery] = createSignal(false)

  const [intervalConfig, setIntervalConfig] = createSignal<IntervalCaptureConfig>({
    intervalSeconds: 5, totalCaptures: 100, imageQuality: 90,
    captureMode: 'MINIMIZE_LATENCY', flashMode: 'OFF',
  })
  const [intervalRunning, setIntervalRunning] = createSignal(false)
  const [intervalCompleted, setIntervalCompleted] = createSignal(0)

  const [recordingConfig, setRecordingConfig] = createSignal<RecordingConfig>({
    durationSeconds: 0, repeatIntervalSeconds: 0,
    quality: 'HIGH', maxFileSizeBytes: 0, includeAudio: true,
  })
  const [isRecording, setIsRecording] = createSignal(false)
  const [recordingElapsed, setRecordingElapsed] = createSignal(0)
  const recordingTimer = createRecordingTimer(isRecording, recordingElapsed)
  const [liveAudioStatus, setLiveAudioStatus] = createSignal<'idle' | 'connecting' | 'live' | 'error'>('idle')

  let saveTimer: ReturnType<typeof setTimeout> | null = null
  let liveAudioAbortController: AbortController | null = null
  let liveAudioContext: AudioContext | null = null
  let liveAudioPlaybackTime = 0
  let liveAudioSession = 0
  let liveAudioKey = ''

  function isPageHidden() {
    return typeof document !== 'undefined' && document.hidden
  }

  function concatBytes(a: Uint8Array, b: Uint8Array) {
    const merged = new Uint8Array(a.length + b.length)
    merged.set(a, 0)
    merged.set(b, a.length)
    return merged
  }

  async function stopLiveAudioPlayback(resetKey = true) {
    if (resetKey) {
      liveAudioKey = ''
    }
    liveAudioSession += 1
    liveAudioAbortController?.abort()
    liveAudioAbortController = null
    liveAudioPlaybackTime = 0
    if (liveAudioContext) {
      try {
        await liveAudioContext.close()
      } catch { }
      liveAudioContext = null
    }
    setLiveAudioStatus('idle')
  }

  async function ensureLiveAudioContext(sampleRate: number) {
    const AudioContextCtor = (window as any).AudioContext || (window as any).webkitAudioContext
    if (!AudioContextCtor) return null

    if (!liveAudioContext || liveAudioContext.state === 'closed') {
      liveAudioContext = new AudioContextCtor({
        latencyHint: 'interactive',
        sampleRate,
      })
    }

    if (liveAudioContext.state === 'suspended') {
      try {
        await liveAudioContext.resume()
      } catch { }
    }

    return liveAudioContext
  }

  function schedulePcmChunk(ctx: AudioContext, pcmBytes: Uint8Array, sampleRate: number, channelCount: number) {
    const int16 = new Int16Array(pcmBytes.buffer, pcmBytes.byteOffset, Math.floor(pcmBytes.byteLength / 2))
    const frameCount = Math.floor(int16.length / channelCount)
    if (frameCount <= 0) return

    const audioBuffer = ctx.createBuffer(channelCount, frameCount, sampleRate)
    for (let channel = 0; channel < channelCount; channel += 1) {
      const data = audioBuffer.getChannelData(channel)
      for (let i = 0; i < frameCount; i += 1) {
        data[i] = int16[i * channelCount + channel] / 32768
      }
    }

    const source = ctx.createBufferSource()
    source.buffer = audioBuffer
    source.connect(ctx.destination)

    const now = ctx.currentTime
    if (liveAudioPlaybackTime < now - 0.1 || liveAudioPlaybackTime > now + 0.35) {
      liveAudioPlaybackTime = now + 0.05
    }

    source.start(liveAudioPlaybackTime)
    liveAudioPlaybackTime += audioBuffer.duration
  }

  async function startLiveAudioPlayback(url: string) {
    await stopLiveAudioPlayback(false)

    const sessionId = ++liveAudioSession
    const controller = new AbortController()
    liveAudioAbortController = controller
    setLiveAudioStatus('connecting')

    try {
      const res = await fetch(url, {
        cache: 'no-store',
        signal: controller.signal,
      })
      if (!res.ok || !res.body) {
        throw new Error(`Audio stream unavailable: ${res.status}`)
      }

      const sampleRate = parseInt(res.headers.get('X-Audio-Sample-Rate') || '48000', 10)
      const channelCount = parseInt(res.headers.get('X-Audio-Channels') || '1', 10)
      const bytesPerFrame = 2 * channelCount
      const ctx = await ensureLiveAudioContext(sampleRate)
      if (!ctx) throw new Error('Web Audio not supported')

      setLiveAudioStatus('live')
      liveAudioPlaybackTime = ctx.currentTime + 0.05

      const reader = res.body.getReader()
      let pending = new Uint8Array(0)

      while (sessionId === liveAudioSession) {
        const { value, done } = await reader.read()
        if (done) break
        if (!value || value.length === 0) continue

        const merged = pending.length > 0 ? concatBytes(pending, value) : value
        const usableLength = merged.length - (merged.length % bytesPerFrame)
        if (usableLength > 0) {
          schedulePcmChunk(ctx, merged.subarray(0, usableLength), sampleRate, channelCount)
        }
        pending = usableLength < merged.length ? merged.subarray(usableLength) : new Uint8Array(0)
      }

      if (!controller.signal.aborted && sessionId === liveAudioSession) {
        setLiveAudioStatus('idle')
      }
    } catch (e) {
      if (!controller.signal.aborted) {
        setLiveAudioStatus('error')
      }
    }
  }

  function debounceSave(fn: () => void, ms = 400) {
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(fn, ms)
  }

  async function checkAuth() {
    try {
      const authStatus = await api.getAuthStatus()
      if (!authStatus.required) {
        setAuthRequired(false)
        setAuthenticated(true)
        return
      }
      setAuthRequired(true)
      try {
        await api.getSettings()
        setAuthenticated(true)
      } catch {
        setAuthenticated(false)
      }
    } catch {
      setAuthRequired(false)
      setAuthenticated(true)
    }
  }

  async function handleLogin(e: Event) {
    e.preventDefault()
    if (loginLoading()) return
    setLoginLoading(true)
    setLoginError('')
    try {
      await api.login(loginUser(), loginPass())
      setAuthenticated(true)
    } catch (e: any) {
      setLoginError(e.message || 'Login failed')
    } finally {
      setLoginLoading(false)
    }
  }

  async function handleLogout() {
    try {
      await api.logout()
    } catch { }
    setAuthenticated(false)
    setSettings(null)
    setStatus(null)
  }

  async function fetchSettings() {
    try {
      const s = await api.getSettings()
      setSettings(s)
      setRecordingConfig((current) => ({
        ...current,
        includeAudio: s.streaming?.recordingAudioEnabled ?? current.includeAudio,
      }))
      setError('')
    } catch (e: any) {
      if (e.message?.includes('401') || e.message?.includes('Authentication')) {
        setAuthenticated(false)
        return
      }
      setError(e.message)
    }
  }

  async function fetchStatus() {
    try {
      const s = await api.getStatus()
      setStatus(s)
    } catch (e: any) {
      if (e.message?.includes('401')) {
        setAuthenticated(false)
      }
    }
  }

  async function fetchLenses() {
    try {
      const r = await api.getLenses()
      setLenses(r.lenses)
    } catch (e: any) {
      if (e.message?.includes('401')) {
        setAuthenticated(false)
      }
    }
  }

  async function fetchIntervalStatus() {
    try {
      const s = await api.getIntervalCaptureStatus()
      setIntervalRunning(s.isRunning)
      setIntervalCompleted(s.completedCaptures)
    } catch { }
  }

  async function fetchRecordingStatus() {
    try {
      const s = await api.getRecordingStatus()
      setIsRecording(s.isRecording)
      setRecordingElapsed(s.elapsedSeconds)
    } catch { }
  }

  createEffect(() => {
    checkAuth()
  })

  createEffect(() => {
    if (!authenticated()) return

    const refreshDashboard = (force = false) => {
      if (!force && isPageHidden()) return

      void fetchStatus()

      if (force || !st()?.streaming?.isActive) {
        void fetchSettings()
        void fetchLenses()
      }

      void fetchIntervalStatus()
      void fetchRecordingStatus()
    }

    refreshDashboard(true)

    const settingsInterval = setInterval(() => {
      if (isPageHidden() || st()?.streaming?.isActive) return
      void fetchSettings()
    }, 30000)
    const statusInterval = setInterval(() => {
      if (isPageHidden()) return
      void fetchStatus()
    }, 3000)
    const lensesInterval = setInterval(() => {
      if (isPageHidden()) return
      void fetchLenses()
    }, 30000)
    const intervalStatusInterval = setInterval(() => {
      if (isPageHidden()) return
      void fetchIntervalStatus()
    }, 5000)
    const recordingStatusInterval = setInterval(() => {
      if (isPageHidden()) return
      void fetchRecordingStatus()
    }, 3000)
    const handleVisibility = () => {
      if (!document.hidden) {
        refreshDashboard(true)
      }
    }
    document.addEventListener('visibilitychange', handleVisibility)
    onCleanup(() => {
      clearInterval(settingsInterval)
      clearInterval(statusInterval)
      clearInterval(lensesInterval)
      clearInterval(intervalStatusInterval)
      clearInterval(recordingStatusInterval)
      document.removeEventListener('visibilitychange', handleVisibility)
    })
  })

  async function saveSettings(partial: Partial<AllSettings>) {
    setSaving(true)
    try {
      await api.updateSettings(partial)
      setError('')
    } catch (e: any) {
      setError(e.message)
    }
    setSaving(false)
  }

  function updateCamera(patch: Partial<CameraSettings>) {
    const current = settings()
    if (!current) return
    const newCam = { ...current.camera, ...patch }
    setSettings({ ...current, camera: newCam })
    debounceSave(() => saveSettings({ camera: newCam }))
  }

  async function handleCapture() {
    setCaptureMsg('Capturing...')
    const result = await api.capturePhoto()
    setCaptureMsg(result.success ? `Captured: ${result.fileName}` : `Failed: ${result.error}`)
    setTimeout(() => setCaptureMsg(''), 4000)
  }

  async function handleSelectLens(index: number) {
    await api.selectLens(index)
    fetchLenses()
    fetchSettings()
  }

  async function handleResetDefaults() {
    const defaults: CameraSettings = {
      exposureCompensation: 0, iso: null, exposureTime: null,
      focusMode: 'AUTO', focusDistance: null, whiteBalance: 'AUTO',
      colorTemperature: null, zoomRatio: 1.0, frameRate: 30,
      resolution: 'FHD_1080P', stabilization: true, hdrMode: 'OFF', sceneMode: null,
    }
    await saveSettings({ camera: defaults })
    fetchSettings()
  }

  async function handleStopStream() {
    if (streamActionLoading()) return
    setStreamActionLoading(true)
    try {
      const result = await api.stopStream()
      if (!result.success) {
        setError(result.error || 'Failed to stop stream')
      } else {
        setError('')
        setPreviewVisible(false)
        setStreamNonce((v) => v + 1)
        await fetchStatus()
      }
    } catch (e: any) {
      setError(e.message || 'Failed to stop stream')
    } finally {
      setStreamActionLoading(false)
    }
  }

  async function handleResumeStream() {
    if (streamActionLoading()) return
    setStreamActionLoading(true)
    try {
      const result = await api.startStream()
      if (!result.success) {
        setError(result.error || 'Failed to resume stream')
      } else {
        setError('')
        setPreviewVisible(true)
        setStreamNonce((v) => v + 1)
        await fetchStatus()
      }
    } catch (e: any) {
      setError(e.message || 'Failed to resume stream')
    } finally {
      setStreamActionLoading(false)
    }
  }

  async function handleStartIntervalCapture() {
    try {
      const result = await api.startIntervalCapture(intervalConfig())
      if (!result.success) {
        setError(result.error || 'Failed to start interval capture')
      } else {
        setError('')
        setIntervalRunning(true)
        setIntervalCompleted(0)
      }
    } catch (e: any) {
      setError(e.message || 'Failed to start interval capture')
    }
  }

  async function handleStopIntervalCapture() {
    try {
      const result = await api.stopIntervalCapture()
      if (!result.success) {
        setError(result.error || 'Failed to stop interval capture')
      } else {
        setError('')
        setIntervalRunning(false)
      }
    } catch (e: any) {
      setError(e.message || 'Failed to stop interval capture')
    }
  }

  async function handleStartRecording() {
    try {
      const result = await api.startRecording(recordingConfig())
      if (!result.success) {
        setError(result.error || 'Failed to start recording')
      } else {
        setError('')
        setIsRecording(true)
        setRecordingElapsed(0)
      }
    } catch (e: any) {
      setError(e.message || 'Failed to start recording')
    }
  }

  async function handleStopRecording() {
    try {
      const result = await api.stopRecording()
      if (!result.success) {
        setError(result.error || 'Failed to stop recording')
      } else {
        setError('')
        setIsRecording(false)
      }
    } catch (e: any) {
      setError(e.message || 'Failed to stop recording')
    }
  }

  createEffect(() => {
    if (status()?.streaming?.isActive) {
      setPreviewVisible(true)
    }
  })

  createEffect(() => {
    const streaming = st()?.streaming
    const nonce = streamNonce()

    if (!streaming?.isActive || !streaming.audioEnabled) {
      void stopLiveAudioPlayback()
      return
    }

    const nextKey = `${streaming.isActive}:${streaming.audioEnabled}:${nonce}`
    if (nextKey === liveAudioKey) return
    liveAudioKey = nextKey

    void startLiveAudioPlayback(`/audio?t=${nonce}`)
  })

  onCleanup(() => {
    void stopLiveAudioPlayback()
  })

  const s = () => settings()
  const st = () => status()

  function fmtFocalLength(fl: number): string {
    if (fl <= 0) return ''
    return Number.isInteger(fl) ? `${fl}mm` : `${fl.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')}mm`
  }

  return (
    <div class="app" data-theme="dark">
      <Show when={authRequired() && !authenticated()} fallback={
        <>
          {/* Navbar */}
          <div class="navbar bg-base-300 border-b border-base-200 px-4 min-h-12">
            <div class="flex-1 flex items-center gap-3">
              <span class="font-bold text-primary text-lg tracking-wide">LensCast</span>
              <span class="badge badge-ghost badge-sm font-mono">{st()?.camera || '...'}</span>
            </div>
            <div class="flex-none flex items-center gap-2">
              <div
                class="badge badge-outline gap-1"
                classList={{
                  'badge-success': true,
                  'badge-warning': st()?.battery?.isCharging,
                }}
              >
                <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
                </svg>
                {st()?.battery?.level ?? '--'}%
              </div>
              <div
                class="badge badge-outline"
                classList={{
                  'badge-success': !st()?.thermal || st()?.thermal === 'NONE',
                  'badge-warning': st()?.thermal === 'MODERATE' || st()?.thermal === 'LIGHT',
                  'badge-error': st()?.thermal === 'SEVERE' || st()?.thermal === 'CRITICAL',
                }}
              >
                {st()?.thermal || '--'}
              </div>
              <div class="badge badge-outline badge-info">
                {st()?.streaming?.clientCount ?? 0} client{(st()?.streaming?.clientCount ?? 0) !== 1 ? 's' : ''}
              </div>
              <button class="btn btn-ghost btn-xs gap-1" onClick={() => setShowGallery(true)}>
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                </svg>
                Gallery
              </button>
              <div
                class="badge badge-outline"
                classList={{
                  'badge-warning': saving(),
                  'badge-ghost': !saving(),
                }}
              >
                {saving() ? 'Saving...' : 'Saved'}
              </div>
              <Show when={authRequired()}>
                <button class="btn btn-ghost btn-xs" onClick={handleLogout}>
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
                  </svg>
                </button>
              </Show>
            </div>
          </div>

          {/* Main Content */}
          <div class="main-grid">
            {/* Preview Section */}
            <section class="preview-section">
              <div class="preview-container">
                {previewVisible() && st()?.streaming?.isActive ? (
                  <img
                    class="preview-img"
                    src={`/stream?t=${streamNonce()}`}
                    alt="Live camera stream"
                    draggable={false}
                    loading="eager"
                    decoding="async"
                    onError={() => setPreviewVisible(false)}
                  />
                ) : (
                  <div class="preview-placeholder">
                    <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                      <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                      <circle cx="12" cy="13" r="4" />
                    </svg>
                    <span class="text-sm">{st()?.streaming?.isActive ? 'Stream error' : 'Stream not active'}</span>
                  </div>
                )}
                <Show when={isRecording()}>
                  <div class="recording-timer-overlay">
                    <span class="recording-dot" />
                    <span class="recording-time">{recordingTimer.formatElapsed()}</span>
                  </div>
                </Show>
              </div>
              <div class="flex items-center gap-2 flex-shrink-0">
                <button
                  class="btn btn-primary btn-sm"
                  onClick={handleCapture}
                  disabled={!st()?.streaming?.isActive}
                >
                  Capture Photo
                </button>
                {st()?.streaming?.isActive ? (
                  <button
                    class="btn btn-warning btn-sm btn-outline"
                    onClick={handleStopStream}
                    disabled={streamActionLoading()}
                  >
                    {streamActionLoading() ? (
                      <span class="loading loading-spinner loading-xs"></span>
                    ) : null}
                    {streamActionLoading() ? 'Stopping...' : 'Stop Stream'}
                  </button>
                ) : (
                  <button
                    class="btn btn-success btn-sm btn-outline"
                    onClick={handleResumeStream}
                    disabled={streamActionLoading()}
                  >
                    {streamActionLoading() ? (
                      <span class="loading loading-spinner loading-xs"></span>
                    ) : null}
                    {streamActionLoading() ? 'Starting...' : 'Resume Stream'}
                  </button>
                )}
                <a class="btn btn-ghost btn-sm" href="/snapshot" target="_blank" download>
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
                  </svg>
                  Snapshot
                </a>
                {captureMsg() && (
                  <span class="text-xs text-success animate-pulse">{captureMsg()}</span>
                )}
              </div>
              {st()?.streaming?.isActive && st()?.streaming?.audioEnabled ? (
                <div class="bg-base-300 rounded-lg px-3 py-3 border border-base-200 flex-shrink-0">
                  <div class="text-[11px] uppercase tracking-widest text-base-content/60 mb-2">Live Audio</div>
                  <div class="text-xs">
                    {liveAudioStatus() === 'live' ? 'Playing live audio' :
                      liveAudioStatus() === 'connecting' ? 'Connecting audio...' :
                        liveAudioStatus() === 'error' ? 'Audio playback error' :
                          'Audio idle'}
                  </div>
                </div>
              ) : st()?.streaming?.isActive ? (
                <div class="text-xs text-base-content/60 flex-shrink-0">
                  Microphone audio is unavailable for this stream.
                </div>
              ) : null}
              {st()?.streaming?.isActive && st()?.streaming?.url && (
                <div class="bg-base-300 rounded-lg px-3 py-2 border border-base-200 flex-shrink-0">
                  <code class="text-xs text-primary font-mono">{st()!.streaming.url}</code>
                </div>
              )}
            </section>

            {/* Settings Panel */}
            <section class="settings-scroll">
              {error() && (
                <div class="alert alert-error alert-soft text-sm py-2">
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                  <span>{error()}</span>
                </div>
              )}

              {/* Lens Selection */}
              <div class="card bg-base-200 shadow-sm">
                <div class="card-body p-4">
                  <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Lens</h3>
                  <div class="lens-grid">
                    {lenses().map((lens) => (
                      <button
                        class="btn btn-sm flex-col h-auto py-2"
                        classList={{
                          'btn-primary': lens.selected,
                          'btn-ghost border border-base-300': !lens.selected,
                        }}
                        onClick={() => handleSelectLens(lens.index)}
                      >
                        <span class="font-semibold text-xs">{lens.label}</span>
                        <span class="text-[10px] opacity-70">{fmtFocalLength(lens.focalLength)}</span>
                      </button>
                    ))}
                  </div>
                </div>
              </div>

              {/* Exposure */}
              <div class="card bg-base-200 shadow-sm">
                <div class="card-body p-4">
                  <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Exposure</h3>
                  <div class="form-control">
                    <label class="label py-1">
                      <span class="label-text text-xs">Exposure Compensation</span>
                      <span class="badge badge-primary badge-xs font-mono">
                        {s()?.camera?.exposureCompensation ?? 0}
                      </span>
                    </label>
                    <input
                      type="range"
                      class="range range-primary range-xs"
                      min={-12}
                      max={12}
                      value={s()?.camera?.exposureCompensation ?? 0}
                      onInput={(e) => updateCamera({ exposureCompensation: parseFloat(e.currentTarget.value) })}
                    />
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">ISO</span>
                    </label>
                    <div class="flex gap-2">
                      <select
                        class="select select-bordered select-sm w-24"
                        value={s()?.camera?.iso == null ? 'auto' : 'manual'}
                        onChange={(e) => {
                          if (e.currentTarget.value === 'auto') {
                            updateCamera({ iso: null })
                          } else {
                            updateCamera({ iso: s()?.camera?.iso ?? 800 })
                          }
                        }}
                      >
                        <option value="auto">Auto</option>
                        <option value="manual">Manual</option>
                      </select>
                      {s()?.camera?.iso != null && (
                        <input
                          type="number"
                          class="input input-bordered input-sm flex-1"
                          value={s()?.camera?.iso ?? ''}
                          min={100}
                          max={32000}
                          onChange={(e) => updateCamera({ iso: parseInt(e.currentTarget.value) || null })}
                        />
                      )}
                    </div>
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Exposure Time</span>
                    </label>
                    <div class="flex gap-2">
                      <select
                        class="select select-bordered select-sm w-24"
                        value={s()?.camera?.exposureTime == null ? 'auto' : 'manual'}
                        onChange={(e) => {
                          if (e.currentTarget.value === 'auto') {
                            updateCamera({ exposureTime: null })
                          } else {
                            updateCamera({ exposureTime: s()?.camera?.exposureTime ?? 10000000 })
                          }
                        }}
                      >
                        <option value="auto">Auto</option>
                        <option value="manual">Manual</option>
                      </select>
                      {s()?.camera?.exposureTime != null && (
                        <input
                          type="number"
                          class="input input-bordered input-sm flex-1"
                          value={s()?.camera?.exposureTime ?? ''}
                          min={100000}
                          max={500000000}
                          step={1000000}
                          placeholder="ns"
                          onChange={(e) => updateCamera({ exposureTime: parseInt(e.currentTarget.value) || null })}
                        />
                      )}
                    </div>
                    {s()?.camera?.exposureTime != null && (
                      <span class="text-[10px] opacity-50 mt-1">
                        {(s()!.camera.exposureTime! / 1_000_000).toFixed(1)}ms ({(s()!.camera.exposureTime! / 1_000_000_000).toFixed(3)}s)
                      </span>
                    )}
                  </div>
                </div>
              </div>

              {/* Focus */}
              <div class="card bg-base-200 shadow-sm">
                <div class="card-body p-4">
                  <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Focus</h3>
                  <div class="form-control">
                    <label class="label py-1">
                      <span class="label-text text-xs">Focus Mode</span>
                    </label>
                    <select
                      class="select select-bordered select-sm"
                      value={s()?.camera?.focusMode ?? 'AUTO'}
                      onChange={(e) => updateCamera({ focusMode: e.currentTarget.value as FocusMode })}
                    >
                      {Object.entries(FOCUS_MODE_LABELS).map(([k, v]) => (
                        <option value={k}>{v}</option>
                      ))}
                    </select>
                  </div>
                  {s()?.camera?.focusMode === 'MANUAL' && (
                    <div class="form-control mt-2">
                      <label class="label py-1">
                        <span class="label-text text-xs">Focus Distance</span>
                        <span class="badge badge-primary badge-xs font-mono">
                          {(s()?.camera?.focusDistance ?? 0).toFixed(1)}
                        </span>
                      </label>
                      <input
                        type="range"
                        class="range range-primary range-xs"
                        min={0}
                        max={10}
                        step={0.1}
                        value={s()?.camera?.focusDistance ?? 0}
                        onInput={(e) => updateCamera({ focusDistance: parseFloat(e.currentTarget.value) })}
                      />
                    </div>
                  )}
                </div>
              </div>

              {/* White Balance */}
              <div class="card bg-base-200 shadow-sm">
                <div class="card-body p-4">
                  <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">White Balance</h3>
                  <div class="form-control">
                    <label class="label py-1">
                      <span class="label-text text-xs">White Balance</span>
                    </label>
                    <select
                      class="select select-bordered select-sm"
                      value={s()?.camera?.whiteBalance ?? 'AUTO'}
                      onChange={(e) => updateCamera({ whiteBalance: e.currentTarget.value as WhiteBalance })}
                    >
                      {Object.entries(WB_LABELS).map(([k, v]) => (
                        <option value={k}>{v}</option>
                      ))}
                    </select>
                  </div>
                  {s()?.camera?.whiteBalance === 'MANUAL' && (
                    <div class="form-control mt-2">
                      <label class="label py-1">
                        <span class="label-text text-xs">Color Temperature</span>
                        <span class="badge badge-primary badge-xs font-mono">
                          {s()?.camera?.colorTemperature ?? 5500}K
                        </span>
                      </label>
                      <input
                        type="range"
                        class="range range-primary range-xs"
                        min={2000}
                        max={9000}
                        step={100}
                        value={s()?.camera?.colorTemperature ?? 5500}
                        onInput={(e) => updateCamera({ colorTemperature: parseFloat(e.currentTarget.value) })}
                      />
                    </div>
                  )}
                </div>
              </div>

              {/* Zoom & Frame */}
              <div class="card bg-base-200 shadow-sm">
                <div class="card-body p-4">
                  <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Zoom & Frame</h3>
                  <div class="form-control">
                    <label class="label py-1">
                      <span class="label-text text-xs">Zoom</span>
                      <span class="badge badge-primary badge-xs font-mono">
                        {(s()?.camera?.zoomRatio ?? 1).toFixed(1)}x
                      </span>
                    </label>
                    <input
                      type="range"
                      class="range range-primary range-xs"
                      min={1}
                      max={10}
                      step={0.1}
                      value={s()?.camera?.zoomRatio ?? 1}
                      onInput={(e) => updateCamera({ zoomRatio: parseFloat(e.currentTarget.value) })}
                    />
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Frame Rate</span>
                    </label>
                    <select
                      class="select select-bordered select-sm"
                      value={s()?.camera?.frameRate ?? 30}
                      onChange={(e) => updateCamera({ frameRate: parseInt(e.currentTarget.value) })}
                    >
                      {FRAME_RATE_OPTIONS.map((r) => (
                        <option value={r}>{r} fps</option>
                      ))}
                    </select>
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Resolution</span>
                    </label>
                    <select
                      class="select select-bordered select-sm"
                      value={s()?.camera?.resolution ?? 'FHD_1080P'}
                      onChange={(e) => updateCamera({ resolution: e.currentTarget.value as Resolution })}
                    >
                      {Object.entries(RESOLUTION_LABELS).map(([k, v]) => (
                        <option value={k}>{v}</option>
                      ))}
                    </select>
                  </div>
                </div>
              </div>

              {/* Effects */}
              <div class="card bg-base-200 shadow-sm">
                <div class="card-body p-4">
                  <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Effects</h3>
                  <div class="form-control">
                    <label class="label py-1">
                      <span class="label-text text-xs">Scene Mode</span>
                    </label>
                    <select
                      class="select select-bordered select-sm"
                      value={s()?.camera?.sceneMode ?? ''}
                      onChange={(e) => {
                        const v = e.currentTarget.value
                        updateCamera({ sceneMode: v === '' ? null : v })
                      }}
                    >
                      {SCENE_MODE_OPTIONS.map((opt) => (
                        <option value={opt.value}>{opt.label}</option>
                      ))}
                    </select>
                  </div>
                  <div class="form-control mt-2">
                    <label class="label cursor-pointer py-1">
                      <span class="label-text text-xs">Stabilization</span>
                      <input
                        type="checkbox"
                        class="toggle toggle-primary toggle-sm"
                        checked={s()?.camera?.stabilization ?? true}
                        onChange={() => updateCamera({ stabilization: !(s()?.camera?.stabilization ?? true) })}
                      />
                    </label>
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">HDR</span>
                    </label>
                    <select
                      class="select select-bordered select-sm"
                      value={s()?.camera?.hdrMode ?? 'OFF'}
                      onChange={(e) => updateCamera({ hdrMode: e.currentTarget.value as HdrMode })}
                    >
                      {Object.entries(HDR_LABELS).map(([k, v]) => (
                        <option value={k}>{v}</option>
                      ))}
                    </select>
                  </div>
                </div>
              </div>

              {/* Streaming */}
              <div class="card bg-base-200 shadow-sm">
                <div class="card-body p-4">
                  <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Streaming</h3>
                  <div class="form-control">
                    <label class="label py-1">
                      <span class="label-text text-xs">JPEG Quality</span>
                      <span class="badge badge-primary badge-xs font-mono">
                        {s()?.streaming?.jpegQuality ?? 80}%
                      </span>
                    </label>
                    <input
                      type="range"
                      class="range range-primary range-xs"
                      min={10}
                      max={100}
                      step={5}
                      value={s()?.streaming?.jpegQuality ?? 80}
                      onInput={(e) => {
                        const v = parseInt(e.currentTarget.value)
                        const current = settings()
                        if (current) {
                          setSettings({ ...current, streaming: { ...current.streaming, jpegQuality: v } })
                        }
                        debounceSave(() => saveSettings({ streaming: { jpegQuality: v } }))
                      }}
                    />
                  </div>
                  <div class="form-control mt-2">
                    <label class="label cursor-pointer py-1">
                      <span class="label-text text-xs">Show Preview on Device</span>
                      <input
                        type="checkbox"
                        class="toggle toggle-primary toggle-sm"
                        checked={s()?.streaming?.showPreview ?? true}
                        onChange={() => {
                          const newVal = !(s()?.streaming?.showPreview ?? true)
                          const current = settings()
                          if (current) {
                            setSettings({ ...current, streaming: { ...current.streaming, showPreview: newVal } })
                          }
                          saveSettings({ streaming: { showPreview: newVal } })
                        }}
                      />
                    </label>
                  </div>
                  <div class="form-control mt-2">
                    <label class="label cursor-pointer py-1">
                      <span class="label-text text-xs">Include Audio in Live Stream</span>
                      <input
                        type="checkbox"
                        class="toggle toggle-primary toggle-sm"
                        checked={s()?.streaming?.streamAudioEnabled ?? true}
                        onChange={() => {
                          const current = settings()
                          if (!current) return
                          const newVal = !(current.streaming.streamAudioEnabled ?? true)
                          const nextStreaming = { ...current.streaming, streamAudioEnabled: newVal }
                          setSettings({ ...current, streaming: nextStreaming })
                          saveSettings({ streaming: nextStreaming })
                        }}
                      />
                    </label>
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Live Audio Bitrate</span>
                      <span class="badge badge-primary badge-xs font-mono">
                        {s()?.streaming?.streamAudioBitrateKbps ?? 128} kbps
                      </span>
                    </label>
                    <input
                      type="range"
                      class="range range-primary range-xs"
                      min={32}
                      max={320}
                      step={16}
                      value={s()?.streaming?.streamAudioBitrateKbps ?? 128}
                      onInput={(e) => {
                        const v = parseInt(e.currentTarget.value)
                        const current = settings()
                        if (current) {
                          const nextStreaming = { ...current.streaming, streamAudioBitrateKbps: v }
                          setSettings({ ...current, streaming: nextStreaming })
                          debounceSave(() => saveSettings({ streaming: nextStreaming }))
                        }
                      }}
                    />
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Live Audio Channels</span>
                    </label>
                    <select
                      class="select select-bordered select-sm"
                      value={`${s()?.streaming?.streamAudioChannels ?? 1}`}
                      onChange={(e) => {
                        const v = parseInt(e.currentTarget.value)
                        const current = settings()
                        if (current) {
                          const nextStreaming = { ...current.streaming, streamAudioChannels: v }
                          setSettings({ ...current, streaming: nextStreaming })
                          saveSettings({ streaming: nextStreaming })
                        }
                      }}
                    >
                      <option value="1">Mono</option>
                      <option value="2">Stereo</option>
                    </select>
                  </div>
                  <div class="form-control mt-2">
                    <label class="label cursor-pointer py-1">
                      <span class="label-text text-xs">Default Recording Audio</span>
                      <input
                        type="checkbox"
                        class="toggle toggle-primary toggle-sm"
                        checked={s()?.streaming?.recordingAudioEnabled ?? true}
                        onChange={() => {
                          const current = settings()
                          if (!current) return
                          const newVal = !(current.streaming.recordingAudioEnabled ?? true)
                          const nextStreaming = { ...current.streaming, recordingAudioEnabled: newVal }
                          setSettings({ ...current, streaming: nextStreaming })
                          saveSettings({ streaming: nextStreaming })
                          setRecordingConfig({ ...recordingConfig(), includeAudio: newVal })
                        }}
                      />
                    </label>
                  </div>
                </div>
              </div>


              {/* Interval Capture */}
              <div class="card bg-base-200 shadow-sm">
                <div class="card-body p-4">
                  <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Interval Capture</h3>
                  {intervalRunning() && (
                    <div class="alert alert-info alert-soft text-sm py-2 mb-2">
                      <span>Running: {intervalCompleted()} captures completed</span>
                    </div>
                  )}
                  <div class="form-control">
                    <label class="label py-1">
                      <span class="label-text text-xs">Interval (seconds)</span>
                      <span class="badge badge-primary badge-xs font-mono">
                        {intervalConfig().intervalSeconds}s
                      </span>
                    </label>
                    <input
                      type="range"
                      class="range range-primary range-xs"
                      min={1}
                      max={3600}
                      value={intervalConfig().intervalSeconds}
                      onInput={(e) => setIntervalConfig({ ...intervalConfig(), intervalSeconds: parseInt(e.currentTarget.value) })}
                      disabled={intervalRunning()}
                    />
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Total Captures</span>
                      <span class="badge badge-primary badge-xs font-mono">
                        {intervalConfig().totalCaptures}
                      </span>
                    </label>
                    <input
                      type="range"
                      class="range range-primary range-xs"
                      min={1}
                      max={1000}
                      value={intervalConfig().totalCaptures}
                      onInput={(e) => setIntervalConfig({ ...intervalConfig(), totalCaptures: parseInt(e.currentTarget.value) })}
                      disabled={intervalRunning()}
                    />
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Image Quality</span>
                      <span class="badge badge-primary badge-xs font-mono">
                        {intervalConfig().imageQuality}%
                      </span>
                    </label>
                    <input
                      type="range"
                      class="range range-primary range-xs"
                      min={10}
                      max={100}
                      value={intervalConfig().imageQuality}
                      onInput={(e) => setIntervalConfig({ ...intervalConfig(), imageQuality: parseInt(e.currentTarget.value) })}
                      disabled={intervalRunning()}
                    />
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Capture Mode</span>
                    </label>
                    <select
                      class="select select-bordered select-sm"
                      value={intervalConfig().captureMode}
                      onChange={(e) => setIntervalConfig({ ...intervalConfig(), captureMode: e.currentTarget.value as CaptureMode })}
                      disabled={intervalRunning()}
                    >
                      {Object.entries(CAPTURE_MODE_LABELS).map(([k, v]) => (
                        <option value={k}>{v}</option>
                      ))}
                    </select>
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Flash Mode</span>
                    </label>
                    <select
                      class="select select-bordered select-sm"
                      value={intervalConfig().flashMode}
                      onChange={(e) => setIntervalConfig({ ...intervalConfig(), flashMode: e.currentTarget.value as FlashMode })}
                      disabled={intervalRunning()}
                    >
                      {Object.entries(FLASH_MODE_LABELS).map(([k, v]) => (
                        <option value={k}>{v}</option>
                      ))}
                    </select>
                  </div>
                  <div class="mt-3">
                    {intervalRunning() ? (
                      <button
                        class="btn btn-error btn-outline btn-sm w-full"
                        onClick={handleStopIntervalCapture}
                      >
                        Stop Interval Capture
                      </button>
                    ) : (
                      <button
                        class="btn btn-primary btn-sm w-full"
                        onClick={handleStartIntervalCapture}
                        disabled={!st()?.streaming?.isActive}
                      >
                        Start Interval Capture
                      </button>
                    )}
                  </div>
                </div>
              </div>

              {/* Recording */}
              <div class="card bg-base-200 shadow-sm">
                <div class="card-body p-4">
                  <h3 class="text-xs font-semibold uppercase tracking-widest text-base-content/60 mb-3">Recording</h3>
                  {isRecording() && (
                    <div class="alert alert-error alert-soft text-sm py-2 mb-2">
                      <span>Recording: {recordingTimer.formatElapsed()}</span>
                    </div>
                  )}
                  <div class="form-control">
                    <label class="label cursor-pointer py-1">
                      <span class="label-text text-xs">Include Audio</span>
                      <input
                        type="checkbox"
                        class="toggle toggle-primary toggle-sm"
                        checked={recordingConfig().includeAudio}
                        onChange={() => setRecordingConfig({ ...recordingConfig(), includeAudio: !recordingConfig().includeAudio })}
                        disabled={isRecording()}
                      />
                    </label>
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Quality</span>
                    </label>
                    <select
                      class="select select-bordered select-sm"
                      value={recordingConfig().quality}
                      onChange={(e) => setRecordingConfig({ ...recordingConfig(), quality: e.currentTarget.value as RecordingQuality })}
                      disabled={isRecording()}
                    >
                      {Object.entries(RECORDING_QUALITY_LABELS).map(([k, v]) => (
                        <option value={k}>{v}</option>
                      ))}
                    </select>
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Duration (seconds, 0 = unlimited)</span>
                      <span class="badge badge-primary badge-xs font-mono">
                        {recordingConfig().durationSeconds === 0 ? 'Unlimited' : `${recordingConfig().durationSeconds}s`}
                      </span>
                    </label>
                    <input
                      type="range"
                      class="range range-primary range-xs"
                      min={0}
                      max={3600}
                      value={recordingConfig().durationSeconds}
                      onInput={(e) => setRecordingConfig({ ...recordingConfig(), durationSeconds: parseInt(e.currentTarget.value) })}
                      disabled={isRecording()}
                    />
                  </div>
                  <div class="form-control mt-2">
                    <label class="label py-1">
                      <span class="label-text text-xs">Repeat Interval (seconds, 0 = no repeat)</span>
                      <span class="badge badge-primary badge-xs font-mono">
                        {recordingConfig().repeatIntervalSeconds === 0 ? 'None' : `${recordingConfig().repeatIntervalSeconds}s`}
                      </span>
                    </label>
                    <input
                      type="range"
                      class="range range-primary range-xs"
                      min={0}
                      max={3600}
                      value={recordingConfig().repeatIntervalSeconds}
                      onInput={(e) => setRecordingConfig({ ...recordingConfig(), repeatIntervalSeconds: parseInt(e.currentTarget.value) })}
                      disabled={isRecording()}
                    />
                  </div>
                  <div class="mt-3">
                    {isRecording() ? (
                      <button
                        class="btn btn-error btn-outline btn-sm w-full"
                        onClick={handleStopRecording}
                      >
                        Stop Recording
                      </button>
                    ) : (
                      <button
                        class="btn btn-primary btn-sm w-full"
                        onClick={handleStartRecording}
                        disabled={!st()?.streaming?.isActive}
                      >
                        Start Recording
                      </button>
                    )}
                  </div>
                </div>
              </div>

              {/* Actions */}
              <div class="flex justify-end">
                <button class="btn btn-error btn-outline btn-sm" onClick={handleResetDefaults}>
                  Reset to Defaults
                </button>
              </div>
            </section>
          </div>

          <Show when={showGallery()}>
            <Gallery onClose={() => setShowGallery(false)} />
          </Show>
        </>
      }>
        {/* Login Screen */}
        <div class="login-screen">
          <div class="login-card">
            <div class="flex items-center justify-center gap-3 mb-6">
              <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                <circle cx="12" cy="13" r="4" />
              </svg>
              <h1 class="text-2xl font-bold text-primary">LensCast</h1>
            </div>
            <p class="text-center text-sm text-base-content/60 mb-4">Authentication required</p>
            <form onSubmit={handleLogin}>
              <div class="form-control">
                <label class="label">
                  <span class="label-text text-xs">Username</span>
                </label>
                <input
                  type="text"
                  class="input input-bordered"
                  placeholder="Username"
                  value={loginUser()}
                  onInput={(e) => setLoginUser(e.currentTarget.value)}
                  autocomplete="username"
                  required
                />
              </div>
              <div class="form-control mt-3">
                <label class="label">
                  <span class="label-text text-xs">Password</span>
                </label>
                <input
                  type="password"
                  class="input input-bordered"
                  placeholder="Password"
                  value={loginPass()}
                  onInput={(e) => setLoginPass(e.currentTarget.value)}
                  autocomplete="current-password"
                  required
                />
              </div>
              {loginError() && (
                <div class="alert alert-error alert-soft text-sm mt-3 py-2">
                  <span>{loginError()}</span>
                </div>
              )}
              <button
                class="btn btn-primary btn-block mt-4"
                type="submit"
                disabled={loginLoading()}
              >
                {loginLoading() ? <span class="loading loading-spinner loading-sm"></span> : null}
                {loginLoading() ? 'Signing in...' : 'Sign In'}
              </button>
            </form>
          </div>
        </div>
      </Show>
    </div>
  )
}

export default App
