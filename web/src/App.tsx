import { ErrorBoundary, Show } from 'solid-js'
import { useAppState } from './hooks/useAppState'
import LoginScreen from './components/LoginScreen'
import Navbar from './components/Navbar'
import StreamPreview from './components/StreamPreview'
import SettingsPanel from './components/SettingsPanel'
import AppSettingsPanel from './components/AppSettingsPanel'
import Gallery from './Gallery'
import './App.css'

function App() {
  const state = useAppState()

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
                <span>Dashboard rendering error: {String(err)}</span>
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
              />

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
                  handleCapture={state.handleCapture}
                  handleStopStream={state.handleStopStream}
                  handleResumeStream={state.handleResumeStream}
                  handleStartWebStream={state.handleStartWebStream}
                  handleStopWebStream={state.handleStopWebStream}
                  handleStartRtspStream={state.handleStartRtspStream}
                  handleStopRtspStream={state.handleStopRtspStream}
                  setPreviewVisible={state.setPreviewVisible}
                  overlaySettings={() => state.settings()?.streaming ?? null}
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
                />

                <Show when={state.activeTab() === 'app'}>
                  <AppSettingsPanel
                    settings={state.settings}
                    updateStreamingAndSave={state.updateStreamingAndSave}
                    updateStreamingDebounced={state.updateStreamingDebounced}
                    setRecordingConfigAudio={(v) => state.setRecordingConfig({ ...state.recordingConfig(), includeAudio: v })}
                  />
                </Show>
              </main>

              <Show when={state.showGallery()}>
                <Gallery onClose={() => state.setShowGallery(false)} />
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
