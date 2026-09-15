import { createEffect, createMemo, createSignal, onCleanup } from 'solid-js'
import * as api from '../api/client'
import { createRecordingTimer } from '../RecordingTimer'
import { createLiveAudioPlayer, type LiveAudioStatus } from '../audio/LiveAudioPlayer'
import { createPollLadder } from './pollLadder'
import { hashRouter, type RouteName } from '../lib/router'
import { createSectionedDebouncer } from '../lib/sectionedDebounce'
import { closeMedia, openMedia, viewerTarget } from '../lib/viewerStore'
import { hlsSupported, nextPlayerMode, whepSupported, type PlayerMode } from '../video/playerLadder'
import { h264Supported } from '../video/h264Player'
import { t } from '../lib/i18n'
import type {
  AllSettings, DeviceStatus, LensInfo, CameraSettings,
  FocusMode, WhiteBalance, Resolution, HdrMode,
  IntervalCaptureConfig, RecordingConfig,
  FlashMode, RecordingQuality, SessionRole,
} from '../types'

/** Consecutive status-lane failures before the dashboard shows the connection-lost banner. */
const CONNECTION_LOST_FAILURES = 3

export function useAppState() {
  // ── Auth ──
  const [authChecked, setAuthChecked] = createSignal(false)
  const [authRequired, setAuthRequired] = createSignal(false)
  const [authenticated, setAuthenticated] = createSignal(false)
  // The signed-in session's role: admin by default (auth off, pre-role
  // servers), viewer for the read-only pair. Server-derived on every
  // load/login — auth state itself is never persisted client-side (the
  // httpOnly cookie is the store), so the role follows the same pattern.
  const [sessionRole, setSessionRole] = createSignal<SessionRole>('admin')
  const isViewer = createMemo(() => authenticated() && sessionRole() === 'viewer')
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
  // The '?' shortcut overlay (ShortcutsOverlay in App).
  const [showShortcuts, setShowShortcuts] = createSignal(false)
  // The live preview's player rung, lifted here so the P shortcut can cycle
  // it — StreamPreview consumes it as props and keeps its ladder fall-down
  // logic untouched.
  const [playerMode, setPlayerMode] = createSignal<PlayerMode>(
    nextPlayerMode('mjpeg', !whepSupported(), !h264Supported(), false, hlsSupported()),
  )

  // ── Connection health ──
  // The status lane fails when fetchStatus errors; the SSE lane reports its
  // own openness. connectionLost is only true once the poll lane has failed
  // several times in a row AND the SSE channel is not open — a single
  // success (or a reopened stream) clears it again.
  const [statusFailures, setStatusFailures] = createSignal(0)
  const [sseConnected, setSseConnected] = createSignal(false)
  const connectionLost = createMemo(() => statusFailures() >= CONNECTION_LOST_FAILURES && !sseConnected())

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

  // Per-section debounce slots (lib/sectionedDebounce): the camera and app
  // tabs save disjoint sections, so one shared timer would let the
  // last-edited tab silently replace the other tab's pending PUT (and the
  // 30 s poll would revert it).
  const debounceSave = createSectionedDebouncer()

  function isAuthError(e: any): boolean {
    if (e?.status === 401) return true
    const msg = e?.message ?? ''
    return msg.includes('401') || msg.includes('Authentication required')
  }

  function isPageHidden() {
    return typeof document !== 'undefined' && document.hidden
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
        setSessionRole('admin')
      } else {
        setAuthRequired(true)
        try {
          const session = await api.getSessionStatus()
          setAuthenticated(session.authenticated)
          setSessionRole(session.role ?? 'admin')
        } catch {
          setAuthenticated(false)
          setSessionRole('admin')
        }
      }
    } catch {
      setAuthRequired(false)
      setAuthenticated(true)
      setSessionRole('admin')
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
      const result = await api.login(loginUser(), loginPass())
      setAuthenticated(true)
      setSessionRole(result.role ?? 'admin')
    } catch (e: any) {
      setLoginError(e.message || t('error.loginFailed'))
    } finally {
      setLoginLoading(false)
    }
  }

  async function handleLogout() {
    try { await api.logout() } catch { }
    setAuthenticated(false)
    setSessionRole('admin')
    setSettings(null)
    setStatus(null)
    setStatusFailures(0)
    setSseConnected(false)
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
      setStatusFailures(0)
    } catch (e: any) {
      if (isAuthError(e)) {
        setAuthenticated(false)
        return
      }
      setStatusFailures((n) => n + 1)
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
    debounceSave('camera', () => saveSettings({ camera: newCam }))
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
    if (nextStreaming) debounceSave('streaming', () => saveSettings({ streaming: nextStreaming }))
  }

  // ── Actions ──
  async function handleCapture() {
    setCaptureMsg(t('error.capturing'))
    try {
      const result = await api.capturePhoto()
      setCaptureMsg(result.success ? t('error.captured', { name: result.fileName ?? '' }) : t('error.actionFailedMsg', { message: result.error ?? '' }))
    } catch (e: any) {
      setCaptureMsg(t('error.actionFailedMsg', { message: e?.message ?? t('error.captureFailed') }))
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
  function handleStartWebStream() {
    return runStreamAction(api.startWebStream, {
      fallbackError: t('error.startWeb'), previewTo: true, bumpNonce: true,
    })
  }

  function handleStopWebStream() {
    return runStreamAction(api.stopWebStream, {
      fallbackError: t('error.stopWeb'), previewTo: false, bumpNonce: true,
    })
  }

  function handleStartRtspStream() {
    return runStreamAction(api.startRtspStream, {
      fallbackError: t('error.startRtsp'), previewTo: null, bumpNonce: false,
    })
  }

  function handleStopRtspStream() {
    return runStreamAction(api.stopRtspStream, {
      fallbackError: t('error.stopRtsp'), previewTo: null, bumpNonce: false,
    })
  }

  // WHIP push opts out of preview/nonce handling exactly like the RTSP pair:
  // the push egress never touches the local preview player.
  function handleStartWhip() {
    return runStreamAction(api.startWhip, {
      fallbackError: t('error.startWhip'), previewTo: null, bumpNonce: false,
    })
  }

  function handleStopWhip() {
    return runStreamAction(api.stopWhip, {
      fallbackError: t('error.stopWhip'), previewTo: null, bumpNonce: false,
    })
  }

  // RTMP push opts out of preview/nonce handling exactly like the WHIP pair:
  // the push egress never touches the local preview player.
  function handleStartRtmp() {
    return runStreamAction(api.startRtmp, {
      fallbackError: t('error.startRtmp'), previewTo: null, bumpNonce: false,
    })
  }

  function handleStopRtmp() {
    return runStreamAction(api.stopRtmp, {
      fallbackError: t('error.stopRtmp'), previewTo: null, bumpNonce: false,
    })
  }

  // ── Interval capture / recording actions ──
  function handleStartIntervalCapture() {
    return runResultAction(
      () => api.startIntervalCapture(intervalConfig()),
      t('error.startInterval'),
      () => {
        setIntervalRunning(true)
        setIntervalCompleted(0)
      },
    )
  }

  function handleStopIntervalCapture() {
    return runResultAction(api.stopIntervalCapture, t('error.stopInterval'), () => {
      setIntervalRunning(false)
    })
  }

  function handleStartRecording() {
    return runResultAction(
      () => api.startRecording(recordingConfig()),
      t('error.startRecording'),
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
    return runResultAction(api.stopRecording, t('error.stopRecording'), () => {
      setIsRecording(false)
      setIsScheduled(false)
      setScheduledStartTimeMs(null)
    })
  }

  // ── Effects ──
  createEffect(() => { checkAuth() })

  // ── Hash router sync (bidirectional, loop-free) ──
  // hash → state: every hash source (deep links, back/forward, the service
  // worker's notification click) lands here once and maps onto the existing
  // tab/gallery signals; the viewer deep link additionally opens the shared
  // media overlay. The previous-route bookkeeping closes that deep-linked
  // viewer when the route leaves it — without reading the viewer store, so
  // ordinary openMedia() calls (event feed, timeline) never bounce off this
  // effect.
  const router = hashRouter()
  createEffect((prev?: { name: RouteName; mediaId?: string }) => {
    const route = router.route()
    if (prev && prev.name === 'gallery' && prev.mediaId &&
        (route.name !== 'gallery' || route.mediaId !== prev.mediaId)) {
      closeMedia()
    }
    if (route.name === 'gallery') {
      if (!showGallery()) setShowGallery(true)
      if (route.mediaId && viewerTarget()?.id !== route.mediaId) {
        openMedia(route.mediaId)
      }
    } else {
      if (showGallery()) setShowGallery(false)
      if (route.name === 'settings' || route.name === 'events') {
        if (activeTab() !== 'app') setActiveTab('app')
        if (route.name === 'events') {
          queueMicrotask(() => {
            document.getElementById('event-feed-anchor')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
          })
        }
      }
    }
    return { name: route.name, mediaId: route.mediaId }
  })

  // state → hash: user-driven tab/gallery changes rewrite the hash only when
  // it contradicts the state (the '#/' default is compatible with either
  // tab), so the pair settles in one hop instead of oscillating.
  createEffect(() => {
    const open = showGallery()
    const tab = activeTab()
    const route = router.route()
    if (open && route.name !== 'gallery') {
      router.navigate({ name: 'gallery' })
    } else if (!open && route.name === 'gallery') {
      router.navigate({ name: tab === 'app' ? 'settings' : 'dashboard' })
    } else if (!open && tab !== 'app' && (route.name === 'settings' || route.name === 'events')) {
      router.navigate({ name: 'dashboard' })
    }
  })

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
    // The model download rides the settings payload with no SSE push of its
    // own, and a download can also be auto-requested by the device itself
    // (the detection coordinator fetches on the first gated motion event).
    // The settings lane therefore always polls at the slow cadence — so a
    // download the dashboard never saw start still gets discovered — and
    // once the wire state says `downloading`, the fast modelDownload lane
    // takes the settings fetch over and the slow lane backs off.
    const modelDownloading = () => settings()?.streaming?.mlModelState === 'downloading'

    // SSE status push: when the channel is live it replaces the status poll
    // lane entirely; the ladder lane stays armed as automatic fallback for
    // browsers or clients where the stream is capped (503).
    let eventSource: EventSource | null = null
    try {
      eventSource = new EventSource('/api/events')
      eventSource.addEventListener('status', (ev) => {
        try {
          const parsed = JSON.parse((ev as MessageEvent).data)
          setStatus(parsed)
        } catch { }
      })
      // Feed the connection-lost banner: the stream counts as connected from
      // open until its next error (EventSource reconnects on its own).
      eventSource.onopen = () => setSseConnected(true)
      eventSource.onerror = () => setSseConnected(false)
      onCleanup(() => eventSource?.close())
    } catch {
      eventSource = null
    }

    const ladder = createPollLadder({
      status: { everyTicks: 3, enabled: () => !sseHealthChecker() },
      recording: { everyTicks: 3 },
      intervalCapture: { everyTicks: 5 },
      settings: { everyTicks: 30, enabled: () => !modelDownloading() },
      modelDownload: { everyTicks: 3, enabled: modelDownloading },
      lenses: { everyTicks: 30, enabled: streamingInactive },
    }, (key) => {
      switch (key) {
        case 'status': void fetchStatus(); break
        case 'recording': void fetchRecordingStatus(); break
        case 'intervalCapture': void fetchIntervalStatus(); break
        case 'settings': void fetchSettings(); break
        case 'modelDownload': void fetchSettings(); break
        case 'lenses': void fetchLenses(); break
      }
    }, {
      isVisible: () => !isPageHidden(),
      tickMs: 1000,
    })

    // Track SSE liveness with a small grace: a closed stream re-arms the lane.
    let sseAliveAt = 0
    function sseHealthChecker(): boolean {
      if (eventSource && eventSource.readyState === EventSource.OPEN) sseAliveAt = Date.now()
      return Date.now() - sseAliveAt < 5_000
    }

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
    authChecked, authRequired, authenticated, sessionRole, isViewer,
    loginUser, setLoginUser, loginPass, setLoginPass,
    loginError, loginLoading, handleLogin, handleLogout,
    // Core
    settings, status, lenses, error, captureMsg, saving,
    previewVisible, setPreviewVisible, streamActionLoading, streamNonce, showGallery, setShowGallery,
    showShortcuts, setShowShortcuts,
    playerMode, setPlayerMode,
    connectionLost,
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
    handleStartWebStream, handleStopWebStream,
    handleStartRtspStream, handleStopRtspStream,
    handleStartWhip, handleStopWhip,
    handleStartRtmp, handleStopRtmp,
    handleStartIntervalCapture, handleStopIntervalCapture,
    handleStartRecording, handleStopRecording,
    // Tabs
    activeTab, setActiveTab,
  }
}
