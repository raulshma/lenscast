import { Show } from 'solid-js'
import { useAppState } from './hooks/useAppState'
import LoginScreen from './components/LoginScreen'
import Navbar from './components/Navbar'
import StreamPreview from './components/StreamPreview'
import SettingsPanel from './components/SettingsPanel'
import Gallery from './Gallery'
import './App.css'

function App() {
  const state = useAppState()

  return (
    <div class="app">
      <Show when={!state.authChecked()} fallback={
        <Show when={state.authRequired() && !state.authenticated()} fallback={
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
                setPreviewVisible={state.setPreviewVisible}
              />

              <SettingsPanel
                settings={state.settings}
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
              />
            </main>

            <Show when={state.showGallery()}>
              <Gallery onClose={() => state.setShowGallery(false)} />
            </Show>
          </>
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
