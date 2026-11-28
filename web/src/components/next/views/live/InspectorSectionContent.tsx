import { Show, type JSX } from 'solid-js'
import { API_DEFAULTS } from '../../../../api/defaults'
import type { NightVisionMode } from '../../../../types'
import type { NextState } from '../../NextState'
import { t } from '../../../../lib/i18n'
import LensPanel from '../system/cards/LensPanel'
import ExposurePanel from '../system/cards/ExposurePanel'
import FocusPanel from '../system/cards/FocusPanel'
import WhiteBalancePanel from '../system/cards/WhiteBalancePanel'
import ZoomFramePanel from '../system/cards/ZoomFramePanel'
import EffectsPanel from '../system/cards/EffectsPanel'
import NightVisionPanel from '../system/cards/NightVisionPanel'
import OverlayPanel from '../system/cards/OverlayPanel'
import PrivacyMaskingPanel from '../system/cards/PrivacyMaskingPanel'
import IntervalCapturePanel from '../system/cards/IntervalCapturePanel'
import RecordingPanel from '../system/cards/RecordingPanel'
import WatchdogPanel from '../system/cards/WatchdogPanel'
import { WebStreamCard, AudioCard, HttpsCard, RtspCard, KeepAwakeCard } from '../system/StreamingCards'
import WhipPanel from '../system/cards/WhipPanel'
import RtmpPanel from '../system/cards/RtmpPanel'
import StoragePanel from './StoragePanel'
import SystemInfoPanel from './SystemInfoPanel'
import RecordingTimelinePanel from '../system/cards/RecordingTimelinePanel'
import MultiCamPanel from '../system/cards/MultiCamPanel'
import SharePanel from '../system/cards/SharePanel'
import PushPanel from '../system/cards/PushPanel'
import UnifiedPushPanel from '../system/cards/UnifiedPushPanel'
import MqttPanel from '../system/cards/MqttPanel'
import AuthPanel from '../system/cards/AuthPanel'
import BackupPanel from '../system/cards/BackupPanel'
import ConfigBackupPanel from '../system/cards/ConfigBackupPanel'
import AuditPanel from '../system/cards/AuditPanel'
import ChannelMatrix from './ChannelMatrix'
import ClientsPanel from './ClientsPanel'
import MaskingQuickPanel from './masking/MaskingQuickPanel'
import CustomSectionPanel from './CustomSectionPanel'
import { webStreamActive } from './masking/webStreamActive'
import EventLog from '../detection/EventLog'
import StatsPanel from '../detection/StatsPanel'
import SecurityPanel from '../detection/SecurityPanel'
import AutomationPanel from '../detection/AutomationPanel'

interface Props {
  state: NextState
  kind: 'builtin' | 'custom'
  sectionId: string
  items: readonly string[]
  customTitle?: string | null
  readOnly: boolean
}

export default function InspectorSectionContent(props: Props): JSX.Element {
  const state = props.state
  if (props.kind === 'custom') {
    return <CustomSectionPanel state={state} itemIds={props.items} readOnly={props.readOnly} />
  }
  const streamingProps = () => ({
    settings: state.settings,
    updateStreamingAndSave: state.updateStreamingAndSave,
    updateStreamingDebounced: state.updateStreamingDebounced,
    setRecordingConfigAudio: (v: boolean) => state.setRecordingConfig({ ...state.recordingConfig(), includeAudio: v }),
  })
  const streamStatusProps = () => ({
    settings: state.settings,
    status: state.status,
    streamActionLoading: state.streamActionLoading,
    updateStreamingAndSave: state.updateStreamingAndSave,
    updateStreamingDebounced: state.updateStreamingDebounced,
  })

  switch (props.sectionId) {
    case 'channels':
      return <ChannelMatrix state={state} />
    case 'storage':
      return (
        <Show when={state.settings()?.streaming}>
          <StoragePanel
            settings={state.settings}
            updateStreamingAndSave={state.updateStreamingAndSave}
            updateStreamingDebounced={state.updateStreamingDebounced}
          />
        </Show>
      )
    case 'clients':
      return <ClientsPanel readOnly={state.isViewer} />
    case 'masking-quick':
      return (
        <Show when={state.settings()?.streaming}>
          <MaskingQuickPanel state={state} />
        </Show>
      )
    case 'system-info':
      return <SystemInfoPanel />
    case 'lens':
      return (
        <Show when={state.lenses().length > 0}>
          <LensPanel lenses={state.lenses} handleSelectLens={state.handleSelectLens} />
        </Show>
      )
    case 'exposure':
      return <ExposurePanel settings={state.settings} updateCamera={state.updateCamera} />
    case 'focus':
      return <FocusPanel settings={state.settings} updateCamera={state.updateCamera} />
    case 'white-balance':
      return <WhiteBalancePanel settings={state.settings} updateCamera={state.updateCamera} />
    case 'zoom-frame':
      return <ZoomFramePanel settings={state.settings} updateCamera={state.updateCamera} />
    case 'effects':
      return <EffectsPanel settings={state.settings} updateCamera={state.updateCamera} />
    case 'night-vision':
      return (
        <NightVisionPanel
          value={state.settings()?.camera?.nightVisionMode ?? API_DEFAULTS.cameraNightVisionMode}
          onChange={(mode: NightVisionMode) => state.updateCamera({ nightVisionMode: mode })}
        />
      )
    case 'overlay':
      return (
        <Show when={state.settings()?.streaming}>
          <OverlayPanel
            streaming={() => state.settings()!.streaming}
            status={state.status}
            onUpdate={state.updateStreamingDebounced}
          />
        </Show>
      )
    case 'privacy-masking':
      return (
        <Show when={state.settings()?.streaming}>
          <PrivacyMaskingPanel
            streaming={() => state.settings()!.streaming}
            onUpdate={state.updateStreamingDebounced}
            streamActive={() => webStreamActive(state)}
          />
        </Show>
      )
    case 'interval-capture':
      return (
        <IntervalCapturePanel
          intervalConfig={state.intervalConfig}
          setIntervalConfig={state.setIntervalConfig}
          intervalRunning={state.intervalRunning}
          intervalCompleted={state.intervalCompleted}
          handleStartIntervalCapture={state.handleStartIntervalCapture}
          handleStopIntervalCapture={state.handleStopIntervalCapture}
        />
      )
    case 'recording':
      return (
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
      )
    case 'watchdog':
      return (
        <Show when={state.settings()?.streaming}>
          <WatchdogPanel
            settings={state.settings}
            status={state.status}
            updateStreamingAndSave={state.updateStreamingAndSave}
            updateStreamingDebounced={state.updateStreamingDebounced}
          />
        </Show>
      )
    case 'web-stream':
      return <WebStreamCard {...streamingProps()} />
    case 'audio':
      return <AudioCard {...streamingProps()} />
    case 'rtsp':
      return <RtspCard {...streamingProps()} />
    case 'whip':
      return (
        <Show when={state.settings()?.streaming}>
          <WhipPanel {...streamStatusProps()} handleStartWhip={state.handleStartWhip} handleStopWhip={state.handleStopWhip} />
        </Show>
      )
    case 'rtmp':
      return (
        <Show when={state.settings()?.streaming}>
          <RtmpPanel {...streamStatusProps()} handleStartRtmp={state.handleStartRtmp} handleStopRtmp={state.handleStopRtmp} />
        </Show>
      )
    case 'https':
      return <HttpsCard {...streamingProps()} />
    case 'keep-awake':
      return <KeepAwakeCard {...streamingProps()} />
    case 'recording-timeline':
      return <RecordingTimelinePanel />
    case 'multicam':
      return <MultiCamPanel />
    case 'share':
      return <SharePanel />
    case 'push':
      return <PushPanel settings={state.settings} updateStreamingAndSave={state.updateStreamingAndSave} />
    case 'unifiedpush':
      return <UnifiedPushPanel settings={state.settings} updateStreamingAndSave={state.updateStreamingAndSave} />
    case 'mqtt':
      return <MqttPanel settings={state.settings} updateStreamingAndSave={state.updateStreamingAndSave} updateStreamingDebounced={state.updateStreamingDebounced} />
    case 'event-log':
      return <EventLog readOnly={state.isViewer} />
    case 'detection-stats':
      return <StatsPanel />
    case 'security':
      return (
        <SecurityPanel
          settings={state.settings}
          status={state.status}
          updateStreamingAndSave={state.updateStreamingAndSave}
          updateStreamingDebounced={state.updateStreamingDebounced}
        />
      )
    case 'automation':
      return <AutomationPanel readOnly={state.isViewer} />
    case 'auth':
      return <AuthPanel />
    case 'backup':
      return <BackupPanel settings={state.settings} updateStreamingAndSave={state.updateStreamingAndSave} updateStreamingDebounced={state.updateStreamingDebounced} />
    case 'config-backup':
      return <ConfigBackupPanel />
    case 'audit':
      return <AuditPanel />
    default:
      return <div class="nx-inspector-empty">{t('next.inspector.unknownSection')}</div>
  }
}
