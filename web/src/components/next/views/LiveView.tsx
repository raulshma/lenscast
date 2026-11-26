import { Show, createEffect, createSignal, onCleanup, untrack } from 'solid-js'
import type { NextState } from '../NextState'
import { registerStageSlot } from '../../../lib/pipStore'
import { t } from '../../../lib/i18n'
import { shortcutFor } from '../../../lib/shortcuts'
import {
  endMaskEdit,
  maskEditActive,
  maskEditSession,
  setMaskEditSession,
} from '../../../lib/maskEditStore'
import { IconSliders } from '../../icons'
import { Drawer, Skeleton, pushToast } from '../ui'
import ChannelMatrix from './live/ChannelMatrix'
import StoragePanel from './live/StoragePanel'
import ClientsPanel from './live/ClientsPanel'
import SystemInfoPanel from './live/SystemInfoPanel'
import MaskEditBar from './live/masking/MaskEditBar'
import MaskZoneInspector from './live/masking/MaskZoneInspector'
import MaskingQuickPanel from './live/masking/MaskingQuickPanel'
import { createMaskEditSession } from './live/masking/maskEditSession'
import { webStreamActive } from './live/masking/webStreamActive'

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
 *
 * ── The masking-zone editor ──
 * While the Live route is mounted, this view OWNS the masking-zone editor
 * (maskEditStore, maskEditSession.ts). Arming is anyone's — "Edit zones" in
 * the inspector, or the Privacy Masking panel elsewhere — and costs nothing;
 * answering is the Live view's, because the editor is drawn on this view's
 * picture. The contract, in the order it applies:
 *
 * 1. An armed edit with no editor published is answered here: one session is
 *    created and published, and never a second while one exists.
 * 2. It is not answered when there is no web stream to draw on. Without this
 *    the edit would stay armed with nothing on screen to finish it, while
 *    the camera strip stays hidden and the picture's gestures stay locked.
 * 3. Whenever the edit ends — "Done", the picture's own Escape, anything
 *    that clears the armed flag — the published editor is released and the
 *    store emptied. `dispose()` alone never ends an edit, so nothing here
 *    relies on it to.
 * 4. When this view goes away, so does the edit: finished (which publishes
 *    the zones to the mirror and restores the remembered feed), then
 *    cleared. An armed edit is never left without a view to finish it — the
 *    mini-player must not inherit one.
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

  // ── The masking-zone editor: this view's while it is mounted ──────
  /** True from the arm until the edit ends. */
  const editing = () => maskEditActive()
  /** The published editor, while the edit runs; null before this view has answered an arm and after the edit ends. */
  const editor = () => (maskEditActive() ? maskEditSession() : null)

  // (1) and (2). Every write here changes a value the effect reads, and lands
  // it on a branch that writes nothing more: created → the session exists;
  // refused → the edit is no longer armed. So it settles in one re-run.
  createEffect(() => {
    if (!maskEditActive()) return
    // Already answered — never a second.
    if (maskEditSession()) return
    // (2) Nothing to draw on: the request is turned away, and said why.
    if (!webStreamActive(state)) {
      endMaskEdit()
      pushToast('warning', t('privacy.streamOffHint'))
      return
    }
    // The shared settings flows replay their default-first placeholder until
    // DataStore's first read lands. Opening now would seed an empty draft and
    // let the first edit replace zones that are already saved on the device.
    if (!state.settings()?.streaming) return
    // (1) The session reads the settings mirror, the focus zone and the
    // player rung once, at creation: none of those may re-run this effect.
    setMaskEditSession(untrack(() => createMaskEditSession(state)))
  })

  // (2b). The stream going away mid-edit is the arm-time refusal's twin. The
  // gate above reads the stream once, before a session exists — once one is
  // published it returns early and the stream is untracked, so the shared W
  // shortcut (or the dock's stop) would end the picture the zones are drawn
  // on and end nothing. This companion tracks the stream for the session's
  // whole life and tears the edit down exactly as the arm-time refusal does:
  // end the edit — whose release puts the remembered feed back — and say why.
  // Both dependencies are read unconditionally, so every flip of either
  // re-runs this effect, whichever branch ran last.
  createEffect(() => {
    const streamOn = webStreamActive(state)
    const published = maskEditActive() ? maskEditSession() : null
    if (!published) return
    if (!streamOn) {
      endMaskEdit()
      pushToast('warning', t('privacy.streamOffHint'))
    }
  })

  // (3). The edit ended by some other hand than this view's cleanup: release
  // what was published (a session that already finished releases nothing).
  createEffect(() => {
    if (maskEditActive()) return
    const published = maskEditSession()
    if (!published) return
    published.dispose()
    setMaskEditSession(null)
  })

  // (4). Unmounting is always the end of the edit, whichever way the view
  // went: the route left Live, the shell was torn down. `finish` drops a
  // gesture in flight, publishes the zones to the mirror if they differ,
  // puts the remembered feed back, and clears the armed flag; the trailing
  // `dispose` is then a no-op that keeps the two releases symmetrical.
  onCleanup(() => {
    const published = maskEditSession()
    if (!published) {
      // Armed, but this view never got to answer: the request leaves with it.
      if (maskEditActive()) endMaskEdit()
      return
    }
    published.finish()
    published.dispose()
    setMaskEditSession(null)
  })

  // The inspector Drawer belongs to the normal Live view. An edit that
  // starts while it is open closes it, so it is not standing there with the
  // normal panels the moment the edit ends.
  createEffect(() => {
    if (editing()) setDrawerOpen(false)
  })

  // ── The player rung stays put while an edit runs ─────────────────
  // Zones are placed on the MJPEG web stream, which paints the whole frame.
  // The other rungs do not (WHEP and H.264 are centre-cropped), so the
  // global "cycle player" key (P) mid-edit would move the picture under
  // zones that were just drawn — and nothing on screen would say so.
  //
  // The global shortcuts listen on document, in the bubble phase, and so
  // does the picture's own key handling (Solid delegates its events there).
  // A capture-phase listener on window runs before either. It stops exactly
  // the one key: stopping any other would also swallow the editor's arrows,
  // Delete and Escape on their way to the picture.
  createEffect(() => {
    if (!maskEditActive()) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (shortcutFor(e) === 'cycle-player') e.stopPropagation()
    }
    window.addEventListener('keydown', onKeyDown, true)
    onCleanup(() => window.removeEventListener('keydown', onKeyDown, true))
  })

  // ── Escape is the editor's while it runs ────────────────────────────
  // One Escape must not double-act: useFullscreen (StreamPreview's own
  // instance, reachable from no beta code) exits the CSS fullscreen overlay
  // from a document-bubble listener on the same key the picture's ladder
  // steps the editor back with. While a session is published, this
  // capture-phase listener on window runs before every document-bubble
  // handler — the fullscreen hook's, the global shortcuts', and Solid's
  // delegated ones, the picture's own Escape ladder included — stops the
  // key, and runs that ladder itself, through session commands. The layer's
  // handler never sees a stopped event, so nothing steps twice; a drag the
  // ladder cancels heals on the layer's next pointer event (its move
  // handler drops a gesture the session no longer has). The shortcuts help,
  // a modal over the picture, keeps its Escape: the guard stands down while
  // it is up. Armed without a session — the arm not yet answered, or
  // refused — registers nothing, and when the edit ends the listener goes
  // with the session: Escape exits fullscreen as normal again.
  createEffect(() => {
    if (!maskEditActive() || !maskEditSession()) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || e.ctrlKey || e.altKey || e.metaKey) return
      if (state.showShortcuts()) return
      const editor = maskEditSession()
      if (!maskEditActive() || !editor) return
      e.preventDefault()
      e.stopPropagation()
      // The picture's ladder, one step at a time; with nothing left, Done.
      if (editor.gesture()) {
        editor.cancelGesture()
        return
      }
      if (editor.drawing()) {
        editor.toggleDrawing()
        return
      }
      if (editor.selectedId() !== null) {
        editor.select(null)
        return
      }
      editor.finish()
    }
    window.addEventListener('keydown', onKeyDown, true)
    onCleanup(() => window.removeEventListener('keydown', onKeyDown, true))
  })

  /** The normal inspector: the decks that tune the rig. Not offered while an edit is open. */
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
      <Show when={state.settings()?.streaming}>
        <MaskingQuickPanel state={state} />
      </Show>
      <SystemInfoPanel />
    </>
  )

  return (
    // tabindex -1: the picture's focus hand-off (MaskEditLayer) parks the
    // focus here when the edit ends — focusable, never in the tab order.
    <div class="nx-view nx-view-flush" tabindex={-1} classList={{ 'nx-mask-editing': editing() }}>
      <div class="nx-stage-row">
        <div class="nx-stage">
          {/* Above the stage slot: the edit bar never shrinks, the picture gives. */}
          <Show when={editor()} keyed>
            {(session) => <MaskEditBar session={session} />}
          </Show>
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

        {/* The inspector column: the normal decks, or — while an edit is
            open — the zones. Never both. */}
        <aside class="nx-inspector">
          <Show when={editor()} keyed fallback={inspector()}>
            {(session) => <MaskZoneInspector session={session} />}
          </Show>
        </aside>
      </div>

      {/* Tablet affordance: the inspector column is display:none between
          768 and 1199 — the drawer is the way in there. While an edit runs
          neither exists: the zones are on the stage and in the sheet under
          it, and the normal panels are not on offer. */}
      <Show when={!editing()}>
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
      </Show>
    </div>
  )
}
