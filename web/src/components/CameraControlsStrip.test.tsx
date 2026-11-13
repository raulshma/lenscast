// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import CameraControlsStrip from './CameraControlsStrip'
import type { CameraControlsDeps } from '../hooks/useCameraControls'
import type { AllSettings, DeviceStatus, LensInfo } from '../types'

// The classic strip renders the shared useCameraControls behavior with its
// own markup: these tests pin the DOM contract the CSS and the shortcuts
// rely on — the slider id (the device-mirror drag guard), aria-pressed on
// the toggles, the viewer lockout, and the collapse persistence.
afterEach(() => cleanup())

const BASE_STATUS: DeviceStatus = {
  streaming: {
    isActive: true,
    url: '',
    webStreamingEnabled: true,
    webStreamingActive: true,
    clientCount: 0,
    audioEnabled: false,
    audioUrl: '',
    rtspEnabled: false,
    rtspStreamingActive: false,
    rtspUrl: '',
  },
  thermal: 'NORMAL',
  battery: { level: 80, isCharging: false, isPowerSaveMode: false },
  camera: 'active',
  torchOn: false,
  sirenActive: false,
  zoomRatio: 1,
}

function baseSettings(): AllSettings {
  return {
    camera: {
      exposureCompensation: 0, iso: null, exposureTime: null,
      focusMode: 'AUTO', focusDistance: null, whiteBalance: 'AUTO',
      colorTemperature: null, zoomRatio: 1.0, frameRate: 30,
      resolution: 'FHD_1080P', stabilization: true, hdrMode: 'OFF', sceneMode: null,
      nightVisionMode: 'OFF', outputRotation: 0, orientationLocked: false,
    },
    streaming: {} as AllSettings['streaming'],
  }
}

const TWO_LENSES: LensInfo[] = [
  { index: 0, id: 'wide', label: 'Wide', kind: 'WIDE', focalLength: 26, isFront: false, selected: true },
  { index: 1, id: 'tele', label: 'Tele', kind: 'ZOOM', focalLength: 78, zoomRatio: 3, isFront: false, selected: false },
]

function mount(overrides: Partial<CameraControlsDeps> = {}) {
  const status = createSignal<DeviceStatus | null>({ ...BASE_STATUS })
  const lenses = createSignal<LensInfo[]>(TWO_LENSES)
  const settings = createSignal<AllSettings | null>(baseSettings())
  const isViewer = createSignal(false)
  const cameraPatches: Record<string, unknown>[] = []
  render(() => (
    <CameraControlsStrip
      status={status[0]}
      lenses={lenses[0]}
      settings={settings[0]}
      isViewer={isViewer[0]}
      updateCamera={(patch) => cameraPatches.push(patch)}
      handleSelectLens={async () => undefined}
      {...overrides}
    />
  ))
  return { status, lenses, settings, isViewer, cameraPatches }
}

describe('zoom group', () => {
  it('renders the slider with its device-mirror id, the readout, and the wide reset', () => {
    mount()
    const slider = document.getElementById('remote-zoom-slider') as HTMLInputElement
    expect(slider).toBeTruthy()
    expect(slider.getAttribute('aria-label')).toBe('Remote zoom')
    expect(slider.min).toBe('1')
    expect(document.querySelector('.cam-strip-readout')!.textContent).toBe('1.0x')
    const wide = [...document.querySelectorAll('.cam-strip-chip')].find((c) => c.textContent === 'Wide')!
    expect(wide).toBeTruthy()
  })

  it('mirrors the device zoom and re-scales the readout', async () => {
    const { status } = mount()
    status[1]({ ...BASE_STATUS, zoomRatio: 2.5, zoomRange: { min: 1, max: 8 } })
    await Promise.resolve()
    const slider = document.getElementById('remote-zoom-slider') as HTMLInputElement
    expect(slider.max).toBe('8')
    expect(document.querySelector('.cam-strip-readout')!.textContent).toBe('2.5x')
  })
})

describe('torch toggle', () => {
  it('carries aria-pressed and the torch truth of the device', async () => {
    const { status } = mount()
    const torch = () => [...document.querySelectorAll('.cam-strip-btn')].find((b) => b.getAttribute('aria-pressed') !== null)!
    expect(torch().getAttribute('aria-pressed')).toBe('false')
    status[1]({ ...BASE_STATUS, torchOn: true })
    await Promise.resolve()
    expect(torch().getAttribute('aria-pressed')).toBe('true')
    expect(torch().getAttribute('aria-label')).toBe('Torch ON')
  })
})

describe('viewer lockout', () => {
  it('disables every control and advertises the admin hint', () => {
    mount({ isViewer: () => true })
    const slider = document.getElementById('remote-zoom-slider') as HTMLInputElement
    expect(slider.disabled).toBe(true)
    const disabledWithTitle = [...document.querySelectorAll('button')]
      .filter((b) => (b as HTMLButtonElement).disabled && b.getAttribute('title') === 'Admin access required')
    // Zoom steppers, wide reset, lens chips, EV steppers, torch — all locked.
    expect(disabledWithTitle.length).toBeGreaterThanOrEqual(6)
  })
})

describe('lens group', () => {
  it('renders one chip per lens with the selected one active', () => {
    mount()
    const chips = [...document.querySelectorAll('.cam-strip-lenses .cam-strip-chip')]
    expect(chips.length).toBe(2)
    expect(chips[0].classList.contains('cam-strip-chip-active')).toBe(true)
    expect(chips[0].getAttribute('aria-pressed')).toBe('true')
    expect(chips[1].getAttribute('aria-pressed')).toBe('false')
  })

  it('hides the group when the device enumerates a single lens', () => {
    const single: LensInfo[] = [TWO_LENSES[0]]
    const lenses = createSignal<LensInfo[]>(single)
    const status = createSignal<DeviceStatus | null>({ ...BASE_STATUS })
    const settings = createSignal<AllSettings | null>(baseSettings())
    render(() => (
      <CameraControlsStrip
        status={status[0]}
        lenses={lenses[0]}
        settings={settings[0]}
        isViewer={() => false}
        updateCamera={() => undefined}
        handleSelectLens={async () => undefined}
      />
    ))
    expect(document.querySelector('.cam-strip-lenses')).toBeNull()
  })
})

describe('exposure compensation', () => {
  it('steps the index through updateCamera and shows the sign', () => {
    const { cameraPatches, settings } = mount()
    settings[1]({ ...baseSettings(), camera: { ...baseSettings().camera, exposureCompensation: 2 } })
    const plus = [...document.querySelectorAll('.cam-strip-btn')]
      .find((b) => b.getAttribute('aria-label') === 'Raise exposure compensation')! as HTMLButtonElement
    plus.click()
    expect(cameraPatches).toEqual([{ exposureCompensation: 3 }])
    const readouts = [...document.querySelectorAll('.cam-strip-readout')]
    expect(readouts.some((r) => r.textContent === '+2' || r.textContent === '+3')).toBe(true)
  })
})

describe('quick-selects', () => {
  it('renders focus and WB selects from the settings mirror', () => {
    mount()
    const selects = [...document.querySelectorAll('.cam-strip-select')] as HTMLSelectElement[]
    expect(selects.length).toBe(2)
    expect(selects[0].getAttribute('aria-label')).toBe('Focus')
    expect(selects[0].value).toBe('AUTO')
    expect(selects[1].getAttribute('aria-label')).toBe('White Balance')
    expect(selects[1].value).toBe('AUTO')
    // MANUAL WB stays settings-only.
    const wbValues = [...selects[1].options].map((o) => o.value)
    expect(wbValues).not.toContain('MANUAL')
  })
})

describe('collapse', () => {
  it('collapses to the slim handle and expands back', () => {
    mount()
    expect(document.querySelector('.cam-strip')).toBeTruthy()
    const collapse = [...document.querySelectorAll('button')]
      .find((b) => b.getAttribute('aria-expanded') === 'true')!
    collapse.click()
    expect(document.querySelector('.cam-strip')).toBeNull()
    const handle = document.querySelector('.cam-strip-handle')!
    expect(handle).toBeTruthy()
    expect(handle.getAttribute('aria-expanded')).toBe('false')
    ;(handle as HTMLButtonElement).click()
    expect(document.querySelector('.cam-strip')).toBeTruthy()
  })
})
