import { For, Show, createEffect, createSignal } from 'solid-js'
import type { NextState } from './NextState'
import type { NextThemeMode } from '../../hooks/useNextTheme'
import { uiModeStore } from '../../lib/uiMode'
import { hashRouter, isNextRoute, toNextRoute, type RouteName } from '../../lib/router'
import { LOCALES, LOCALE_DISPLAY_NAMES, locale, setLocale, t, type Locale } from '../../lib/i18n'
import {
  IconLive,
  IconMedia,
  IconShield,
  IconSliders,
  IconSun,
  IconMoon,
  IconKeyboard,
  IconLogout,
  IconUndo,
  IconGlobe,
  IconRecord,
  IconWarning,
  IconLock,
  IconBattery,
  IconTherm,
  IconSignal,
  IconChevronLeft,
  IconChevronRight,
} from '../icons'
import { Led, Badge, Toaster, pushToast } from './ui'
import {
  channelStates,
  channelLed,
  formatKbps,
  linkLed,
  linkLabel,
  batteryTone,
  thermalTone,
  thermalLabel,
  vitalToneClass,
} from './lib/telemetry'
import LiveView from './views/LiveView'
import MediaView from './views/MediaView'
import DetectionView from './views/DetectionView'
import SystemView from './views/SystemView'
import ShortcutsOverlay from '../ShortcutsOverlay'
import { MediaViewerOverlay } from '../MediaViewer'
import StreamPipHost from './StreamPipHost'

interface Props {
  state: NextState
  theme: () => NextThemeMode
  toggleTheme: () => void
}

const SIDEBAR_KEY = 'lenscast.nextSidebar'

function readStoredCompact(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_KEY) === 'compact'
  } catch {
    return false
  }
}

/**
 * The operator console shell: a rail-first sidebar (brand mark, view
 * items, REC row, vitals dock, utility row), a 36px telemetry
 * statusline (LIVE, channel LEDs, throughput, clients, REC, thermal,
 * battery, saving), and the view outlet. All state is the dispatcher's
 * shared useAppState instance; the theme signal comes from the
 * dispatcher too (it owns the DOM attribute). Below 1024px the sidebar
 * auto-compacts; below 768px it docks as a labelled bottom tab bar
 * (layout.css).
 */
export default function NextShell(props: Props) {
  const state = props.state
  const ui = uiModeStore()
  const router = hashRouter()
  const theme = props.theme
  const toggleTheme = props.toggleTheme

  // Sidebar width preference — a desktop affordance; the breakpoints
  // below 1024px compact regardless of it (CSS decides).
  const [compact, setCompact] = createSignal(readStoredCompact())
  const toggleCompact = () => {
    const next = !compact()
    setCompact(next)
    try {
      localStorage.setItem(SIDEBAR_KEY, next ? 'compact' : 'wide')
    } catch {
      /* private mode: the toggle just won't persist */
    }
  }

  // Legacy hashes redirect into the next UI's own route space, one hop
  // after they arrive (bookmarks, notification deep links).
  createEffect(() => {
    const route = router.route()
    if (!isNextRoute(route.name)) router.navigate(toNextRoute(route))
  })

  const view = (): RouteName => {
    const name = router.route().name
    return isNextRoute(name) ? name : 'live'
  }

  const viewLabel = (name: RouteName): string => {
    switch (name) {
      case 'live':
        return t('view.live')
      case 'media':
        return t('view.media')
      case 'detection':
        return t('view.detection')
      case 'system':
        return t('view.system')
      default:
        return t('view.live')
    }
  }

  const VIEW_ICONS: Record<string, typeof IconLive> = {
    live: IconLive,
    media: IconMedia,
    detection: IconShield,
    system: IconSliders,
  }

  // Action failures and capture confirmations surface as toasts — the
  // console never fails silently (classic shows these in a banner).
  createEffect(() => {
    const error = state.error()
    if (error) pushToast('danger', error)
  })

  createEffect(() => {
    const msg = state.captureMsg()
    if (msg) pushToast(msg.endsWith('…') ? 'info' : 'success', msg)
  })

  const streaming = () => state.status()?.streaming
  const isLive = () => streaming()?.isActive === true

  const channels = () => channelStates(state.status())

  const txLabel = (): string | null => {
    if (!isLive()) return null
    const kbps = state.status()?.adaptiveBitrate?.estimatedBandwidthKbps
    return kbps ? formatKbps(kbps) : null
  }

  const fpsLabel = (): number | null => {
    if (!isLive()) return null
    const fps = state.status()?.adaptiveBitrate?.currentFps
    return fps && fps > 0 ? fps : null
  }

  const clientsLabel = (): number | null => {
    const s = streaming()
    if (!s) return null
    return (s.clientCount ?? 0) + (s.whepClients ?? 0)
  }

  const battery = () => state.status()?.battery
  const batteryToneClass = (): string => vitalToneClass(batteryTone(battery()?.level))
  const thermalToneClass = (): string => vitalToneClass(thermalTone(state.status()?.thermal))
  const quality = () => state.status()?.connectionQuality?.qualityLevel
  const linkToneClass = (): string => vitalToneClass(linkLed(quality()))

  return (
    <div class="nx-shell" classList={{ 'nx-shell-compact': compact() }}>
      <nav class="nx-sidebar" aria-label={t('nav.newUi')}>
        <div class="nx-side-head">
          <img src="/logo.svg" alt="LensCast" width="26" height="26" />
          <span class="nx-side-brand">LensCast</span>
          <button
            type="button"
            class="nx-util-btn"
            onClick={toggleCompact}
            title={compact() ? t('next.sidebar.expand') : t('next.sidebar.collapse')}
            aria-label={compact() ? t('next.sidebar.expand') : t('next.sidebar.collapse')}
          >
            <Show when={compact()} fallback={<IconChevronLeft size={16} />}>
              <IconChevronRight size={16} />
            </Show>
          </button>
        </div>

        <div class="nx-side-nav">
          <For each={['live', 'media', 'detection', 'system'] as RouteName[]}>
            {(name) => {
              const Icon = VIEW_ICONS[name]
              return (
                <button
                  type="button"
                  class="nx-side-item"
                  classList={{ 'nx-side-item-active': view() === name }}
                  onClick={() => router.navigate({ name })}
                  title={viewLabel(name)}
                >
                  <Icon size={18} />
                  <span class="nx-side-item-label">{viewLabel(name)}</span>
                  <Show when={name === 'live' && isLive()}>
                    <span class="nx-side-item-glyph">
                      <Led tone="rec" />
                    </span>
                  </Show>
                </button>
              )
            }}
          </For>
        </div>

        <Show when={state.isRecording()}>
          <div class="nx-side-rec" title={state.recordingTimer.formatElapsed()}>
            <Led tone="rec" />
            <span class="nx-side-rec-label">
              {t('next.rec')} {state.recordingTimer.formatElapsed()}
            </span>
          </div>
        </Show>

        <div class="nx-side-spacer" />

        {/* Vitals dock: battery, thermal, link — three quiet rows.
            Golos labels speak; only the measurements go mono, and the
            tone classes color icon + value (layout.css). */}
        <Show when={state.status()}>
          <div class="nx-side-vitals">
            <Show when={battery()}>
              <div class="nx-vital">
                <span class={`nx-vital-head ${batteryToneClass()}`}>
                  <IconBattery size={13} />
                  <span class="nx-vital-label">{t('next.vitals.battery')}</span>
                  <span class="nx-vital-value nx-num">{battery()!.level}%</span>
                  <Show when={battery()!.isCharging}>
                    <span class="nx-vital-charging">{t('next.vitals.charging')}</span>
                  </Show>
                </span>
              </div>
            </Show>
            <div class="nx-vital">
              <span class={`nx-vital-head ${thermalToneClass()}`}>
                <IconTherm size={13} />
                <span class="nx-vital-label">{t('next.vitals.thermal')}</span>
                <span class="nx-vital-value">{thermalLabel(state.status()?.thermal)}</span>
              </span>
            </div>
            <Show when={quality()}>
              <div class="nx-vital">
                <span class={`nx-vital-head ${linkToneClass()}`}>
                  <IconSignal size={13} />
                  <span class="nx-vital-label">{t('next.vitals.link')}</span>
                  <span class="nx-vital-value nx-num">{linkLabel(quality())}</span>
                </span>
              </div>
            </Show>
          </div>
        </Show>

        {/* Utility row: theme, language, shortcuts, the way back, logout.
            All carry the same compact style — on mobile only theme, exit
            and logout survive, and none of them can masquerade as a view
            tab anymore. */}
        <div class="nx-side-util">
          <button
            type="button"
            class="nx-util-btn"
            onClick={toggleTheme}
            title={theme() === 'dark' ? t('nav.theme.light') : t('nav.theme.dark')}
            aria-label={theme() === 'dark' ? t('nav.theme.light') : t('nav.theme.dark')}
          >
            <Show when={theme() === 'dark'} fallback={<IconMoon size={17} />}>
              <IconSun size={17} />
            </Show>
          </button>

          <div class="nx-util-btn nx-side-locale">
            <IconGlobe size={17} />
            <select
              class="nx-side-locale-select"
              aria-label={t('nav.language')}
              title={t('nav.language')}
              value={locale()}
              onChange={(e) => setLocale(e.currentTarget.value as Locale)}
            >
              <For each={LOCALES}>
                {(code) => <option value={code}>{LOCALE_DISPLAY_NAMES[code]}</option>}
              </For>
            </select>
          </div>

          <button
            type="button"
            class="nx-util-btn nx-util-btn-shortcuts"
            onClick={() => state.setShowShortcuts(true)}
            title={t('nav.shortcuts')}
            aria-label={t('nav.shortcuts')}
          >
            <IconKeyboard size={17} />
          </button>

          <button
            type="button"
            class="nx-util-btn"
            onClick={() => ui.set('classic')}
            title={t('nav.exitNewUi')}
            aria-label={t('nav.exitNewUi')}
          >
            <IconUndo size={17} />
          </button>

          <Show when={state.authRequired()}>
            <button
              type="button"
              class="nx-util-btn"
              onClick={state.handleLogout}
              title={t('nav.logout')}
              aria-label={t('nav.logout')}
            >
              <IconLogout size={17} />
            </button>
          </Show>
        </div>
      </nav>

      <div class="nx-content">
        {/* Telemetry strip: where you are, and what the camera is doing. */}
        <div class="nx-strip">
          <span class="nx-strip-title">{viewLabel(view())}</span>
          <div class="nx-strip-chips">
            <Show when={isLive()}>
              {/* On air — signal magenta (layout.css), not the danger red. */}
              <Badge tone="neutral" class="nx-strip-live">
                <Led tone="rec" />
                {t('next.live')}
              </Badge>
            </Show>

            {/* Channel LEDs — tap to jump to the streaming rack. */}
            <For each={channels()}>
              {(channel) => (
                <button
                  type="button"
                  class="nx-chip"
                  classList={{
                    'nx-chip-ok': channelLed(channel) === 'ok',
                    'nx-chip-warn': channelLed(channel) === 'warn' || channelLed(channel) === 'danger',
                  }}
                  onClick={() => router.navigate({ name: 'system', tab: 'streaming' })}
                  title={channel.error || channel.code}
                >
                  <Led tone={channelLed(channel)} />
                  {channel.code}
                </button>
              )}
            </For>

            <Show when={txLabel()}>
              <span class="nx-chip nx-strip-hide-lg" title={t('next.strip.throughput')}>
                TX {txLabel()}
              </span>
            </Show>
            <Show when={fpsLabel()}>
              <span class="nx-chip nx-strip-hide-lg">{fpsLabel()}FPS</span>
            </Show>
            <Show when={clientsLabel() !== null}>
              <span class="nx-chip nx-strip-hide-lg">CL {clientsLabel()}</span>
            </Show>

            {/* Live view is excluded: its stage already carries the REC
                chip, and the pair read as two timers on every viewport. */}
            <Show when={state.isRecording() && view() !== 'live'}>
              <span class="nx-chip nx-chip-rec">
                <Led tone="rec" />
                {t('next.rec')} {state.recordingTimer.formatElapsed()}
              </span>
            </Show>

            <Show when={state.status()?.thermal && state.status()!.thermal !== 'NORMAL'}>
              <span class="nx-chip nx-chip-warn nx-strip-hide-sm">
                {thermalLabel(state.status()?.thermal)}
              </span>
            </Show>
            <Show when={battery()}>
              {/* Critical battery is a fault — red (nx-chip-crit), never
                  the on-air magenta that nx-chip-rec now carries. */}
              <span class="nx-chip nx-strip-hide-sm" classList={{ 'nx-chip-warn': batteryTone(battery()?.level) === 'warn', 'nx-chip-crit': batteryTone(battery()?.level) === 'danger' }}>
                {battery()!.level}%
              </span>
            </Show>
            <Show when={state.saving()}>
              <span class="nx-chip">{t('next.strip.saving')}…</span>
            </Show>
          </div>
        </div>

        <Show when={state.connectionLost()}>
          <div class="nx-banner-bar nx-banner-bar-danger" role="alert">
            <IconWarning size={15} />
            <span>{t('app.connectionLost')}</span>
          </div>
        </Show>

        <Show when={state.isViewer()}>
          <div class="nx-banner-bar nx-banner-bar-neutral" role="note">
            <IconLock size={15} />
            <span>{t('app.viewerBanner')}</span>
          </div>
        </Show>

        <Show when={view() === 'live'} fallback={
          <Show when={view() === 'media'} fallback={
            <Show when={view() === 'detection'} fallback={<SystemView state={state} />}>
              <DetectionView state={state} />
            </Show>
          }>
            <MediaView state={state} />
          </Show>
        }>
          <LiveView state={state} />
        </Show>
      </div>

      <Show when={state.showShortcuts()}>
        <ShortcutsOverlay onClose={() => state.setShowShortcuts(false)} />
      </Show>

      {/* The shared clip viewer: deep links and (later) feeds open it above
          any view. */}
      <MediaViewerOverlay canDelete={!state.isViewer()} />

      {/* The persistent live-stream host: owns the one StreamPreview
          instance and re-parents it between the live stage and the mini
          player, so navigating away never tears the stream down. Mounted
          outside every <Show> — it must survive all view switches. */}
      <StreamPipHost state={state} />

      <Toaster />
    </div>
  )
}
