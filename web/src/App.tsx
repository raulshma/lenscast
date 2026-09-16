import { ErrorBoundary, Show } from 'solid-js'
import { useAppState } from './hooks/useAppState'
import { useTheme } from './hooks/useTheme'
import { useKeyboardShortcuts } from './hooks/useKeyboardShortcuts'
import LoginScreen from './components/LoginScreen'
import Navbar from './components/Navbar'
import StreamPreview from './components/StreamPreview'
import SettingsPanel from './components/SettingsPanel'
import AppSettingsPanel from './components/AppSettingsPanel'
import ClientsCard from './components/ClientsCard'
import MultiCamCard from './components/MultiCamCard'
import ShareCard from './components/ShareCard'
import ShortcutsOverlay from './components/ShortcutsOverlay'
import { MediaViewerOverlay } from './components/MediaViewer'
import Gallery from './Gallery'
import { cyclePlayerMode } from './video/playerLadder'
import { viewerTarget } from './lib/viewerStore'
import { t } from './lib/i18n'
import './App.css'

/** Programmatic download of a GET route, the Gallery batch-download pattern. */
function triggerDownload(url: string) {
  const a = document.createElement('a')
  a.href = url
  a.download = ''
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

function App() {
  const state = useAppState()
  const { theme, toggleTheme } = useTheme()

  // One global keydown listener; every guard (editable focus, modifier keys,
  // the key map) is the pure lib/shortcuts module. Write shortcuts skip
  // viewer sessions — the same POSTs the buttons fire would 403 — and the
  // gating mirrors the buttons exactly (stream enabled flags, action busy,
  // active stream for capture).
  useKeyboardShortcuts({
    capture: () => {
      if (state.isViewer()) return
      if (state.status()?.streaming?.isActive) state.handleCapture()
    },
    snapshot: () => {
      triggerDownload('/snapshot?highres=1&save=1')
    },
    'toggle-web': () => {
      if (state.isViewer() || state.streamActionLoading()) return
      const s = state.status()?.streaming
      if (!s) return
      if (s.webStreamingActive) state.handleStopWebStream()
      else if (s.webStreamingEnabled) state.handleStartWebStream()
    },
    'toggle-rtsp': () => {
      if (state.isViewer() || state.streamActionLoading()) return
      const s = state.status()?.streaming
      if (!s) return
      if (s.rtspStreamingActive) state.handleStopRtspStream()
      else if (s.rtspEnabled) state.handleStartRtspStream()
    },
    'cycle-player': () => {
      state.setPlayerMode(cyclePlayerMode(state.playerMode()))
    },
    gallery: () => {
      state.setShowGallery(!state.showGallery())
    },
    search: () => {
      const focus = () => {
        (document.getElementById('gallery-search-input') as HTMLInputElement | null)?.focus()
      }
      if (state.showGallery()) focus()
      else {
        state.setShowGallery(true)
        // Solid renders the gallery synchronously with the signal flip; one
        // macrotask later the input exists.
        setTimeout(focus, 50)
      }
    },
    help: () => {
      state.setShowShortcuts(!state.showShortcuts())
    },
  }, {
    enabled: () => state.authChecked() && (!state.authRequired() || state.authenticated()),
  })

  // True while a higher overlay owns Escape (shortcuts help, the shared media
  // viewer) — the gallery defers its own Escape handling then.
  const overlayActive = () => state.showShortcuts() || viewerTarget() != null

  return (
    <div class="app">
      <Show when={!state.authChecked()} fallback={
        <Show when={state.authRequired() && !state.authenticated()} fallback={
          <ErrorBoundary fallback={(err) => (
            <div class="app-loading" style={{ padding: '24px' }}>
              <div class="error-banner" style={{ 'max-width': '720px', width: '100%' }}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="10" />
                  <line x1="12" y1="8" x2="12" y2="12" />
                  <line x1="12" y1="16" x2="12.01" y2="16" />
                </svg>
                <span>{t('app.renderError', { error: String(err) })}</span>
              </div>
            </div>
          )}>
            <>
              <Navbar
                status={state.status}
                saving={state.saving}
                authRequired={state.authRequired}
                handleLogout={state.handleLogout}
                setShowGallery={state.setShowGallery}
                onShowShortcuts={() => state.setShowShortcuts(true)}
                theme={theme}
                toggleTheme={toggleTheme}
              />

              <Show when={state.connectionLost()}>
                <div class="connection-lost-banner" role="alert">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
                    <line x1="12" y1="9" x2="12" y2="13" />
                    <line x1="12" y1="17" x2="12.01" y2="17" />
                  </svg>
                  <span>{t('app.connectionLost')}</span>
                </div>
              </Show>

              <Show when={state.isViewer()}>
                <div class="connection-lost-banner" role="note">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="3" y="11" width="18" height="11" rx="2" />
                    <path d="M7 11V7a5 5 0 0110 0v4" />
                  </svg>
                  <span>{t('app.viewerBanner')}</span>
                </div>
              </Show>

              <main class="main-layout">
                <StreamPreview
                  status={state.status}
                  previewVisible={state.previewVisible}
                  streamNonce={state.streamNonce}
                  streamActionLoading={state.streamActionLoading}
                  isRecording={state.isRecording}
                  captureMsg={state.captureMsg}
                  liveAudioStatus={state.liveAudioStatus}
                  recordingTimer={state.recordingTimer}
                  playerMode={state.playerMode}
                  setPlayerMode={state.setPlayerMode}
                  handleCapture={state.handleCapture}
                  handleStartWebStream={state.handleStartWebStream}
                  handleStopWebStream={state.handleStopWebStream}
                  handleStartRtspStream={state.handleStartRtspStream}
                  handleStopRtspStream={state.handleStopRtspStream}
                  setPreviewVisible={state.setPreviewVisible}
                  overlaySettings={() => state.settings()?.streaming ?? null}
                  connectionLost={state.connectionLost}
                />

                <SettingsPanel
                  settings={state.settings}
                  status={state.status}
                  lenses={state.lenses}
                  error={state.error}
                  updateCamera={state.updateCamera}
                  updateStreamingAndSave={state.updateStreamingAndSave}
                  updateStreamingDebounced={state.updateStreamingDebounced}
                  handleSelectLens={state.handleSelectLens}
                  handleResetDefaults={state.handleResetDefaults}
                  intervalConfig={state.intervalConfig}
                  setIntervalConfig={state.setIntervalConfig}
                  intervalRunning={state.intervalRunning}
                  intervalCompleted={state.intervalCompleted}
                  handleStartIntervalCapture={state.handleStartIntervalCapture}
                  handleStopIntervalCapture={state.handleStopIntervalCapture}
                  recordingConfig={state.recordingConfig}
                  setRecordingConfig={state.setRecordingConfig}
                  isRecording={state.isRecording}
                  isScheduled={state.isScheduled}
                  scheduledStartTimeMs={state.scheduledStartTimeMs}
                  recordingTimer={state.recordingTimer}
                  handleStartRecording={state.handleStartRecording}
                  handleStopRecording={state.handleStopRecording}
                  activeTab={state.activeTab}
                  setActiveTab={state.setActiveTab}
                >
                  <AppSettingsPanel
                    settings={state.settings}
                    status={state.status}
                    streamActionLoading={state.streamActionLoading}
                    updateStreamingAndSave={state.updateStreamingAndSave}
                    updateStreamingDebounced={state.updateStreamingDebounced}
                    setRecordingConfigAudio={(v) => state.setRecordingConfig({ ...state.recordingConfig(), includeAudio: v })}
                    handleStartWhip={state.handleStartWhip}
                    handleStopWhip={state.handleStopWhip}
                    handleStartRtmp={state.handleStartRtmp}
                    handleStopRtmp={state.handleStopRtmp}
                    readOnly={state.isViewer}
                  />
                  <ClientsCard readOnly={state.isViewer} />
                  <MultiCamCard />
                  <ShareCard />
                </SettingsPanel>
              </main>

              <Show when={state.showGallery()}>
                <Gallery
                  onClose={() => state.setShowGallery(false)}
                  readOnly={state.isViewer}
                  overlayActive={overlayActive}
                />
              </Show>

              {/* The shared clip viewer: event-feed "View clip", timeline
                  segments, and #/gallery/<id> deep links all open here. */}
              <MediaViewerOverlay canDelete={!state.isViewer()} />

              <Show when={state.showShortcuts()}>
                <ShortcutsOverlay onClose={() => state.setShowShortcuts(false)} />
              </Show>
            </>
          </ErrorBoundary>
        }>
          <LoginScreen
            loginUser={state.loginUser}
            setLoginUser={state.setLoginUser}
            loginPass={state.loginPass}
            setLoginPass={state.setLoginPass}
            loginError={state.loginError}
            loginLoading={state.loginLoading}
            handleLogin={state.handleLogin}
          />
        </Show>
      }>
        <div class="app-loading">
          <div class="login-lens-ring">
            <div class="login-lens-inner">
              <img src="/logo.svg" alt="LensCast" width="36" height="36" />
            </div>
          </div>
        </div>
      </Show>
    </div>
  )
}

export default App
