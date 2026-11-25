import { For, Show, createEffect, type JSX } from 'solid-js'
import { API_DEFAULTS } from '../../../api/defaults'
import type { NightVisionMode } from '../../../types'
import type { NextState } from '../NextState'
import { t } from '../../../lib/i18n'
import { hashRouter, isSystemTab, SYSTEM_TABS, type SystemTab } from '../../../lib/router'
import { Skeleton } from '../ui'
// Camera tab — next-native ports (views/system/cards/*)
import LensPanel from './system/cards/LensPanel'
import ExposurePanel from './system/cards/ExposurePanel'
import FocusPanel from './system/cards/FocusPanel'
import WhiteBalancePanel from './system/cards/WhiteBalancePanel'
import ZoomFramePanel from './system/cards/ZoomFramePanel'
import EffectsPanel from './system/cards/EffectsPanel'
import NightVisionPanel from './system/cards/NightVisionPanel'
import OverlayPanel from './system/cards/OverlayPanel'
import PrivacyMaskingPanel from './system/cards/PrivacyMaskingPanel'
// Capture tab
import IntervalCapturePanel from './system/cards/IntervalCapturePanel'
import RecordingPanel from './system/cards/RecordingPanel'
import WatchdogPanel from './system/cards/WatchdogPanel'
// Streaming tab — WebStream/Audio/Https/Rtsp/KeepAwake were migrated in
// place inside StreamingCards.tsx; WHIP/RTMP have dedicated ports.
import { WebStreamCard, AudioCard, HttpsCard, RtspCard, KeepAwakeCard } from './system/StreamingCards'
import WhipPanel from './system/cards/WhipPanel'
import RtmpPanel from './system/cards/RtmpPanel'
// Storage tab — retention/device reuse the Live inspector's telemetry panels
import StoragePanel from './live/StoragePanel'
import SystemInfoPanel from './live/SystemInfoPanel'
import RecordingTimelinePanel from './system/cards/RecordingTimelinePanel'
// Links tab
import MultiCamPanel from './system/cards/MultiCamPanel'
import SharePanel from './system/cards/SharePanel'
import PushPanel from './system/cards/PushPanel'
import UnifiedPushPanel from './system/cards/UnifiedPushPanel'
import MqttPanel from './system/cards/MqttPanel'
// Admin tab
import AuthPanel from './system/cards/AuthPanel'
import BackupPanel from './system/cards/BackupPanel'
import ConfigBackupPanel from './system/cards/ConfigBackupPanel'
import AuditPanel from './system/cards/AuditPanel'

/**
 * System view: one settings topic per sub-tab (#/system/<tab>), so nothing
 * from another topic is ever in view — the old single 29-card page is six
 * short ones. The tab is the chapter: flat instrument nav (hairline + amber
 * underline); an invalid or missing tab normalizes to `camera`. Every card
 * is a next-native port (`system/cards/*`) with logic copied verbatim from
 * its classic counterpart — same signals, same bindings, same API_DEFAULTS.
 *
 * Inside a tab, related panels group into racks (`.nx-rack`): ONE faceplate
 * cut by 1px --color-rule seams (the `.nx-cut` pattern), its panels stacked
 * in a single readable column — no floating cards, no masonry. The rack's
 * own name is the grouping's aria heading only (`.nx-rack-title` is visually
 * hidden): the tab names the chapter, each panel's own title is the one
 * visible heading per idea, and the seams alone carry the grouping.
 */

/**
 * One rack: a group of panels cut from a single plate. `title` stays the
 * rack's accessible heading (the t() key stays wired 1:1); `single` marks
 * one-card racks so the pane grid can pair them side by side; `wide`
 * (single-card racks whose panel genuinely wants width — the camera lens
 * rack, the QR share, the audit log) spans both columns instead.
 */
function Rack(props: { id: string; title: string; single?: boolean; wide?: boolean; children: JSX.Element }) {
  return (
    <section
      class="nx-section nx-rack"
      classList={{ 'nx-section-single': !!props.single, 'nx-section-wide': !!props.wide }}
      aria-labelledby={props.id}
    >
      <h2 class="nx-rack-title" id={props.id}>{props.title}</h2>
      <div class="nx-cut nx-rack-plate">{props.children}</div>
    </section>
  )
}

const TABPANEL_ID = 'nx-system-tabpanel'

export default function SystemView(props: { state: NextState }) {
  const state = props.state
  const router = hashRouter()

  const tab = (): SystemTab => {
    const current = router.route().tab
    return isSystemTab(current) ? current : 'camera'
  }

  // An out-of-range tab (stale bookmark, hand-typed hash) replaces itself
  // with the default so the URL always describes a real tab.
  createEffect(() => {
    const current = router.route()
    if (current.name === 'system' && !isSystemTab(current.tab)) {
      router.navigate({ name: 'system', tab: 'camera' })
    }
  })

  const tabLabel = (name: SystemTab): string => t(`system.tab.${name}`)

  const streamingCardProps = () => ({
    settings: state.settings,
    updateStreamingAndSave: state.updateStreamingAndSave,
    updateStreamingDebounced: state.updateStreamingDebounced,
    setRecordingConfigAudio: (v: boolean) => state.setRecordingConfig({ ...state.recordingConfig(), includeAudio: v }),
  })

  const sectionId = (key: string): string => `nx-system-section-${key}`

  return (
    <div class="nx-view nx-view-system">
      <div class="nx-systabs-wrap">
        <div class="nx-systabs" role="tablist" aria-label={t('view.system')}>
          <For each={SYSTEM_TABS}>
            {(name) => (
              <Show when={name !== 'admin' || !state.isViewer()}>
                <button
                  type="button"
                  role="tab"
                  id={`nx-systab-${name}`}
                  class="nx-systab"
                  classList={{ 'nx-systab-active': tab() === name }}
                  aria-selected={tab() === name}
                  aria-controls={TABPANEL_ID}
                  onClick={() => router.navigate({ name: 'system', tab: name })}
                >
                  {tabLabel(name)}
                </button>
              </Show>
            )}
          </For>
        </div>
        {/* Truncation affordance: the tab row scrolls with hidden scrollbars,
            so a clipped last tab (Admin on a 390px phone) needs an edge cue.
            Sits outside the scroller so it pins to the viewport edge. */}
        <div class="nx-systabs-fade" aria-hidden="true" />
      </div>

      {/* Only the active tab's sections mount; the tab row drives which one.
          A viewer never gets the admin topics. While settings/lenses are
          still loading the pane shows loading bars instead of blank space. */}
      <Show
        when={state.lenses().length > 0 || state.settings()}
        fallback={
          <div
            class="nx-view-inner nx-system-pane"
            role="tabpanel"
            id={TABPANEL_ID}
            aria-labelledby={`nx-systab-${tab()}`}
          >
            <div class="nx-system-loading" aria-hidden="true">
              <Skeleton h="24px" w="180px" />
              <Skeleton h="120px" />
              <Skeleton h="200px" />
            </div>
          </div>
        }
      >
        <div
          class="nx-view-inner nx-system-pane"
          role="tabpanel"
          id={TABPANEL_ID}
          aria-labelledby={`nx-systab-${tab()}`}
        >
          {/* ── Camera ── */}
          <Show when={tab() === 'camera'}>
            <Show when={state.lenses().length > 0}>
              <Rack id={sectionId('lens')} title={t('system.section.lens')} single wide>
                <LensPanel lenses={state.lenses} handleSelectLens={state.handleSelectLens} />
              </Rack>
            </Show>
            <Rack id={sectionId('exposure')} title={t('system.section.exposure')}>
              <ExposurePanel settings={state.settings} updateCamera={state.updateCamera} />
              <FocusPanel settings={state.settings} updateCamera={state.updateCamera} />
              <WhiteBalancePanel settings={state.settings} updateCamera={state.updateCamera} />
            </Rack>
            <Rack id={sectionId('image')} title={t('system.section.image')}>
              <ZoomFramePanel settings={state.settings} updateCamera={state.updateCamera} />
              <EffectsPanel settings={state.settings} updateCamera={state.updateCamera} />
              <NightVisionPanel
                value={state.settings()?.camera?.nightVisionMode ?? API_DEFAULTS.cameraNightVisionMode}
                onChange={(mode: NightVisionMode) => state.updateCamera({ nightVisionMode: mode })}
              />
            </Rack>
            <Show when={state.settings()?.streaming}>
              <Rack id={sectionId('overlays')} title={t('system.section.overlays')}>
                <OverlayPanel
                  streaming={() => state.settings()!.streaming}
                  onUpdate={state.updateStreamingDebounced}
                />
                <PrivacyMaskingPanel
                  streaming={() => state.settings()!.streaming}
                  onUpdate={state.updateStreamingDebounced}
                />
              </Rack>
            </Show>
          </Show>

          {/* ── Capture ── */}
          <Show when={tab() === 'capture'}>
            <Rack id={sectionId('scheduled')} title={t('system.section.scheduled')}>
              <IntervalCapturePanel
                intervalConfig={state.intervalConfig}
                setIntervalConfig={state.setIntervalConfig}
                intervalRunning={state.intervalRunning}
                intervalCompleted={state.intervalCompleted}
                handleStartIntervalCapture={state.handleStartIntervalCapture}
                handleStopIntervalCapture={state.handleStopIntervalCapture}
              />
              <RecordingPanel
                recordingConfig={state.recordingConfig}
                setRecordingConfig={state.setRecordingConfig}
                isRecording={state.isRecording}
                isScheduled={state.isScheduled}
                scheduledStartTimeMs={state.scheduledStartTimeMs}
                recordingTimer={state.recordingTimer}
                handleStartRecording={state.handleStartRecording}
                handleStopRecording={state.handleStopRecording}
              />
            </Rack>
            <Show when={state.settings()?.streaming}>
              <Rack id={sectionId('reliability')} title={t('system.section.reliability')} single>
                <WatchdogPanel
                  settings={state.settings}
                  status={state.status}
                  updateStreamingAndSave={state.updateStreamingAndSave}
                  updateStreamingDebounced={state.updateStreamingDebounced}
                />
              </Rack>
            </Show>
          </Show>

          {/* ── Streaming ── */}
          <Show when={tab() === 'streaming'}>
            <Rack id={sectionId('web')} title={t('system.section.web')}>
              <WebStreamCard {...streamingCardProps()} />
              <AudioCard {...streamingCardProps()} />
            </Rack>
            <Rack id={sectionId('protocols')} title={t('system.section.protocols')}>
              <RtspCard {...streamingCardProps()} />
              <Show when={state.settings()?.streaming}>
                <WhipPanel
                  settings={state.settings}
                  status={state.status}
                  streamActionLoading={state.streamActionLoading}
                  updateStreamingAndSave={state.updateStreamingAndSave}
                  updateStreamingDebounced={state.updateStreamingDebounced}
                  handleStartWhip={state.handleStartWhip}
                  handleStopWhip={state.handleStopWhip}
                />
                <RtmpPanel
                  settings={state.settings}
                  status={state.status}
                  streamActionLoading={state.streamActionLoading}
                  updateStreamingAndSave={state.updateStreamingAndSave}
                  updateStreamingDebounced={state.updateStreamingDebounced}
                  handleStartRtmp={state.handleStartRtmp}
                  handleStopRtmp={state.handleStopRtmp}
                />
              </Show>
            </Rack>
            <Rack id={sectionId('service')} title={t('system.section.service')}>
              <HttpsCard {...streamingCardProps()} />
              <KeepAwakeCard {...streamingCardProps()} />
            </Rack>
          </Show>

          {/* ── Storage ── */}
          <Show when={tab() === 'storage'}>
            <Show when={state.settings()?.streaming}>
              <Rack id={sectionId('retention')} title={t('system.section.retention')} single>
                <StoragePanel
                  settings={state.settings}
                  updateStreamingAndSave={state.updateStreamingAndSave}
                  updateStreamingDebounced={state.updateStreamingDebounced}
                />
              </Rack>
            </Show>
            <Rack id={sectionId('history')} title={t('system.section.history')} single>
              <RecordingTimelinePanel />
            </Rack>
            <Rack id={sectionId('device')} title={t('system.section.device')} single>
              <SystemInfoPanel />
            </Rack>
          </Show>

          {/* ── Links (connections) ── */}
          <Show when={tab() === 'links'}>
            <Rack id={sectionId('cameras')} title={t('system.section.cameras')} single wide>
              <MultiCamPanel />
            </Rack>
            <Rack id={sectionId('sharing')} title={t('system.section.sharing')} single wide>
              <SharePanel />
            </Rack>
            <Rack id={sectionId('notifications')} title={t('system.section.notifications')}>
              <PushPanel settings={state.settings} updateStreamingAndSave={state.updateStreamingAndSave} />
              <UnifiedPushPanel settings={state.settings} updateStreamingAndSave={state.updateStreamingAndSave} />
            </Rack>
            <Rack id={sectionId('messaging')} title={t('system.section.messaging')} single>
              <MqttPanel
                settings={state.settings}
                updateStreamingAndSave={state.updateStreamingAndSave}
                updateStreamingDebounced={state.updateStreamingDebounced}
              />
            </Rack>
          </Show>

          {/* ── Admin ── */}
          <Show when={tab() === 'admin' && !state.isViewer()}>
            <Rack id={sectionId('access')} title={t('system.section.access')} single>
              <AuthPanel />
            </Rack>
            <Rack id={sectionId('backup')} title={t('system.section.backup')}>
              <BackupPanel
                settings={state.settings}
                updateStreamingAndSave={state.updateStreamingAndSave}
                updateStreamingDebounced={state.updateStreamingDebounced}
              />
              <ConfigBackupPanel />
            </Rack>
            <Rack id={sectionId('audit')} title={t('system.section.audit')} single wide>
              <AuditPanel />
            </Rack>
          </Show>
        </div>
      </Show>
    </div>
  )
}
