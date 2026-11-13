import { Show, createSignal } from 'solid-js'
import type { NextState } from '../NextState'
import StreamPreview from '../../StreamPreview'
import CameraStrip from './live/CameraStrip'
import { t } from '../../../lib/i18n'
import { IconSliders } from '../../icons'
import { Drawer, Skeleton } from '../ui'
import ChannelMatrix from './live/ChannelMatrix'
import StoragePanel from './live/StoragePanel'
import ClientsPanel from './live/ClientsPanel'
import SystemInfoPanel from './live/SystemInfoPanel'

/**
 * Live view: the full-width preview stage with its command bar, and the
 * inspector column — channel rack, storage, viewers, diagnostics. The
 * stage engine stays StreamPreview (player ladder, talkback, tap-to-focus
 * are its own world); the reskin lives in styles/next/views/live.css.
 * At 768–1199px the inspector is unreachable inline, so a slide-over
 * Drawer carries it — the tablet no longer loses the channel rack.
 */
export default function LiveView(props: { state: NextState }) {
  const state = props.state
  const [drawerOpen, setDrawerOpen] = createSignal(false)

  const inspector = () => (
    <>
      <ChannelMatrix state={state} />
      <Show when={state.settings()?.streaming}>
        <StoragePanel
          settings={state.settings}
          updateStreamingAndSave={state.updateStreamingAndSave}
          updateStreamingDebounced={state.updateStreamingDebounced}
        />
      </Show>
      <ClientsPanel readOnly={state.isViewer} />
      <SystemInfoPanel />
    </>
  )

  return (
    <div class="nx-view nx-view-flush">
      <div class="nx-stage-row">
        <div class="nx-stage">
          <Show
            when={state.status()}
            fallback={
              <div class="nx-stage-skeleton">
                <Skeleton h="100%" />
              </div>
            }
          >
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
              connectionLost={state.connectionLost}
              stripOverlay={() => <CameraStrip state={state} />}
            />
          </Show>
        </div>

        <aside class="nx-inspector">{inspector()}</aside>
      </div>

      {/* Tablet affordance: the inspector column is display:none between
          768 and 1199 — the drawer is the way in there. */}
      <button
        type="button"
        class="nx-inspector-fab"
        onClick={() => setDrawerOpen(true)}
        title={t('next.inspector')}
        aria-label={t('next.inspector')}
      >
        <IconSliders size={16} />
      </button>

      <Drawer open={drawerOpen()} onClose={() => setDrawerOpen(false)} title={t('view.live')}>
        {inspector()}
      </Drawer>
    </div>
  )
}
