import { Show, createSignal, onCleanup } from 'solid-js'
import type { NextState } from '../NextState'
import { registerStageSlot } from '../../../lib/pipStore'
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
 * stage engine (StreamPreview) is not mounted here: it lives once in
 * StreamPipHost above the view outlet, and this view renders only the
 * mount-point slot it re-parents into — so navigating away parks the
 * player in the mini-player dock instead of tearing the stream down.
 * The reskin lives in styles/next/views/live.css. At 768–1199px the
 * inspector is unreachable inline, so a slide-over Drawer carries it —
 * the tablet no longer loses the channel rack.
 */
export default function LiveView(props: { state: NextState }) {
  const state = props.state
  const [drawerOpen, setDrawerOpen] = createSignal(false)

  // The slot the persistent StreamPreview host re-parents into while this
  // view is active; cleared on unmount so the host parks (or docks) instead
  // of appending into a detached subtree.
  const stageSlotRef = (el: HTMLDivElement) => {
    registerStageSlot(el)
    onCleanup(() => registerStageSlot(null))
  }

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
            <div class="nx-stage-slot" ref={stageSlotRef} />
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
