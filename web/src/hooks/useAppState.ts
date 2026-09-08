import { createEffect, createSignal, onCleanup } from 'solid-js'
import * as api from '../api/client'
import { createRecordingTimer } from '../RecordingTimer'
import { createLiveAudioPlayer, type LiveAudioStatus } from '../audio/LiveAudioPlayer'
import { createPollLadder } from './pollLadder'
import type {
  AllSettings, DeviceStatus, LensInfo, CameraSettings,
  FocusMode, WhiteBalance, Resolution, HdrMode,
  IntervalCaptureConfig, RecordingConfig,
  FlashMode, RecordingQuality,
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
    intervalSeconds: 5, totalCaptures: 100, flashMode: 'OFF',
  })
  const [intervalRunning, setIntervalRunning] = createSignal(false)
  const [intervalCompleted, setIntervalCompleted] = createSignal(0)

  // ── Recording ──
  const [recordingConfig, setRecordingConfig] = createSignal<RecordingConfig>({
    durationSeconds: 0, repeatIntervalSeconds: 0,
    quality: 'HIGH', includeAudio: true,
  })
  const [isRecording, setIsRecording] = createSignal(false)
  const [recordingElapsed, setRecordingElapsed] = createSignal(0)
  const [isScheduled, setIsScheduled] = createSignal(false)
  const [scheduledStartTimeMs, setScheduledStartTimeMs] = createSignal<number | null>(null)
  const recordingTimer = createRecordingTimer(isRecording, recordingElapsed)

  // ── Live Audio ──
  const [liveAudioStatus, setLiveAudioStatus] = createSignal<LiveAudioStatus>('idle')
  const liveAudioPlayer = createLiveAudioPlayer({ onStatus: (s) => setLiveAudioStatus(s) })

  // ── Settings Tabs ──
  const [activeTab, setActiveTab] = createSignal<'camera' | 'app'>('camera')

  let saveTimer: ReturnType<typeof setTimeout> | null = null

  function isAuthError(e: any): boolean {
    if (e?.status === 401) return true
    const msg = e?.message ?? ''
    return msg.includes('401') || msg.includes('Authentication required')
  }

  function isPageHidden() {
    return typeof document !== 'undefined' && document.hidden
  }

  function debounceSave(fn: () => void, ms = 400) {
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(fn, ms)
  }

  // ── Action pipeline ──
  // Every device action answers the same shape: run the api call, translate
  // failure into setError(fallback), clear the error and run success
  // side effects otherwise. Stream actions additionally guard a shared
  // busy flag and refresh status after a successful state change; the
  // RTSP pair opts out of preview/nonce handling explicitly.
  async function runResultAction(
    action: () => Promise<{ success: boolean; error?: string }>,
    fallbackError: string,
    onSuccess?: () => void | Promise<void>,
  ) {
    try {
      const result = await action()
      if (!result.success) {
        setError(result.error || fallbackError)
      } else {
        setError('')
        await onSuccess?.()
      }
    } catch (e: any) {
      setError(e.message || fallbackError)
    }
  }

  async function runStreamAction(
    action: () => Promise<{ success: boolean; error?: string }>,
    options: { fallbackError: string; previewTo: boolean | null; bumpNonce: boolean },
  ) {
    if (streamActionLoading()) return
    setStreamActionLoading(true)
    try {
      await runResultAction(action, options.fallbackError, async () => {
        if (options.previewTo !== null) setPreviewVisible(options.previewTo)
        if (options.bumpNonce) setStreamNonce((v) => v + 1)
        await fetchStatus()
      })
    } finally {
      setStreamActionLoading(false)
    }
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
      if (isAuthError(e)) {
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
      if (isAuthError(e)) setAuthenticated(false)
    }
  }

  async function fetchLenses() {
    try {
      const r = await api.getLenses()
      setLenses(r.lenses)
    } catch (e: any) {
      if (isAuthError(e)) setAuthenticated(false)
    }
  }

  async function fetchIntervalStatus() {
    try {
      const s = await api.getIntervalCaptureStatus()
      setIntervalRunning(s.isRunning)
      setIntervalCompleted(s.completedCaptures)
    } catch (e) {
      console.warn('Failed to fetch interval status:', e)
    }
  }

  async function fetchRecordingStatus() {
    try {
      const s = await api.getRecordingStatus()
      setIsRecording(s.isRecording)
      setRecordingElapsed(s.elapsedSeconds)
      setIsScheduled(s.isScheduled ?? false)
      setScheduledStartTimeMs(s.scheduledStartTimeMs ?? null)
    } catch (e) {
      console.warn('Failed to fetch recording status:', e)
    }
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

  // ── Stream actions ──
  function handleStopStream() {
    return runStreamAction(api.stopStream, {
      fallbackError: 'Failed to stop stream', previewTo: false, bumpNonce: true,
    })
  }

  function handleResumeStream() {
    return runStreamAction(api.startStream, {
      fallbackError: 'Failed to resume stream', previewTo: true, bumpNonce: true,
    })
  }

  function handleStartWebStream() {
    return runStreamAction(api.startWebStream, {
      fallbackError: 'Failed to start web stream', previewTo: true, bumpNonce: true,
    })
  }

  function handleStopWebStream() {
    return runStreamAction(api.stopWebStream, {
      fallbackError: 'Failed to stop web stream', previewTo: false, bumpNonce: true,
    })
  }

  function handleStartRtspStream() {
    return runStreamAction(api.startRtspStream, {
      fallbackError: 'Failed to start RTSP stream', previewTo: null, bumpNonce: false,
    })
  }

  function handleStopRtspStream() {
    return runStreamAction(api.stopRtspStream, {
      fallbackError: 'Failed to stop RTSP stream', previewTo: null, bumpNonce: false,
    })
  }

  // ── Interval capture / recording actions ──
  function handleStartIntervalCapture() {
    return runResultAction(
      () => api.startIntervalCapture(intervalConfig()),
      'Failed to start interval capture',
      () => {
        setIntervalRunning(true)
        setIntervalCompleted(0)
      },
    )
  }

  function handleStopIntervalCapture() {
    return runResultAction(api.stopIntervalCapture, 'Failed to stop interval capture', () => {
      setIntervalRunning(false)
    })
  }

  function handleStartRecording() {
    return runResultAction(
      () => api.startRecording(recordingConfig()),
      'Failed to start recording',
      () => {
        if (recordingConfig().startTimeMs) {
          setIsScheduled(true)
          setScheduledStartTimeMs(recordingConfig().startTimeMs ?? null)
        } else {
          setIsRecording(true)
          setRecordingElapsed(0)
        }
      },
    )
  }

  function handleStopRecording() {
    return runResultAction(api.stopRecording, 'Failed to stop recording', () => {
      setIsRecording(false)
      setIsScheduled(false)
      setScheduledStartTimeMs(null)
    })
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

    const streamingInactive = () => !status()?.streaming?.isActive

    const ladder = createPollLadder({
      status: { everyTicks: 3 },
      recording: { everyTicks: 3 },
      intervalCapture: { everyTicks: 5 },
      settings: { everyTicks: 30, enabled: streamingInactive },
      lenses: { everyTicks: 30, enabled: streamingInactive },
    }, (key) => {
      switch (key) {
        case 'status': void fetchStatus(); break
        case 'recording': void fetchRecordingStatus(); break
        case 'intervalCapture': void fetchIntervalStatus(); break
        case 'settings': void fetchSettings(); break
        case 'lenses': void fetchLenses(); break
      }
    }, {
      isVisible: () => !isPageHidden(),
      tickMs: 1000,
    })

    ladder.start()

    const handleVisibility = () => {
      if (!document.hidden) refreshDashboard(true)
    }
    document.addEventListener('visibilitychange', handleVisibility)
    onCleanup(() => {
      ladder.stop()
      document.removeEventListener('visibilitychange', handleVisibility)
    })
  })

  createEffect(() => {
    if (status()?.streaming?.webStreamingActive) setPreviewVisible(true)
  })

  createEffect(() => {
    const streaming = status()?.streaming
    const nonce = streamNonce()
    if (!streaming?.isActive || !streaming.audioEnabled) {
      void liveAudioPlayer.stop()
      return
    }
    const nextKey = `${streaming.isActive}:${streaming.audioEnabled}:${nonce}`
    if (nextKey === liveAudioPlayer.key) return
    void liveAudioPlayer.start(`/audio?t=${nonce}`, nextKey)
  })

  onCleanup(() => { void liveAudioPlayer.stop() })

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
    handleStartWebStream, handleStopWebStream,
    handleStartRtspStream, handleStopRtspStream,
    handleStartIntervalCapture, handleStopIntervalCapture,
    handleStartRecording, handleStopRecording,
    // Tabs
    activeTab, setActiveTab,
  }
}
