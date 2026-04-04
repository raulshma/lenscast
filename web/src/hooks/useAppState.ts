import { createEffect, createSignal, onCleanup } from 'solid-js'
import * as api from '../api/client'
import { createRecordingTimer } from '../RecordingTimer'
import type {
  AllSettings, DeviceStatus, LensInfo, CameraSettings,
  FocusMode, WhiteBalance, Resolution, HdrMode,
  IntervalCaptureConfig, RecordingConfig,
  CaptureMode, FlashMode, RecordingQuality,
} from '../types'

export function useAppState() {
  // ── Auth ──
  const [authChecked, setAuthChecked] = createSignal(false)
  const [authRequired, setAuthRequired] = createSignal(false)
  const [authenticated, setAuthenticated] = createSignal(false)
  const [loginUser, setLoginUser] = createSignal('')
  const [loginPass, setLoginPass] = createSignal('')
  const [loginError, setLoginError] = createSignal('')
  const [loginLoading, setLoginLoading] = createSignal(false)

  // ── Core state ──
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

  // ── Interval capture ──
  const [intervalConfig, setIntervalConfig] = createSignal<IntervalCaptureConfig>({
    intervalSeconds: 5, totalCaptures: 100, imageQuality: 90,
    captureMode: 'MINIMIZE_LATENCY', flashMode: 'OFF',
  })
  const [intervalRunning, setIntervalRunning] = createSignal(false)
  const [intervalCompleted, setIntervalCompleted] = createSignal(0)

  // ── Recording ──
  const [recordingConfig, setRecordingConfig] = createSignal<RecordingConfig>({
    durationSeconds: 0, repeatIntervalSeconds: 0,
    quality: 'HIGH', maxFileSizeBytes: 0, includeAudio: true,
  })
  const [isRecording, setIsRecording] = createSignal(false)
  const [recordingElapsed, setRecordingElapsed] = createSignal(0)
  const [isScheduled, setIsScheduled] = createSignal(false)
  const [scheduledStartTimeMs, setScheduledStartTimeMs] = createSignal<number | null>(null)
  const recordingTimer = createRecordingTimer(isRecording, recordingElapsed)

  // ── Live Audio ──
  const [liveAudioStatus, setLiveAudioStatus] = createSignal<'idle' | 'connecting' | 'live' | 'error'>('idle')

  let saveTimer: ReturnType<typeof setTimeout> | null = null
  let liveAudioAbortController: AbortController | null = null
  let liveAudioContext: AudioContext | null = null
  let liveAudioPlaybackTime = 0
  let liveAudioSession = 0
  let liveAudioKey = ''
  let audioBufferPool: ArrayBuffer[] = []
  const MAX_BUFFER_POOL_SIZE = 8

  function isPageHidden() {
    return typeof document !== 'undefined' && document.hidden
  }

  function concatBytes(a: Uint8Array, b: Uint8Array) {
    const merged = new Uint8Array(a.length + b.length)
    merged.set(a, 0)
    merged.set(b, a.length)
    return merged
  }

  function getBufferFromPool(size: number): ArrayBuffer {
    for (let i = 0; i < audioBufferPool.length; i++) {
      if (audioBufferPool[i].byteLength >= size) {
        return audioBufferPool.splice(i, 1)[0]
      }
    }
    return new ArrayBuffer(size)
  }

  function returnBufferToPool(buffer: ArrayBuffer) {
    if (audioBufferPool.length < MAX_BUFFER_POOL_SIZE) {
      audioBufferPool.push(buffer)
    }
  }

  async function stopLiveAudioPlayback(resetKey = true) {
    if (resetKey) liveAudioKey = ''
    liveAudioSession += 1
    liveAudioAbortController?.abort()
    liveAudioAbortController = null
    liveAudioPlaybackTime = 0
    if (liveAudioContext) {
      try { await liveAudioContext.close() } catch { }
      liveAudioContext = null
    }
    setLiveAudioStatus('idle')
  }

  async function ensureLiveAudioContext(sampleRate: number) {
    const AudioContextCtor = (window as any).AudioContext || (window as any).webkitAudioContext
    if (!AudioContextCtor) return null
    if (!liveAudioContext || liveAudioContext.state === 'closed') {
      liveAudioContext = new AudioContextCtor({ latencyHint: 'interactive', sampleRate })
    }
    if (liveAudioContext && liveAudioContext.state === 'suspended') {
      try { await liveAudioContext.resume() } catch { }
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

    let reconnectAttempts = 0
    const MAX_RECONNECT_ATTEMPTS = 3
    const RECONNECT_DELAY_MS = 2000

    async function attemptConnection() {
      try {
        const res = await fetch(url, { cache: 'no-store', signal: controller.signal })
        if (!res.ok || !res.body) throw new Error(`Audio stream unavailable: ${res.status}`)

        const sampleRate = parseInt(res.headers.get('X-Audio-Sample-Rate') || '48000', 10)
        const channelCount = parseInt(res.headers.get('X-Audio-Channels') || '1', 10)
        const bytesPerFrame = 2 * channelCount
        const ctx = await ensureLiveAudioContext(sampleRate)
        if (!ctx) throw new Error('Web Audio not supported')

        setLiveAudioStatus('live')
        liveAudioPlaybackTime = ctx.currentTime + 0.05
        reconnectAttempts = 0

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
        if (!controller.signal.aborted && sessionId === liveAudioSession) {
          if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            await new Promise(resolve => setTimeout(resolve, RECONNECT_DELAY_MS))
            if (sessionId === liveAudioSession && !controller.signal.aborted) {
              await attemptConnection()
            }
          } else {
            setLiveAudioStatus('error')
          }
        }
      }
    }

    await attemptConnection()
  }

  function debounceSave(fn: () => void, ms = 400) {
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(fn, ms)
  }

  // ── Auth handlers ──
  async function checkAuth() {
    try {
      const authStatus = await api.getAuthStatus()
      if (!authStatus.required) {
        setAuthRequired(false)
        setAuthenticated(true)
      } else {
        setAuthRequired(true)
        try {
          const session = await api.getSessionStatus()
          setAuthenticated(session.authenticated)
        } catch {
          setAuthenticated(false)
        }
      }
    } catch {
      setAuthRequired(false)
      setAuthenticated(true)
    } finally {
      setAuthChecked(true)
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
    try { await api.logout() } catch { }
    setAuthenticated(false)
    setSettings(null)
    setStatus(null)
  }

  // ── Data fetching ──
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
      if (e.message?.includes('401')) setAuthenticated(false)
    }
  }

  async function fetchLenses() {
    try {
      const r = await api.getLenses()
      setLenses(r.lenses)
    } catch (e: any) {
      if (e.message?.includes('401')) setAuthenticated(false)
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
      setIsScheduled(s.isScheduled ?? false)
      setScheduledStartTimeMs(s.scheduledStartTimeMs ?? null)
    } catch { }
  }

  // ── Settings ──
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

  function updateStreaming(patch: Partial<AllSettings['streaming']>) {
    const current = settings()
    if (!current) return
    const nextStreaming = { ...current.streaming, ...patch }
    setSettings({ ...current, streaming: nextStreaming })
    return nextStreaming
  }

  function updateStreamingAndSave(patch: Partial<AllSettings['streaming']>) {
    const nextStreaming = updateStreaming(patch)
    if (nextStreaming) saveSettings({ streaming: nextStreaming })
  }

  function updateStreamingDebounced(patch: Partial<AllSettings['streaming']>) {
    const nextStreaming = updateStreaming(patch)
    if (nextStreaming) debounceSave(() => saveSettings({ streaming: nextStreaming }))
  }

  // ── Actions ──
  async function handleCapture() {
    setCaptureMsg('Capturing...')
    try {
      const result = await api.capturePhoto()
      setCaptureMsg(result.success ? `Captured: ${result.fileName}` : `Failed: ${result.error}`)
    } catch (e: any) {
      setCaptureMsg(`Failed: ${e?.message ?? 'Capture failed'}`)
    }
    setTimeout(() => setCaptureMsg(''), 4000)
  }

  async function handleSelectLens(index: number) {
    await api.selectLens(index)
  }

  async function handleResetDefaults() {
    const defaults: CameraSettings = {
      exposureCompensation: 0, iso: null, exposureTime: null,
      focusMode: 'AUTO', focusDistance: null, whiteBalance: 'AUTO',
      colorTemperature: null, zoomRatio: 1.0, frameRate: 30,
      resolution: 'FHD_1080P', stabilization: true, hdrMode: 'OFF', sceneMode: null,
      nightVisionMode: 'OFF',
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
        if (recordingConfig().startTimeMs) {
          setIsScheduled(true)
          setScheduledStartTimeMs(recordingConfig().startTimeMs ?? null)
        } else {
          setIsRecording(true)
          setRecordingElapsed(0)
        }
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
        setIsScheduled(false)
        setScheduledStartTimeMs(null)
      }
    } catch (e: any) {
      setError(e.message || 'Failed to stop recording')
    }
  }

  // ── Effects ──
  createEffect(() => { checkAuth() })

  createEffect(() => {
    if (!authenticated()) return

    const refreshDashboard = (force = false) => {
      if (!force && isPageHidden()) return
      void fetchStatus()
      if (force || !status()?.streaming?.isActive) {
        void fetchSettings()
        void fetchLenses()
      }
      void fetchIntervalStatus()
      void fetchRecordingStatus()
    }

    refreshDashboard(true)

    const POLL_TICK_MS = 1000
    const STATUS_INTERVAL = 3
    const RECORDING_INTERVAL = 3
    const INTERVAL_CAPTURE_INTERVAL = 5
    const SETTINGS_INTERVAL = 30
    const LENSES_INTERVAL = 30

    let tickCount = 0
    let settingsTick = 0
    let lensesTick = 0
    let intervalTick = 0
    let recordingTick = 0
    let statusTick = 0

    const pollTimer = setInterval(() => {
      if (isPageHidden()) return
      tickCount++
      statusTick++
      recordingTick++
      intervalTick++
      settingsTick++
      lensesTick++

      if (statusTick >= STATUS_INTERVAL) {
        statusTick = 0
        void fetchStatus()
      }
      if (recordingTick >= RECORDING_INTERVAL) {
        recordingTick = 0
        void fetchRecordingStatus()
      }
      if (intervalTick >= INTERVAL_CAPTURE_INTERVAL) {
        intervalTick = 0
        void fetchIntervalStatus()
      }
      if (!status()?.streaming?.isActive) {
        if (settingsTick >= SETTINGS_INTERVAL) {
          settingsTick = 0
          void fetchSettings()
        }
        if (lensesTick >= LENSES_INTERVAL) {
          lensesTick = 0
          void fetchLenses()
        }
      }
    }, POLL_TICK_MS)

    const handleVisibility = () => {
      if (!document.hidden) refreshDashboard(true)
    }
    document.addEventListener('visibilitychange', handleVisibility)
    onCleanup(() => {
      clearInterval(pollTimer)
      document.removeEventListener('visibilitychange', handleVisibility)
    })
  })

  createEffect(() => {
    if (status()?.streaming?.isActive) setPreviewVisible(true)
  })

  createEffect(() => {
    const streaming = status()?.streaming
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

  onCleanup(() => { void stopLiveAudioPlayback() })

  return {
    // Auth
    authChecked, authRequired, authenticated, loginUser, setLoginUser, loginPass, setLoginPass,
    loginError, loginLoading, handleLogin, handleLogout,
    // Core
    settings, status, lenses, error, captureMsg, saving,
    previewVisible, setPreviewVisible, streamActionLoading, streamNonce, showGallery, setShowGallery,
    // Camera
    updateCamera,
    // Streaming
    updateStreamingAndSave, updateStreamingDebounced,
    // Interval
    intervalConfig, setIntervalConfig, intervalRunning, intervalCompleted,
    // Recording
    recordingConfig, setRecordingConfig, isRecording, recordingElapsed, recordingTimer,
    isScheduled, scheduledStartTimeMs,
    // Audio
    liveAudioStatus,
    // Actions
    handleCapture, handleSelectLens, handleResetDefaults,
    handleStopStream, handleResumeStream,
    handleStartIntervalCapture, handleStopIntervalCapture,
    handleStartRecording, handleStopRecording,
  }
}
