// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, waitFor, within } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import settingsFixture from '../../../../../contract/settings.json'
import statusFixture from '../../../../../contract/status.json'
import type { NextState } from '../../NextState'
import type {
  AllSettings,
  CameraSettings,
  DeviceStatus,
  IntervalCaptureConfig,
  RecordingConfig,
} from '../../../../types'
import { sendTestAlert, setSiren, setTorch } from '../../../../api/client'
import { en } from '../../../../i18n/en'
import { setLocale } from '../../../../lib/i18n'
import { maskEditActive, resetMaskEditStoreForTests } from '../../../../lib/maskEditStore'
import { InspectorSectionContext } from '../../ui/SettingItem'
import { controlItem, customControlItems } from './controlItemCatalog'
import CustomSectionPanel from './CustomSectionPanel'

vi.mock('../../../../api/client', () => ({
  sendTestAlert: vi.fn(),
  setSiren: vi.fn(),
  setTorch: vi.fn(),
}))

const CUSTOM_IDS = [
  'exposure.compensation',
  'exposure.iso',
  'exposure.time',
  'focus.mode',
  'focus.distance',
  'whiteBalance.preset',
  'whiteBalance.temperature',
  'zoomFrame.zoom',
  'zoomFrame.frameRate',
  'zoomFrame.resolution',
  'zoomFrame.rotation',
  'zoomFrame.orientationLocked',
  'effects.stabilization',
  'effects.hdr',
  'nightVision.mode',
  'channels.status',
  'streaming.webEnabled',
  'streaming.webQuality',
  'streaming.resolution',
  'streaming.adaptiveBitrate',
  'streaming.audioEnabled',
  'streaming.rtspEnabled',
  'streaming.whipEnabled',
  'streaming.rtmpEnabled',
  'streaming.keepAwake',
  'recording.start-stop',
  'recording.audio',
  'recording.quality',
  'interval.start-stop',
  'security.siren',
  'security.torch',
  'security.testAlert',
  'capture.snapshot',
  'security.motion',
  'security.motionSensitivity',
  'security.sound',
  'security.ml',
  'overlay.enabled',
  'masking.enabled',
  'masking.edit',
] as const

function cloneSettings(): AllSettings {
  return structuredClone(settingsFixture) as unknown as AllSettings
}

function cloneStatus(): DeviceStatus {
  return structuredClone(statusFixture) as unknown as DeviceStatus
}

interface MountOptions {
  settings?: AllSettings | null
  status?: DeviceStatus | null
  viewer?: boolean
  readOnly?: boolean
  recording?: Partial<RecordingConfig>
  recordingActive?: boolean
  scheduled?: boolean
  intervalRunning?: boolean
  intervalCompleted?: number
  captureMsg?: string
}

function mountPanel(itemIds: readonly string[], options: MountOptions = {}) {
  const [settings, setSettings] = createSignal<AllSettings | null>(
    options.settings === undefined ? cloneSettings() : options.settings,
  )
  const [status, setStatus] = createSignal<DeviceStatus | null>(
    options.status === undefined ? cloneStatus() : options.status,
  )
  const [viewer, setViewer] = createSignal(options.viewer ?? false)
  const [recordingConfig, setRecordingConfigSignal] = createSignal<RecordingConfig>({
    durationSeconds: 0,
    repeatIntervalSeconds: 0,
    quality: 'HIGH',
    includeAudio: true,
    startTimeMs: 1_800_000_000_000,
    ...options.recording,
  })
  const [intervalConfig] = createSignal<IntervalCaptureConfig>({
    intervalSeconds: 5,
    totalCaptures: 20,
    flashMode: 'OFF',
  })
  const [recording, setRecording] = createSignal(options.recordingActive ?? false)
  const [scheduled, setScheduled] = createSignal(options.scheduled ?? false)
  const [intervalRunning, setIntervalRunning] = createSignal(options.intervalRunning ?? false)
  const [intervalCompleted, setIntervalCompleted] = createSignal(options.intervalCompleted ?? 0)
  const [captureMsg] = createSignal(options.captureMsg ?? '')

  const spies = {
    updateCamera: vi.fn((patch: Partial<CameraSettings>) => {
      setSettings((current) => current
        ? { ...current, camera: { ...current.camera, ...patch } }
        : current)
    }),
    updateStreamingAndSave: vi.fn((patch: Partial<AllSettings['streaming']>) => {
      setSettings((current) => current
        ? { ...current, streaming: { ...current.streaming, ...patch } }
        : current)
    }),
    updateStreamingDebounced: vi.fn((patch: Partial<AllSettings['streaming']>) => {
      setSettings((current) => current
        ? { ...current, streaming: { ...current.streaming, ...patch } }
        : current)
    }),
    setRecordingConfig: vi.fn((value: RecordingConfig) => setRecordingConfigSignal(value)),
    setIntervalConfig: vi.fn((_value: IntervalCaptureConfig) => {}),
    handleCapture: vi.fn(async () => {}),
    handleStartRecording: vi.fn(),
    handleStopRecording: vi.fn(),
    handleStartIntervalCapture: vi.fn(),
    handleStopIntervalCapture: vi.fn(),
  }

  const state = {
    settings,
    status,
    isViewer: viewer,
    recordingConfig,
    intervalConfig,
    isRecording: recording,
    isScheduled: scheduled,
    intervalRunning,
    intervalCompleted,
    captureMsg,
    ...spies,
  } as unknown as NextState

  const view = render(() => (
    <CustomSectionPanel state={state} itemIds={itemIds} readOnly={options.readOnly} />
  ))

  const row = (id: string): HTMLElement => {
    const found = view.container.querySelector<HTMLElement>(`[data-control-item="${id}"]`)
    expect(found, `row ${id}`).not.toBeNull()
    return found!
  }
  const inRow = (id: string) => within(row(id))
  const rows = () => [...view.container.querySelectorAll<HTMLElement>('[data-control-item]')]

  return {
    ...spies,
    view,
    row,
    inRow,
    rows,
    setSettings,
    setStatus,
    setViewer,
    setRecording,
    setScheduled,
    setIntervalRunning,
    setIntervalCompleted,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  setLocale('en')
  resetMaskEditStoreForTests()
  vi.clearAllMocks()
  vi.mocked(setSiren).mockResolvedValue({ success: true })
  vi.mocked(setTorch).mockResolvedValue({ success: true })
  vi.mocked(sendTestAlert).mockResolvedValue({ success: true, dispatchedActions: [] })
})

afterEach(() => {
  cleanup()
  resetMaskEditStoreForTests()
})

describe('CustomSectionPanel catalog and ordering', () => {
  it('covers exactly the forty custom catalog IDs and renders every one in the supplied order', () => {
    expect([...new Set(customControlItems().map((item) => item.id))].sort()).toEqual([...CUSTOM_IDS].sort())
    const requested = [
      'masking.edit',
      'exposure.time',
      ...CUSTOM_IDS.filter((id) => id !== 'masking.edit' && id !== 'exposure.time'),
    ]
    const panel = mountPanel(requested)

    expect(panel.rows().map((node) => node.dataset.controlItem)).toEqual(requested)
    expect(panel.view.container.querySelectorAll('.nx-panel')).toHaveLength(1)
    expect(panel.view.container.querySelector('.nx-panel .nx-panel')).toBeNull()
  })

  it('ignores unknown, non-custom, and prototype-looking IDs without disturbing valid order', () => {
    const panel = mountPanel([
      'not-a-control',
      'lens.select',
      'streaming.rtspEnabled',
      '__proto__',
      'constructor',
      'exposure.compensation',
      'recording.duration',
    ])

    expect(panel.rows().map((node) => node.dataset.controlItem)).toEqual([
      'streaming.rtspEnabled',
      'exposure.compensation',
    ])
    expect(controlItem('lens.select')?.custom).toBe(false)
  })

  it('renders no item rows for an empty request and preserves repeated valid IDs', () => {
    const empty = mountPanel([])
    expect(empty.rows()).toHaveLength(0)
    cleanup()

    const repeated = mountPanel(['effects.hdr', 'exposure.compensation', 'effects.hdr'])
    expect(repeated.rows().map((node) => node.dataset.controlItem)).toEqual([
      'effects.hdr',
      'exposure.compensation',
      'effects.hdr',
    ])
  })

  it('uses the inspector frame title/actions and honors per-item hiding without nesting panels', () => {
    const state = {
      settings: () => cloneSettings(),
      status: () => cloneStatus(),
      isViewer: () => false,
      updateCamera: vi.fn(),
      updateStreamingAndSave: vi.fn(),
      updateStreamingDebounced: vi.fn(),
    } as unknown as NextState
    const view = render(() => (
      <InspectorSectionContext.Provider
        value={{
          hiddenItems: () => ['exposure.iso'],
          readOnly: () => false,
          title: () => 'My pinned controls',
          headerActions: <button type="button">Section action</button>,
        }}
      >
        <CustomSectionPanel state={state} itemIds={['exposure.iso', 'exposure.compensation']} />
      </InspectorSectionContext.Provider>
    ))

    expect(within(view.container).getByText('My pinned controls')).toBeTruthy()
    expect(within(view.container).getByRole('button', { name: 'Section action' })).toBeTruthy()
    expect(view.container.querySelector('[data-control-item="exposure.iso"]')).toBeNull()
    expect(view.container.querySelector('[data-control-item="exposure.compensation"]')).not.toBeNull()
    expect(view.container.querySelectorAll('.nx-panel')).toHaveLength(1)
  })
})

describe('CustomSectionPanel camera bindings', () => {
  it('uses live EV, ISO, and zoom bounds and explicitly clamps writes through updateCamera', () => {
    const settings = cloneSettings()
    settings.camera.exposureCompensation = 99
    settings.camera.iso = 9_999
    settings.camera.zoomRatio = 99
    const status = cloneStatus()
    status.exposureCompensationRange = { min: -2, max: 4 }
    status.isoRange = { min: 200, max: 1_600 }
    status.zoomRange = { min: 1, max: 6 }
    const panel = mountPanel(['exposure.compensation', 'exposure.iso', 'zoomFrame.zoom'], { settings, status })

    const ev = panel.inRow('exposure.compensation').getByRole('slider') as HTMLInputElement
    const iso = panel.row('exposure.iso').querySelector<HTMLInputElement>('input[type="number"]')!
    const zoom = panel.inRow('zoomFrame.zoom').getByRole('slider') as HTMLInputElement
    expect([ev.min, ev.max, ev.value]).toEqual(['-2', '4', '4'])
    expect([iso.min, iso.max, iso.value]).toEqual(['200', '1600', '1600'])
    expect([zoom.min, zoom.max, zoom.value]).toEqual(['1', '6', '6'])

    fireEvent.input(ev, { target: { value: '99' } })
    fireEvent.change(iso, { target: { value: '9999' } })
    fireEvent.input(zoom, { target: { value: '99' } })
    expect(panel.updateCamera.mock.calls.map(([patch]) => patch)).toEqual([
      { exposureCompensation: 4 },
      { iso: 1_600 },
      { zoomRatio: 6 },
    ])

    const isoMode = panel.row('exposure.iso').querySelector('select')!
    fireEvent.change(isoMode, { target: { value: 'auto' } })
    expect(panel.updateCamera).toHaveBeenLastCalledWith({ iso: null })
    fireEvent.change(isoMode, { target: { value: 'manual' } })
    expect(panel.updateCamera).toHaveBeenLastCalledWith({ iso: 800 })
  })

  it('converts exposure milliseconds to nanoseconds, clamps to 0.1–500 ms, and supports auto', () => {
    const panel = mountPanel(['exposure.time'])
    const input = panel.inRow('exposure.time').getByRole('spinbutton') as HTMLInputElement
    const mode = panel.row('exposure.time').querySelector('select')!
    expect(input.value).toBe('10')

    fireEvent.change(input, { target: { value: '999' } })
    expect(panel.updateCamera).toHaveBeenLastCalledWith({ exposureTime: 500_000_000 })
    fireEvent.change(input, { target: { value: '0' } })
    expect(panel.updateCamera).toHaveBeenLastCalledWith({ exposureTime: 100_000 })
    fireEvent.change(input, { target: { value: '12.5' } })
    expect(panel.updateCamera).toHaveBeenLastCalledWith({ exposureTime: 12_500_000 })

    fireEvent.change(mode, { target: { value: 'auto' } })
    expect(panel.updateCamera).toHaveBeenLastCalledWith({ exposureTime: null })
    expect(panel.row('exposure.time').querySelector('input[type="number"]')).toBeNull()
  })

  it('enables focus distance and white-balance temperature only in their manual modes', () => {
    const panel = mountPanel(['focus.mode', 'focus.distance', 'whiteBalance.preset', 'whiteBalance.temperature'])
    const focusMode = panel.inRow('focus.mode').getByRole('combobox') as HTMLSelectElement
    const distance = panel.inRow('focus.distance').getByRole('slider') as HTMLInputElement
    const wbMode = panel.inRow('whiteBalance.preset').getByRole('combobox') as HTMLSelectElement
    const temperature = panel.inRow('whiteBalance.temperature').getByRole('slider') as HTMLInputElement
    expect(distance.disabled).toBe(true)
    expect(temperature.disabled).toBe(true)

    fireEvent.change(focusMode, { target: { value: 'MANUAL' } })
    expect(panel.updateCamera).toHaveBeenLastCalledWith({ focusMode: 'MANUAL' })
    expect(distance.disabled).toBe(false)
    fireEvent.input(distance, { target: { value: '4.2' } })
    expect(panel.updateCamera).toHaveBeenLastCalledWith({ focusDistance: 4.2 })

    fireEvent.change(wbMode, { target: { value: 'MANUAL' } })
    expect(panel.updateCamera).toHaveBeenLastCalledWith({ whiteBalance: 'MANUAL' })
    expect(temperature.disabled).toBe(false)
    fireEvent.input(temperature, { target: { value: '6500' } })
    expect(panel.updateCamera).toHaveBeenLastCalledWith({ colorTemperature: 6_500 })
  })

  it('binds frame rate, resolution, rotation, orientation lock, stabilization, HDR, and night vision', () => {
    const panel = mountPanel([
      'zoomFrame.frameRate',
      'zoomFrame.resolution',
      'zoomFrame.rotation',
      'zoomFrame.orientationLocked',
      'effects.stabilization',
      'effects.hdr',
      'nightVision.mode',
    ])

    fireEvent.change(panel.inRow('zoomFrame.frameRate').getByRole('combobox'), { target: { value: '60' } })
    fireEvent.change(panel.inRow('zoomFrame.resolution').getByRole('combobox'), { target: { value: 'HD_720P' } })
    fireEvent.change(panel.inRow('zoomFrame.rotation').getByRole('combobox'), { target: { value: '90' } })
    fireEvent.click(panel.inRow('zoomFrame.orientationLocked').getByRole('checkbox'))
    fireEvent.click(panel.inRow('effects.stabilization').getByRole('checkbox'))
    fireEvent.change(panel.inRow('effects.hdr').getByRole('combobox'), { target: { value: 'AUTO' } })
    fireEvent.change(panel.inRow('nightVision.mode').getByRole('combobox'), { target: { value: 'ON' } })

    expect(panel.updateCamera.mock.calls.map(([patch]) => patch)).toEqual([
      { frameRate: 60 },
      { resolution: 'HD_720P' },
      { outputRotation: 90 },
      { orientationLocked: true },
      { stabilization: false },
      { hdrMode: 'AUTO' },
      { nightVisionMode: 'ON' },
    ])
  })
})

describe('CustomSectionPanel streaming and detection bindings', () => {
  it('uses immediate saves for toggles/resolution and the existing debouncer for JPEG quality', () => {
    const panel = mountPanel([
      'streaming.webEnabled',
      'streaming.webQuality',
      'streaming.audioEnabled',
      'streaming.resolution',
    ])
    const web = panel.inRow('streaming.webEnabled').getByRole('checkbox') as HTMLInputElement
    const quality = panel.inRow('streaming.webQuality').getByRole('slider') as HTMLInputElement
    const audio = panel.inRow('streaming.audioEnabled').getByRole('checkbox') as HTMLInputElement
    const resolution = panel.inRow('streaming.resolution').getByRole('combobox') as HTMLSelectElement

    fireEvent.click(web)
    expect(panel.updateStreamingAndSave).toHaveBeenCalledWith({ webStreamingEnabled: false })
    expect(quality.disabled).toBe(true)
    expect(audio.disabled).toBe(true)
    expect(resolution.disabled).toBe(false)
    fireEvent.input(quality, { target: { value: '90' } })
    expect(panel.updateStreamingDebounced).not.toHaveBeenCalled()

    fireEvent.change(resolution, { target: { value: '1080p43' } })
    expect(panel.updateStreamingAndSave).toHaveBeenCalledWith({ rtspResolution: '1080p43' })
    expect([...resolution.options].map((option) => option.value)).toEqual([
      '480p', '720p', '720p43', '1080p', '1080p43',
    ])

    fireEvent.click(web)
    fireEvent.input(quality, { target: { value: '90' } })
    expect(panel.updateStreamingDebounced).toHaveBeenCalledWith({ jpegQuality: 90 })
    fireEvent.click(audio)
    expect(panel.updateStreamingAndSave).toHaveBeenCalledWith({ streamAudioEnabled: false })
  })

  it('maps every remaining streaming/detection boolean to the correct immediate or debounced writer', () => {
    const panel = mountPanel([
      'streaming.adaptiveBitrate',
      'streaming.rtspEnabled',
      'streaming.whipEnabled',
      'streaming.rtmpEnabled',
      'streaming.keepAwake',
      'security.motion',
      'security.sound',
      'security.ml',
      'overlay.enabled',
      'masking.enabled',
    ])
    const immediate: Array<[string, Partial<AllSettings['streaming']>]> = [
      ['streaming.adaptiveBitrate', { adaptiveBitrateEnabled: true }],
      ['streaming.rtspEnabled', { rtspEnabled: true }],
      ['streaming.whipEnabled', { whipEnabled: true }],
      ['streaming.rtmpEnabled', { rtmpEnabled: true }],
      ['streaming.keepAwake', { keepAwakeEnabled: false }],
      ['security.motion', { motionDetectionEnabled: true }],
      ['security.sound', { soundDetectionEnabled: true }],
      ['security.ml', { mlDetectionEnabled: true }],
    ]
    for (const [id] of immediate) fireEvent.click(panel.inRow(id).getByRole('checkbox'))
    expect(panel.updateStreamingAndSave.mock.calls.map(([patch]) => patch)).toEqual(immediate.map(([, patch]) => patch))

    fireEvent.click(panel.inRow('overlay.enabled').getByRole('checkbox'))
    fireEvent.click(panel.inRow('masking.enabled').getByRole('checkbox'))
    expect(panel.updateStreamingDebounced.mock.calls.map(([patch]) => patch)).toEqual([
      { overlayEnabled: true },
      { maskingEnabled: true },
    ])
  })

  it('debounces motion sensitivity through the shared writer and clamps it to 1–100', () => {
    const settings = cloneSettings()
    settings.streaming.motionDetectionEnabled = true
    const panel = mountPanel(['security.motionSensitivity'], { settings })
    const slider = panel.inRow('security.motionSensitivity').getByRole('slider') as HTMLInputElement
    expect(slider.disabled).toBe(false)
    fireEvent.input(slider, { target: { value: '100' } })
    expect(panel.updateStreamingDebounced).toHaveBeenCalledWith({ motionSensitivityPercent: 100 })
    expect(panel.updateStreamingAndSave).not.toHaveBeenCalled()
  })
})

describe('CustomSectionPanel recording, interval, and snapshot actions', () => {
  it('starts immediately by clearing a stale schedule, binds recording audio/quality, and stops recording or scheduled state', () => {
    const panel = mountPanel(['recording.start-stop', 'recording.audio', 'recording.quality'])
    const start = panel.inRow('recording.start-stop').getByRole('button', { name: en['recording.start'] })
    fireEvent.click(start)
    expect(panel.setRecordingConfig).toHaveBeenCalledWith(expect.objectContaining({ startTimeMs: null }))
    expect(panel.handleStartRecording).toHaveBeenCalledTimes(1)

    fireEvent.click(panel.inRow('recording.audio').getByRole('checkbox'))
    expect(panel.setRecordingConfig).toHaveBeenLastCalledWith(expect.objectContaining({ includeAudio: false }))
    fireEvent.change(panel.inRow('recording.quality').getByRole('combobox'), { target: { value: 'LOW' } })
    expect(panel.setRecordingConfig).toHaveBeenLastCalledWith(expect.objectContaining({ quality: 'LOW' }))

    panel.setRecording(true)
    const stop = panel.inRow('recording.start-stop').getByRole('button', { name: en['recording.stop'] })
    expect((panel.inRow('recording.audio').getByRole('checkbox') as HTMLInputElement).disabled).toBe(true)
    expect((panel.inRow('recording.quality').getByRole('combobox') as HTMLSelectElement).disabled).toBe(true)
    fireEvent.click(stop)
    expect(panel.handleStopRecording).toHaveBeenCalledTimes(1)

    panel.setRecording(false)
    panel.setScheduled(true)
    const stopScheduled = panel.inRow('recording.start-stop').getByRole('button', { name: en['recording.stopScheduled'] })
    fireEvent.click(stopScheduled)
    expect(panel.handleStopRecording).toHaveBeenCalledTimes(2)
  })

  it('uses the shared interval start/stop handlers and shows live completion status', () => {
    const panel = mountPanel(['interval.start-stop'], { intervalCompleted: 3 })
    fireEvent.click(panel.inRow('interval.start-stop').getByRole('button', { name: en['interval.start'] }))
    expect(panel.handleStartIntervalCapture).toHaveBeenCalledTimes(1)

    panel.setIntervalRunning(true)
    panel.setIntervalCompleted(4)
    expect(panel.row('interval.start-stop').textContent).toContain(en['interval.running'].replace('{count}', '4'))
    fireEvent.click(panel.inRow('interval.start-stop').getByRole('button', { name: en['interval.stop'] }))
    expect(panel.handleStopIntervalCapture).toHaveBeenCalledTimes(1)
  })

  it('captures through NextState and displays the shared capture message with a real accessible action', () => {
    const panel = mountPanel(['capture.snapshot'], { captureMsg: 'Photo saved' })
    const button = panel.inRow('capture.snapshot').getByRole('button', { name: en['preview.captureTitle'] })
    expect(button.textContent).toBe(en['preview.capture'])
    expect(panel.row('capture.snapshot').textContent).toContain('Photo saved')
    fireEvent.click(button)
    expect(panel.handleCapture).toHaveBeenCalledTimes(1)
  })
})

describe('CustomSectionPanel status-only and device actions', () => {
  it('keeps all four channel rows visible and accurate for viewers without any start/stop controls', () => {
    const panel = mountPanel(['channels.status'], { viewer: true })
    expect(panel.rows()).toHaveLength(1)
    expect(panel.row('channels.status').querySelectorAll('.nx-channel')).toHaveLength(4)
    expect(panel.row('channels.status').querySelectorAll('button')).toHaveLength(0)
    expect([...panel.row('channels.status').querySelectorAll('.nx-channel-code')].map((node) => node.textContent)).toEqual([
      'WEB', 'RTSP', 'RTMP', 'WHIP',
    ])
    expect([...panel.row('channels.status').querySelectorAll('.nx-channel-status')].map((node) => node.textContent)).toEqual([
      'ON AIR', 'ON AIR', 'OFF', 'OFF',
    ])

    const status = cloneStatus()
    status.cameraBlockedByLock = true
    status.streaming.rtmpStatus = 'error'
    status.streaming.rtmpError = 'Encoder refused'
    status.streaming.whipStatus = 'connecting'
    panel.setStatus(status)
    expect([...panel.row('channels.status').querySelectorAll('.nx-channel-status')].map((node) => node.textContent)).toEqual([
      'LOCKED', 'LOCKED', 'FAULT', 'SYNC',
    ])
    expect(panel.row('channels.status').textContent).toContain('Encoder refused')
  })

  it('mirrors siren/torch device truth, reuses the existing client actions, and guards in-flight repeats', async () => {
    const pending = deferred<{ success: boolean }>()
    vi.mocked(setSiren).mockReturnValueOnce(pending.promise)
    const panel = mountPanel(['security.siren', 'security.torch'])
    const siren = panel.inRow('security.siren').getByRole('button', { name: en['security.sirenOn'] }) as HTMLButtonElement
    const torch = panel.inRow('security.torch').getByRole('button', { name: en['security.lightOn'] }) as HTMLButtonElement

    fireEvent.click(siren)
    expect(setSiren).toHaveBeenCalledWith(true)
    expect(siren.disabled).toBe(true)
    fireEvent.click(siren)
    expect(setSiren).toHaveBeenCalledTimes(1)
    pending.resolve({ success: true })
    await waitFor(() => expect(siren.disabled).toBe(false))
    expect(siren.getAttribute('aria-pressed')).toBe('true')
    expect(panel.inRow('security.siren').getByRole('button', { name: en['security.sirenOff'] })).toBe(siren)

    fireEvent.click(torch)
    expect(setTorch).toHaveBeenCalledWith(true)
    await waitFor(() => expect(torch.getAttribute('aria-pressed')).toBe('true'))
    await waitFor(() => expect(torch.disabled).toBe(false))

    const status = cloneStatus()
    status.sirenActive = true
    status.torchOn = true
    panel.setStatus(status)
    fireEvent.click(siren)
    fireEvent.click(torch)
    expect(setSiren).toHaveBeenLastCalledWith(false)
    expect(setTorch).toHaveBeenLastCalledWith(false)
  })

  it.each([
    { name: 'named channels', response: { success: true, dispatchedActions: ['webhook', 'MQTT'] }, message: en['security.testAlertVia'].replace('{channels}', 'webhook, MQTT') },
    { name: 'no channels', response: { success: true, dispatchedActions: [] }, message: en['security.testAlertNoChannel'] },
    { name: 'unavailable', response: { success: false, dispatchedActions: [] }, message: en['security.testAlertUnavailable'] },
  ])('reports the test-alert $name result from the existing endpoint', async ({ response, message }) => {
    vi.mocked(sendTestAlert).mockResolvedValueOnce(response)
    const panel = mountPanel(['security.testAlert'])
    fireEvent.click(panel.inRow('security.testAlert').getByRole('button', { name: en['security.sendTestAlert'] }))
    expect(sendTestAlert).toHaveBeenCalledTimes(1)
    expect(await within(panel.row('security.testAlert')).findByText(message)).toBeTruthy()
  })

  it('reports a rejected test alert and releases the busy guard', async () => {
    vi.mocked(sendTestAlert).mockRejectedValueOnce(new Error('offline'))
    const panel = mountPanel(['security.testAlert'])
    const button = panel.inRow('security.testAlert').getByRole('button', { name: en['security.sendTestAlert'] }) as HTMLButtonElement
    fireEvent.click(button)
    expect(button.disabled).toBe(true)
    expect(await within(panel.row('security.testAlert')).findByText(en['security.testAlertFailed'])).toBeTruthy()
    await waitFor(() => expect(button.disabled).toBe(false))
  })
})

describe('CustomSectionPanel masking editor', () => {
  it('arms the real mask-edit store only with hydrated settings and a live web stream', () => {
    const settings = cloneSettings()
    settings.streaming.maskingZones = [
      { id: 'a', label: '', enabled: true, type: 'BLACKOUT', x: 0.1, y: 0.1, width: 0.2, height: 0.2, pixelateSize: 16, blurRadius: 10 },
      { id: 'b', label: '', enabled: true, type: 'BLUR', x: 0.4, y: 0.4, width: 0.2, height: 0.2, pixelateSize: 16, blurRadius: 10 },
    ]
    const panel = mountPanel(['masking.edit'], { settings })
    const button = panel.inRow('masking.edit').getByRole('button', { name: en['privacy.editZoneTitle'] }) as HTMLButtonElement
    expect(button.disabled).toBe(false)
    expect(panel.row('masking.edit').textContent).toContain(en['privacy.zones'].replace('{count}', '2'))

    fireEvent.click(button)
    expect(maskEditActive()).toBe(true)
    expect(button.disabled).toBe(true)
    resetMaskEditStoreForTests()
    expect(button.disabled).toBe(false)

    const status = cloneStatus()
    status.streaming.webStreamingActive = false
    panel.setStatus(status)
    expect(button.disabled).toBe(true)
    expect(button.getAttribute('data-tooltip')).toBe(en['privacy.streamOffHint'])
    expect(panel.row('masking.edit').textContent).toContain(en['privacy.streamOffHint'])
    fireEvent.click(button)
    expect(maskEditActive()).toBe(false)

    panel.setSettings(null)
    expect(button.disabled).toBe(true)
    fireEvent.click(button)
    expect(maskEditActive()).toBe(false)
  })
})

describe('CustomSectionPanel read-only and hydration guards', () => {
  it('disables every mutating control for readOnly and guards even synthetic events', () => {
    const panel = mountPanel(CUSTOM_IDS, { readOnly: true })
    const controls = [...panel.view.container.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLButtonElement>('.nx-panel-body [data-control-item] input, .nx-panel-body [data-control-item] select, .nx-panel-body [data-control-item] button')]
    expect(controls.length).toBeGreaterThan(30)
    for (const control of controls) expect(control.disabled, control.outerHTML).toBe(true)

    for (const control of controls) {
      if (control instanceof HTMLButtonElement) fireEvent.click(control)
      else if (control instanceof HTMLSelectElement) {
        const option = control.options[1] ?? control.options[0]
        if (option) fireEvent.change(control, { target: { value: option.value } })
      } else if (control.type === 'checkbox') fireEvent.click(control)
      else if (control.type === 'range') fireEvent.input(control, { target: { value: control.max } })
      else fireEvent.change(control, { target: { value: control.max } })
    }

    expect(panel.updateCamera).not.toHaveBeenCalled()
    expect(panel.updateStreamingAndSave).not.toHaveBeenCalled()
    expect(panel.updateStreamingDebounced).not.toHaveBeenCalled()
    expect(panel.setRecordingConfig).not.toHaveBeenCalled()
    expect(panel.setIntervalConfig).not.toHaveBeenCalled()
    expect(panel.handleCapture).not.toHaveBeenCalled()
    expect(panel.handleStartRecording).not.toHaveBeenCalled()
    expect(panel.handleStopRecording).not.toHaveBeenCalled()
    expect(panel.handleStartIntervalCapture).not.toHaveBeenCalled()
    expect(panel.handleStopIntervalCapture).not.toHaveBeenCalled()
    expect(setSiren).not.toHaveBeenCalled()
    expect(setTorch).not.toHaveBeenCalled()
    expect(sendTestAlert).not.toHaveBeenCalled()
    expect(maskEditActive()).toBe(false)
    expect(panel.row('channels.status').textContent).toContain('WEB')
    expect(panel.view.container.textContent).toContain(en['cam.adminHint'])
  })

  it('reacts to a viewer role change after mount and keeps channel status readable', () => {
    const panel = mountPanel(['exposure.compensation', 'streaming.webEnabled', 'capture.snapshot', 'channels.status'])
    panel.setViewer(true)
    expect((panel.inRow('exposure.compensation').getByRole('slider') as HTMLInputElement).disabled).toBe(true)
    expect((panel.inRow('streaming.webEnabled').getByRole('checkbox') as HTMLInputElement).disabled).toBe(true)
    expect((panel.inRow('capture.snapshot').getByRole('button') as HTMLButtonElement).disabled).toBe(true)

    fireEvent.input(panel.inRow('exposure.compensation').getByRole('slider'), { target: { value: '5' } })
    fireEvent.click(panel.inRow('streaming.webEnabled').getByRole('checkbox'))
    fireEvent.click(panel.inRow('capture.snapshot').getByRole('button'))
    expect(panel.updateCamera).not.toHaveBeenCalled()
    expect(panel.updateStreamingAndSave).not.toHaveBeenCalled()
    expect(panel.handleCapture).not.toHaveBeenCalled()
    expect(panel.row('channels.status').textContent).toContain('ON AIR')
  })

  it('stands down settings controls until settings exist and never writes through the default-first gap', () => {
    const panel = mountPanel(['exposure.compensation', 'streaming.webEnabled', 'overlay.enabled'], { settings: null })
    const ev = panel.inRow('exposure.compensation').getByRole('slider') as HTMLInputElement
    const web = panel.inRow('streaming.webEnabled').getByRole('checkbox') as HTMLInputElement
    const overlay = panel.inRow('overlay.enabled').getByRole('checkbox') as HTMLInputElement
    expect(ev.disabled).toBe(true)
    expect(web.disabled).toBe(true)
    expect(overlay.disabled).toBe(true)

    fireEvent.input(ev, { target: { value: '5' } })
    fireEvent.click(web)
    fireEvent.click(overlay)
    expect(panel.updateCamera).not.toHaveBeenCalled()
    expect(panel.updateStreamingAndSave).not.toHaveBeenCalled()
    expect(panel.updateStreamingDebounced).not.toHaveBeenCalled()
  })
})
