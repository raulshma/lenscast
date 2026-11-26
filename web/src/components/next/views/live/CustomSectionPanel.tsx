import { createEffect, createMemo, createSignal, For, Show, type JSX } from 'solid-js'
import type { NextState } from '../../NextState'
import type {
  AllSettings,
  CameraSettings,
  ControlRange,
  FocusMode,
  HdrMode,
  NightVisionMode,
  OutputRotation,
  RecordingConfig,
  RecordingQuality,
  Resolution,
  RtspResolution,
  WhiteBalance,
} from '../../../../types'
import {
  FOCUS_MODE_LABELS,
  FRAME_RATE_OPTIONS,
  HDR_LABELS,
  OUTPUT_ROTATION_OPTIONS,
  RECORDING_QUALITY_LABELS,
  RESOLUTION_LABELS,
  WB_LABELS,
} from '../../../../types'
import { API_DEFAULTS } from '../../../../api/defaults'
import { sendTestAlert, setSiren, setTorch } from '../../../../api/client'
import { t } from '../../../../lib/i18n'
import { FOCUS_MODE_I18N_KEYS, WB_MODE_I18N_KEYS } from '../../../../lib/cameraLabels'
import { clampExposureIndex, clampRatio, DEFAULT_EV_RANGE, zoomReadout } from '../../../../lib/cameraControlsPolicy'
import { maskEditActive, startMaskEdit } from '../../../../lib/maskEditStore'
import { streamToggle } from '../../../../lib/streamToggle'
import { channelStates, type ChannelState } from '../../lib/telemetry'
import {
  Button,
  Field,
  Led,
  Panel,
  Select,
  SliderField,
  ToggleField,
  type LedTone,
  type SelectOption,
} from '../../ui'
import { SettingItem } from '../../ui/SettingItem'
import { controlItem } from './controlItemCatalog'
import { webStreamActive } from './masking/webStreamActive'

export interface CustomSectionPanelProps {
  state: NextState
  itemIds: readonly string[]
  readOnly?: boolean
}

const ISO_FALLBACK_RANGE: ControlRange = { min: 100, max: 32_000 }
const EXPOSURE_TIME_MIN_MS = 0.1
const EXPOSURE_TIME_MAX_MS = 500
const STREAM_RESOLUTIONS: readonly SelectOption[] = [
  { value: '480p', label: '480p (4:3)' },
  { value: '720p', label: '720p' },
  { value: '720p43', label: '720p (4:3)' },
  { value: '1080p', label: '1080p' },
  { value: '1080p43', label: '1080p (4:3)' },
]

const HDR_KEYS: Record<HdrMode, string> = {
  OFF: 'common.off',
  ON: 'common.on',
  AUTO: 'common.auto',
}
const NIGHT_VISION_KEYS: Record<NightVisionMode, string> = {
  OFF: 'common.off',
  AUTO: 'common.auto',
  ON: 'option.irOn',
}
const QUALITY_KEYS: Record<RecordingQuality, string> = {
  HIGH: 'option.quality.high',
  MEDIUM: 'option.quality.medium',
  LOW: 'option.quality.low',
}

function clampIso(value: number, range: ControlRange): number {
  const finite = Number.isFinite(value) ? value : API_DEFAULTS.cameraIso
  return Math.min(Math.max(Math.round(finite), range.min), range.max)
}

function clampExposureMs(value: number): number {
  return Math.min(Math.max(value, EXPOSURE_TIME_MIN_MS), EXPOSURE_TIME_MAX_MS)
}

function channelStatusText(channel: ChannelState): string {
  if (channel.status === 'blocked') return 'LOCKED'
  if (channel.active) return 'ON AIR'
  if (channel.status === 'connecting') return 'SYNC'
  if (channel.status === 'error') return 'FAULT'
  return channel.enabled ? 'RDY' : 'OFF'
}

function channelStatusClass(channel: ChannelState): string {
  if (channel.status === 'blocked') return 'nx-channel-status-warn'
  if (channel.active) return 'nx-channel-status-air'
  if (channel.status === 'connecting') return 'nx-channel-status-warn'
  if (channel.status === 'error') return 'nx-channel-status-fault'
  return ''
}

function channelLedTone(channel: ChannelState): LedTone {
  if (channel.status === 'blocked') return 'warn'
  if (channel.active) return 'rec'
  if (channel.status === 'connecting') return 'warn'
  if (channel.status === 'error') return 'danger'
  return 'off'
}

/**
 * The beta inspector's ordered, user-pinned controls. Each entry resolves
 * through the catalog and reuses the same state writers as its full panel;
 * this surface owns no settings copy, timers, endpoints, or save pipeline.
 */
export default function CustomSectionPanel(props: CustomSectionPanelProps) {
  const state = props.state
  const entries = createMemo(() => props.itemIds.flatMap((id, index) => {
    const item = controlItem(id)
    return item?.custom ? [{ id, item, key: `${index}:${id}` }] : []
  }))

  const settings = () => state.settings()
  const camera = () => settings()?.camera
  const streaming = () => settings()?.streaming
  const locked = () => props.readOnly === true || state.isViewer()
  const cameraDisabled = () => locked() || camera() == null
  const streamingDisabled = () => locked() || streaming() == null
  const webEnabled = () => streaming()?.webStreamingEnabled ?? API_DEFAULTS.webStreamingEnabled
  const motionEnabled = () => streaming()?.motionDetectionEnabled ?? API_DEFAULTS.motionDetectionEnabled

  const cameraWrite = (patch: Partial<CameraSettings>) => {
    if (locked() || camera() == null) return
    state.updateCamera(patch)
  }
  const streamingWrite = (patch: Partial<AllSettings['streaming']>) => {
    if (locked() || streaming() == null) return
    state.updateStreamingAndSave(patch)
  }
  const streamingWriteDebounced = (patch: Partial<AllSettings['streaming']>) => {
    if (locked() || streaming() == null) return
    state.updateStreamingDebounced(patch)
  }
  const recordingWrite = (value: RecordingConfig) => {
    if (!locked()) state.setRecordingConfig(value)
  }

  const streamToggles = {
    web: streamToggle(streaming, 'webStreamingEnabled', streamingWrite),
    adaptive: streamToggle(streaming, 'adaptiveBitrateEnabled', streamingWrite),
    audio: streamToggle(streaming, 'streamAudioEnabled', streamingWrite),
    rtsp: streamToggle(streaming, 'rtspEnabled', streamingWrite),
    whip: streamToggle(streaming, 'whipEnabled', streamingWrite),
    rtmp: streamToggle(streaming, 'rtmpEnabled', streamingWrite),
    keepAwake: streamToggle(streaming, 'keepAwakeEnabled', streamingWrite),
    motion: streamToggle(streaming, 'motionDetectionEnabled', streamingWrite),
    sound: streamToggle(streaming, 'soundDetectionEnabled', streamingWrite),
    ml: streamToggle(streaming, 'mlDetectionEnabled', streamingWrite),
    overlay: streamToggle(streaming, 'overlayEnabled', streamingWriteDebounced),
    masking: streamToggle(streaming, 'maskingEnabled', streamingWriteDebounced),
  }

  const evRange = () => state.status()?.exposureCompensationRange ?? DEFAULT_EV_RANGE
  const isoRange = () => state.status()?.isoRange ?? ISO_FALLBACK_RANGE
  const zoomMax = () => Math.max(1, state.status()?.zoomRange?.max ?? API_DEFAULTS.cameraZoomMaxRatio)
  const evValue = () => clampExposureIndex(
    camera()?.exposureCompensation ?? API_DEFAULTS.cameraExposureCompensation,
    evRange(),
  )
  const zoomValue = () => clampRatio(camera()?.zoomRatio ?? API_DEFAULTS.cameraZoomRatio, zoomMax())

  const [sirenOn, setSirenOn] = createSignal(false)
  const [sirenBusy, setSirenBusy] = createSignal(false)
  const [torchOn, setTorchOn] = createSignal(false)
  const [torchBusy, setTorchBusy] = createSignal(false)
  const [testAlertBusy, setTestAlertBusy] = createSignal(false)
  const [testAlertResult, setTestAlertResult] = createSignal('')

  // Device truth can change through auto-deterrence or another client. Mirror
  // every status snapshot, exactly as the full SecurityPanel does.
  createEffect(() => {
    const device = state.status()
    if (device) {
      setSirenOn(device.sirenActive)
      setTorchOn(device.torchOn)
    }
  })

  function runGuarded(busy: () => boolean, setBusy: (value: boolean) => void, action: () => Promise<unknown>) {
    if (locked() || busy()) return
    setBusy(true)
    action()
      .catch(() => {})
      .finally(() => setBusy(false))
  }

  function toggleSiren() {
    const next = !sirenOn()
    runGuarded(sirenBusy, setSirenBusy, () => setSiren(next).then(() => setSirenOn(next)))
  }

  function toggleTorch() {
    const next = !torchOn()
    runGuarded(torchBusy, setTorchBusy, () => setTorch(next).then(() => setTorchOn(next)))
  }

  function sendTestAlertNow() {
    if (locked() || testAlertBusy()) return
    setTestAlertBusy(true)
    setTestAlertResult('')
    sendTestAlert()
      .then((result) => {
        setTestAlertResult(
          result.success
            ? result.dispatchedActions.length > 0
              ? t('security.testAlertVia', { channels: result.dispatchedActions.join(', ') })
              : t('security.testAlertNoChannel')
            : t('security.testAlertUnavailable'),
        )
      })
      .catch(() => setTestAlertResult(t('security.testAlertFailed')))
      .finally(() => setTestAlertBusy(false))
  }

  function captureSnapshot() {
    if (!locked()) void state.handleCapture()
  }

  function toggleRecording() {
    if (locked()) return
    if (state.isRecording() || state.isScheduled()) {
      state.handleStopRecording()
      return
    }
    // A custom start has no schedule field; do not inherit a stale schedule
    // selected in another recording surface.
    state.setRecordingConfig({ ...state.recordingConfig(), startTimeMs: null })
    state.handleStartRecording()
  }

  function toggleIntervalCapture() {
    if (locked()) return
    if (state.intervalRunning()) state.handleStopIntervalCapture()
    else state.handleStartIntervalCapture()
  }

  const streamOn = () => webStreamActive(state)
  const maskingReady = () => streaming() != null
  const maskingZones = () => streaming()?.maskingZones ?? []
  const maskEditorDisabled = () => locked() || !maskingReady() || !streamOn() || maskEditActive()

  function openMaskEditor() {
    if (maskEditorDisabled()) return
    startMaskEdit()
  }

  const focusOptions = (): SelectOption[] => Object.keys(FOCUS_MODE_LABELS).map((key) => ({
    value: key,
    label: t(FOCUS_MODE_I18N_KEYS[key as FocusMode]),
  }))
  const wbOptions = (): SelectOption[] => Object.keys(WB_LABELS).map((key) => ({
    value: key,
    label: t(WB_MODE_I18N_KEYS[key as WhiteBalance]),
  }))
  const hdrOptions = (): SelectOption[] => Object.keys(HDR_LABELS).map((key) => ({
    value: key,
    label: t(HDR_KEYS[key as HdrMode]),
  }))
  const nightVisionOptions = (): SelectOption[] => (['OFF', 'AUTO', 'ON'] as NightVisionMode[]).map((value) => ({
    value,
    label: t(NIGHT_VISION_KEYS[value]),
  }))
  const recordingQualityOptions = (): SelectOption[] => Object.keys(RECORDING_QUALITY_LABELS).map((key) => ({
    value: key,
    label: t(QUALITY_KEYS[key as RecordingQuality]),
  }))

  function renderControl(id: string): JSX.Element {
    switch (id) {
      case 'exposure.compensation':
        return (
          <SliderField
            label={t('exposure.compensation')}
            min={evRange().min}
            max={evRange().max}
            step={1}
            value={evValue()}
            display={`${evValue()} EV`}
            disabled={cameraDisabled()}
            onChange={(value) => cameraWrite({ exposureCompensation: clampExposureIndex(value, evRange()) })}
          />
        )

      case 'exposure.iso': {
        const manualIso = () => {
          const value = camera()?.iso
          return value == null ? API_DEFAULTS.cameraIso : clampIso(value, isoRange())
        }
        return (
          <Field label={t('exposure.iso')}>
            <div style={{ display: 'flex', gap: 'var(--space-xs)', 'align-items': 'center', 'flex-wrap': 'wrap' }}>
              <Select
                small
                disabled={cameraDisabled()}
                value={camera()?.iso == null ? 'auto' : 'manual'}
                options={[
                  { value: 'auto', label: t('common.auto') },
                  { value: 'manual', label: t('common.manual') },
                ]}
                ariaLabel={t('exposure.iso')}
                onChange={(value) => cameraWrite(value === 'auto'
                  ? { iso: null }
                  : { iso: clampIso(camera()?.iso ?? API_DEFAULTS.cameraIso, isoRange()) })}
              />
              <Show when={camera()?.iso != null}>
                <input
                  type="number"
                  class="nx-input nx-input-sm nx-mono nx-input-num"
                  value={manualIso()}
                  min={isoRange().min}
                  max={isoRange().max}
                  step={1}
                  disabled={cameraDisabled()}
                  aria-label={t('exposure.iso')}
                  onChange={(event) => {
                    if (event.currentTarget.value.trim() === '') {
                      cameraWrite({ iso: null })
                      return
                    }
                    const value = Number.parseFloat(event.currentTarget.value)
                    if (Number.isFinite(value)) cameraWrite({ iso: clampIso(value, isoRange()) })
                  }}
                />
              </Show>
            </div>
          </Field>
        )
      }

      case 'exposure.time': {
        const manualTimeMs = () => Number(((camera()?.exposureTime ?? API_DEFAULTS.cameraExposureTime) / 1_000_000).toFixed(3))
        return (
          <Field label={t('exposure.time')}>
            <div style={{ display: 'flex', gap: 'var(--space-xs)', 'align-items': 'center', 'flex-wrap': 'wrap' }}>
              <Select
                small
                disabled={cameraDisabled()}
                value={camera()?.exposureTime == null ? 'auto' : 'manual'}
                options={[
                  { value: 'auto', label: t('common.auto') },
                  { value: 'manual', label: t('common.manual') },
                ]}
                ariaLabel={t('exposure.time')}
                onChange={(value) => cameraWrite(value === 'auto'
                  ? { exposureTime: null }
                  : { exposureTime: camera()?.exposureTime ?? API_DEFAULTS.cameraExposureTime })}
              />
              <Show when={camera()?.exposureTime != null}>
                <input
                  type="number"
                  class="nx-input nx-input-sm nx-mono nx-input-num"
                  value={manualTimeMs()}
                  min={EXPOSURE_TIME_MIN_MS}
                  max={EXPOSURE_TIME_MAX_MS}
                  step={0.1}
                  placeholder="ms"
                  disabled={cameraDisabled()}
                  aria-label={t('exposure.timeMs')}
                  onChange={(event) => {
                    if (event.currentTarget.value.trim() === '') {
                      event.currentTarget.value = String(manualTimeMs())
                      return
                    }
                    const milliseconds = Number.parseFloat(event.currentTarget.value)
                    if (Number.isFinite(milliseconds)) {
                      cameraWrite({ exposureTime: Math.round(clampExposureMs(milliseconds) * 1_000_000) })
                    }
                  }}
                />
              </Show>
            </div>
          </Field>
        )
      }

      case 'focus.mode':
        return (
          <Field label={t('focus.mode')}>
            <Select
              disabled={cameraDisabled()}
              value={camera()?.focusMode ?? API_DEFAULTS.cameraFocusMode}
              options={focusOptions}
              ariaLabel={t('focus.mode')}
              onChange={(value) => cameraWrite({ focusMode: value as FocusMode })}
            />
          </Field>
        )

      case 'focus.distance':
        return (
          <SliderField
            label={t('focus.distance')}
            display={(camera()?.focusDistance ?? API_DEFAULTS.cameraFocusDistance).toFixed(1)}
            min={0}
            max={10}
            step={0.1}
            value={camera()?.focusDistance ?? API_DEFAULTS.cameraFocusDistance}
            disabled={cameraDisabled() || camera()?.focusMode !== 'MANUAL'}
            onChange={(value) => {
              if (camera()?.focusMode === 'MANUAL') cameraWrite({ focusDistance: value })
            }}
          />
        )

      case 'whiteBalance.preset':
        return (
          <Field label={t('common.mode')}>
            <Select
              disabled={cameraDisabled()}
              value={camera()?.whiteBalance ?? API_DEFAULTS.cameraWhiteBalance}
              options={wbOptions}
              ariaLabel={t('common.mode')}
              onChange={(value) => cameraWrite({ whiteBalance: value as WhiteBalance })}
            />
          </Field>
        )

      case 'whiteBalance.temperature':
        return (
          <SliderField
            label={t('wb.temp')}
            display={`${camera()?.colorTemperature ?? API_DEFAULTS.cameraColorTemperature}K`}
            min={2000}
            max={9000}
            step={100}
            value={camera()?.colorTemperature ?? API_DEFAULTS.cameraColorTemperature}
            disabled={cameraDisabled() || camera()?.whiteBalance !== 'MANUAL'}
            onChange={(value) => {
              if (camera()?.whiteBalance === 'MANUAL') cameraWrite({ colorTemperature: value })
            }}
          />
        )

      case 'zoomFrame.zoom':
        return (
          <SliderField
            label={t('zoomframe.zoom')}
            min={1}
            max={zoomMax()}
            step={0.1}
            value={zoomValue()}
            display={zoomReadout(zoomValue())}
            disabled={cameraDisabled()}
            onChange={(value) => cameraWrite({ zoomRatio: clampRatio(value, zoomMax()) })}
          />
        )

      case 'zoomFrame.frameRate':
        return (
          <Field label={t('zoomframe.frameRate')}>
            <Select
              disabled={cameraDisabled()}
              value={String(camera()?.frameRate ?? API_DEFAULTS.cameraFrameRate)}
              options={FRAME_RATE_OPTIONS.map((rate) => ({ value: String(rate), label: `${rate} fps` }))}
              ariaLabel={t('zoomframe.frameRate')}
              onChange={(value) => cameraWrite({ frameRate: Number.parseInt(value, 10) })}
            />
          </Field>
        )

      case 'zoomFrame.resolution':
        return (
          <Field label={t('common.resolution')}>
            <Select
              disabled={cameraDisabled()}
              value={camera()?.resolution ?? API_DEFAULTS.cameraResolution}
              options={Object.keys(RESOLUTION_LABELS).map((key) => ({ value: key, label: RESOLUTION_LABELS[key as Resolution] }))}
              ariaLabel={t('common.resolution')}
              onChange={(value) => cameraWrite({ resolution: value as Resolution })}
            />
          </Field>
        )

      case 'zoomFrame.rotation':
        return (
          <Field label={t('zoomframe.rotation')}>
            <Select
              disabled={cameraDisabled()}
              value={String(camera()?.outputRotation ?? API_DEFAULTS.cameraOutputRotation)}
              options={OUTPUT_ROTATION_OPTIONS.map((rotation) => ({ value: String(rotation), label: `${rotation}°` }))}
              ariaLabel={t('zoomframe.rotation')}
              onChange={(value) => cameraWrite({ outputRotation: Number.parseInt(value, 10) as OutputRotation })}
            />
          </Field>
        )

      case 'zoomFrame.orientationLocked':
        return (
          <ToggleField
            label={t('zoomframe.orientationLocked')}
            checked={camera()?.orientationLocked ?? API_DEFAULTS.cameraOrientationLocked}
            disabled={cameraDisabled()}
            onChange={(value) => cameraWrite({ orientationLocked: value })}
          />
        )

      case 'effects.stabilization':
        return (
          <ToggleField
            label={t('effects.stabilization')}
            checked={camera()?.stabilization ?? API_DEFAULTS.cameraStabilization}
            disabled={cameraDisabled()}
            onChange={(value) => cameraWrite({ stabilization: value })}
          />
        )

      case 'effects.hdr':
        return (
          <Field label={t('effects.hdr')}>
            <Select
              disabled={cameraDisabled()}
              value={camera()?.hdrMode ?? API_DEFAULTS.cameraHdrMode}
              options={hdrOptions}
              ariaLabel={t('effects.hdr')}
              onChange={(value) => cameraWrite({ hdrMode: value as HdrMode })}
            />
          </Field>
        )

      case 'nightVision.mode':
        return (
          <Field label={t('nightvision.title')}>
            <Select
              disabled={cameraDisabled()}
              value={camera()?.nightVisionMode ?? API_DEFAULTS.cameraNightVisionMode}
              options={nightVisionOptions}
              ariaLabel={t('nightvision.title')}
              onChange={(value) => cameraWrite({ nightVisionMode: value as NightVisionMode })}
            />
          </Field>
        )

      case 'channels.status':
        return (
          <For each={channelStates(state.status())}>
            {(channel) => (
              <div class="nx-channel">
                <div class="nx-channel-head">
                  <Led tone={channelLedTone(channel)} />
                  <span class="nx-channel-code">{channel.code}</span>
                  <span class={`nx-channel-status ${channelStatusClass(channel)}`}>{channelStatusText(channel)}</span>
                </div>
                <Show when={channel.error}>
                  <div class="nx-banner nx-banner-danger nx-channel-error" role="alert">{channel.error}</div>
                </Show>
              </div>
            )}
          </For>
        )

      case 'streaming.webEnabled':
        return (
          <ToggleField
            label={t('webstream.enable')}
            checked={streamToggles.web.checked()}
            disabled={streamingDisabled()}
            onChange={streamToggles.web.onToggle}
          />
        )

      case 'streaming.webQuality':
        return (
          <SliderField
            label={t('webstream.jpegQuality')}
            display={`${streaming()?.jpegQuality ?? API_DEFAULTS.jpegQuality}%`}
            min={API_DEFAULTS.jpegQualityMin}
            max={API_DEFAULTS.jpegQualityMax}
            step={5}
            value={streaming()?.jpegQuality ?? API_DEFAULTS.jpegQuality}
            disabled={streamingDisabled() || !webEnabled()}
            onChange={(value) => {
              if (webEnabled()) {
                streamingWriteDebounced({ jpegQuality: Math.min(Math.max(value, API_DEFAULTS.jpegQualityMin), API_DEFAULTS.jpegQualityMax) })
              }
            }}
          />
        )

      case 'streaming.resolution':
        return (
          <Field label={t('webstream.resolution')} hint={t('webstream.resolutionHint')}>
            <Select
              disabled={streamingDisabled()}
              value={streaming()?.rtspResolution ?? API_DEFAULTS.rtspResolution}
              options={STREAM_RESOLUTIONS}
              ariaLabel={t('webstream.resolution')}
              onChange={(value) => streamingWrite({ rtspResolution: value as RtspResolution })}
            />
          </Field>
        )

      case 'streaming.adaptiveBitrate':
        return (
          <ToggleField
            label={t('webstream.adaptive')}
            hint={t('webstream.adaptiveDesc')}
            checked={streamToggles.adaptive.checked()}
            disabled={streamingDisabled()}
            onChange={streamToggles.adaptive.onToggle}
          />
        )

      case 'streaming.audioEnabled':
        return (
          <ToggleField
            label={t('audio.includeLive')}
            checked={streamToggles.audio.checked()}
            disabled={streamingDisabled() || !webEnabled()}
            onChange={() => {
              if (webEnabled()) streamToggles.audio.onToggle()
            }}
          />
        )

      case 'streaming.rtspEnabled':
        return (
          <ToggleField
            label={t('rtsp.enable')}
            checked={streamToggles.rtsp.checked()}
            disabled={streamingDisabled()}
            onChange={streamToggles.rtsp.onToggle}
          />
        )

      case 'streaming.whipEnabled':
        return (
          <ToggleField
            label={t('whip.enable')}
            checked={streamToggles.whip.checked()}
            disabled={streamingDisabled()}
            onChange={streamToggles.whip.onToggle}
          />
        )

      case 'streaming.rtmpEnabled':
        return (
          <ToggleField
            label={t('rtmp.enable')}
            checked={streamToggles.rtmp.checked()}
            disabled={streamingDisabled()}
            onChange={streamToggles.rtmp.onToggle}
          />
        )

      case 'streaming.keepAwake':
        return (
          <ToggleField
            label={t('keepawake.enable')}
            hint={t('keepawake.desc')}
            checked={streamToggles.keepAwake.checked()}
            disabled={streamingDisabled()}
            onChange={streamToggles.keepAwake.onToggle}
          />
        )

      case 'recording.start-stop': {
        const stopping = () => state.isRecording() || state.isScheduled()
        const label = () => state.isRecording()
          ? t('recording.stop')
          : state.isScheduled()
            ? t('recording.stopScheduled')
            : t('recording.start')
        return (
          <Field label={t('recording.title')}>
            <Button
              size="sm"
              variant={stopping() ? 'danger' : 'secondary'}
              disabled={locked()}
              ariaLabel={label()}
              onClick={toggleRecording}
            >
              {label()}
            </Button>
          </Field>
        )
      }

      case 'recording.audio':
        return (
          <ToggleField
            label={t('recording.audio')}
            checked={state.recordingConfig().includeAudio}
            disabled={locked() || state.isRecording()}
            onChange={(value) => recordingWrite({ ...state.recordingConfig(), includeAudio: value })}
          />
        )

      case 'recording.quality':
        return (
          <Field label={t('recording.quality')}>
            <Select
              disabled={locked() || state.isRecording()}
              value={state.recordingConfig().quality}
              options={recordingQualityOptions}
              ariaLabel={t('recording.quality')}
              onChange={(value) => recordingWrite({ ...state.recordingConfig(), quality: value as RecordingQuality })}
            />
          </Field>
        )

      case 'interval.start-stop':
        return (
          <Field
            label={t('interval.title')}
            hint={state.intervalRunning() ? t('interval.running', { count: state.intervalCompleted() }) : undefined}
          >
            <Button
              size="sm"
              variant={state.intervalRunning() ? 'danger' : 'secondary'}
              disabled={locked()}
              ariaLabel={state.intervalRunning() ? t('interval.stop') : t('interval.start')}
              onClick={toggleIntervalCapture}
            >
              {state.intervalRunning() ? t('interval.stop') : t('interval.start')}
            </Button>
          </Field>
        )

      case 'security.siren':
        return (
          <div class="nx-deterrence">
            <Button
              variant={sirenOn() ? 'warning' : 'secondary'}
              size="sm"
              disabled={locked() || sirenBusy()}
              ariaLabel={sirenOn() ? t('security.sirenOff') : t('security.sirenOn')}
              ariaPressed={sirenOn()}
              onClick={toggleSiren}
            >
              {sirenOn() ? t('security.sirenOff') : t('security.sirenOn')}
            </Button>
          </div>
        )

      case 'security.torch':
        return (
          <div class="nx-deterrence">
            <Button
              variant={torchOn() ? 'warning' : 'secondary'}
              size="sm"
              disabled={locked() || torchBusy()}
              ariaLabel={torchOn() ? t('security.lightOff') : t('security.lightOn')}
              ariaPressed={torchOn()}
              onClick={toggleTorch}
            >
              {torchOn() ? t('security.lightOff') : t('security.lightOn')}
            </Button>
          </div>
        )

      case 'security.testAlert':
        return (
          <div class="nx-security-actions">
            <Button
              size="sm"
              variant="secondary"
              disabled={locked() || testAlertBusy()}
              ariaLabel={t('security.sendTestAlert')}
              onClick={sendTestAlertNow}
            >
              {testAlertBusy() ? t('security.sending') : t('security.sendTestAlert')}
            </Button>
            <Show when={testAlertResult()}>
              <div class="nx-banner nx-banner-neutral" role="status" aria-live="polite">{testAlertResult()}</div>
            </Show>
          </div>
        )

      case 'capture.snapshot':
        return (
          <div class="nx-security-actions">
            <Button
              size="sm"
              variant="secondary"
              disabled={locked()}
              title={t('preview.captureTitle')}
              ariaLabel={t('preview.captureTitle')}
              onClick={captureSnapshot}
            >
              {t('preview.capture')}
            </Button>
            <Show when={state.captureMsg()}>
              <div class="nx-banner nx-banner-neutral" role="status" aria-live="polite">{state.captureMsg()}</div>
            </Show>
          </div>
        )

      case 'security.motion':
        return (
          <ToggleField
            label={t('security.motion')}
            checked={streamToggles.motion.checked()}
            disabled={streamingDisabled()}
            onChange={streamToggles.motion.onToggle}
          />
        )

      case 'security.motionSensitivity':
        return (
          <SliderField
            label={t('security.sensitivity')}
            display={`${streaming()?.motionSensitivityPercent ?? API_DEFAULTS.motionSensitivityPercent}%`}
            min={1}
            max={100}
            step={1}
            value={streaming()?.motionSensitivityPercent ?? API_DEFAULTS.motionSensitivityPercent}
            disabled={streamingDisabled() || !motionEnabled()}
            onChange={(value) => {
              if (motionEnabled()) streamingWriteDebounced({ motionSensitivityPercent: Math.min(Math.max(value, 1), 100) })
            }}
          />
        )

      case 'security.sound':
        return (
          <ToggleField
            label={t('security.sound')}
            checked={streamToggles.sound.checked()}
            disabled={streamingDisabled()}
            onChange={streamToggles.sound.onToggle}
          />
        )

      case 'security.ml':
        return (
          <ToggleField
            label={t('security.ml')}
            checked={streamToggles.ml.checked()}
            disabled={streamingDisabled()}
            onChange={streamToggles.ml.onToggle}
          />
        )

      case 'overlay.enabled':
        return (
          <ToggleField
            label={t('overlay.enable')}
            checked={streamToggles.overlay.checked()}
            disabled={streamingDisabled()}
            onChange={streamToggles.overlay.onToggle}
          />
        )

      case 'masking.enabled':
        return (
          <ToggleField
            label={t('privacy.enable')}
            checked={streamToggles.masking.checked()}
            disabled={streamingDisabled()}
            onChange={streamToggles.masking.onToggle}
          />
        )

      case 'masking.edit':
        return (
          <Field
            label={t('privacy.zones', { count: maskingZones().length })}
            hint={!maskingReady() || streamOn() ? undefined : t('privacy.streamOffHint')}
          >
            <Button
              size="sm"
              variant="primary"
              disabled={maskEditorDisabled()}
              title={streamOn() ? t('privacy.editZoneTitle') : t('privacy.streamOffHint')}
              ariaLabel={t('privacy.editZoneTitle')}
              onClick={openMaskEditor}
            >
              {t('privacy.editZones')}
            </Button>
          </Field>
        )

      default:
        return <></>
    }
  }

  return (
    <Panel>
      <Show when={locked()}>
        <div class="nx-banner nx-banner-neutral" role="status">{t('cam.adminHint')}</div>
      </Show>
      <For each={entries()}>
        {(entry) => (
          <SettingItem id={entry.id}>
            <div data-control-item={entry.id}>
              {renderControl(entry.id)}
            </div>
          </SettingItem>
        )}
      </For>
    </Panel>
  )
}
