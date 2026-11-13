import { Show } from 'solid-js'
import type { AllSettings, DeviceStatus } from '../types'
import SecurityCard from './SecurityCard'
import EventFeed from './EventFeed'
import DetectionStatsCard from './DetectionStatsCard'
import RecordingTimeline from './RecordingTimeline'
import StorageCard from './StorageCard'
import SystemPanel from './SystemPanel'
import BackupCard from './BackupCard'
import MqttCard from './MqttCard'
import PushCard from './PushCard'
import UnifiedPushCard from './UnifiedPushCard'
import WhipCard from './WhipCard'
import RtmpCard from './RtmpCard'
import AuthCard from './AuthCard'
import AuditCard from './AuditCard'
import ConfigBackupCard from './ConfigBackupCard'
import AutomationCard from './AutomationCard'
import { WebStreamCard, AudioCard, HttpsCard, RtspCard, KeepAwakeCard } from './next/views/system/StreamingCards'

interface Props {
  settings: () => AllSettings | null
  status: () => DeviceStatus | null
  streamActionLoading: () => boolean
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
  updateStreamingDebounced: (patch: Partial<AllSettings['streaming']>) => void
  setRecordingConfigAudio: (v: boolean) => void
  handleStartWhip: () => void
  handleStopWhip: () => void
  handleStartRtmp: () => void
  handleStopRtmp: () => void
  /** True for a viewer-role session: the admin-only cards render nothing. */
  readOnly?: () => boolean
}

export default function AppSettingsPanel(props: Props) {
  // A viewer session hides the admin-only surfaces entirely; the remaining
  // write controls stay on the banner + 403 path (the server answers
  // "Admin access required" and the global error handling surfaces it).
  const isAdmin = () => !props.readOnly?.()

  return (
    <section class="settings-panel" id="app-settings-panel">
      {/* Streaming */}
      <WebStreamCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
        setRecordingConfigAudio={props.setRecordingConfigAudio}
      />

      {/* Audio */}
      <AudioCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
        setRecordingConfigAudio={props.setRecordingConfigAudio}
      />

      {/* HTTPS */}
      <HttpsCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
        setRecordingConfigAudio={props.setRecordingConfigAudio}
      />

      {/* RTSP */}
      <RtspCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
        setRecordingConfigAudio={props.setRecordingConfigAudio}
      />

      <WhipCard
        settings={props.settings}
        status={props.status}
        streamActionLoading={props.streamActionLoading}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
        handleStartWhip={props.handleStartWhip}
        handleStopWhip={props.handleStopWhip}
      />

      <RtmpCard
        settings={props.settings}
        status={props.status}
        streamActionLoading={props.streamActionLoading}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
        handleStartRtmp={props.handleStartRtmp}
        handleStopRtmp={props.handleStopRtmp}
      />

      <SecurityCard
        settings={props.settings}
        status={props.status}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
      />

      {/* Time-of-day automation rules (GET/PUT /api/automation): the card
          fetches and saves its own document, so it rides no settings prop. */}
      <AutomationCard readOnly={props.readOnly} />

      {/* #/events deep links scroll the feed into view via this anchor. */}
      <div id="event-feed-anchor">
        <EventFeed readOnly={props.readOnly} />
      </div>

      <DetectionStatsCard />

      <BackupCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
      />

      <StorageCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
      />

      <RecordingTimeline />

      {/* Keep awake (the security-phone posture): the CPU hold that lets the
          automation engine keep ticking while the screen is locked. */}
      <KeepAwakeCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
        setRecordingConfigAudio={props.setRecordingConfigAudio}
      />

      <SystemPanel />

      <MqttCard
        settings={props.settings}
        updateStreamingAndSave={props.updateStreamingAndSave}
        updateStreamingDebounced={props.updateStreamingDebounced}
      />

      <PushCard settings={props.settings} updateStreamingAndSave={props.updateStreamingAndSave} />

      <UnifiedPushCard settings={props.settings} updateStreamingAndSave={props.updateStreamingAndSave} />

      <Show when={isAdmin()}>
        <AuthCard />

        <ConfigBackupCard />

        <AuditCard />
      </Show>
    </section>
  )
}
