import { Show, createSignal } from 'solid-js'
import type { useAppState } from '../../hooks/useAppState'
import { streamPreviewProps } from '../next/NextState'
import type { ThemeMode } from '../../hooks/useTheme'
import { viewerTarget } from '../../lib/viewerStore'
import Navbar from '../Navbar'
import StreamPreview from '../StreamPreview'
import CameraControlsStrip from '../CameraControlsStrip'
import SettingsPanel from '../SettingsPanel'
import AppSettingsPanel from '../AppSettingsPanel'
import ClientsCard from '../ClientsCard'
import MultiCamCard from '../MultiCamCard'
import ShareCard from '../ShareCard'
import ShortcutsOverlay from '../ShortcutsOverlay'
import { MediaViewerOverlay } from '../MediaViewer'
import Gallery from '../../Gallery'
import { t } from '../../lib/i18n'

interface Props {
  state: ReturnType<typeof useAppState>
  theme: () => ThemeMode
  toggleTheme: () => void
}

/**
 * The classic dashboard shell — the shipped default UI, moved verbatim from
 * App.tsx when the shell dispatcher landed. It owns the two-pane layout,
 * the classic theme toggle wiring, and all classic overlays. The only
 * intentional addition is the opt-in pill in the Navbar.
 */
export default function LegacyShell(props: Props) {
  const state = props.state

  // True while a higher overlay owns Escape (shortcuts help, the shared media
  // viewer, the full-screen preview) — the gallery defers its own Escape
  // handling then.
  const [previewFullscreen, setPreviewFullscreen] = createSignal(false)
  const overlayActive = () => state.showShortcuts() || viewerTarget() != null || previewFullscreen()

  return (
    <>
      <Navbar
        status={state.status}
        saving={state.saving}
        authRequired={state.authRequired}
        handleLogout={state.handleLogout}
        setShowGallery={state.setShowGallery}
        onShowShortcuts={() => state.setShowShortcuts(true)}
        theme={props.theme}
        toggleTheme={props.toggleTheme}
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
          {...streamPreviewProps(state)}
          stripOverlay={() => (
            <CameraControlsStrip
              status={state.status}
              lenses={state.lenses}
              settings={state.settings}
              isViewer={state.isViewer}
              updateCamera={state.updateCamera}
              handleSelectLens={state.handleSelectLens}
            />
          )}
          onFullscreenChange={setPreviewFullscreen}
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
  )
}
