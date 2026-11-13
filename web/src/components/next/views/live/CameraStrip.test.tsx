// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render } from '@solidjs/testing-library'
import { createSignal, type Accessor } from 'solid-js'
import CameraStrip from './CameraStrip'
import type { NextState } from '../../NextState'
import type { AllSettings, CameraSettings, DeviceStatus, LensInfo } from '../../../../types'

// The next strip is the kit twin of the classic one: same shared behavior
// factory, nx markup. These tests pin what the kit composition must carry —
// the lens chip row, the torch toggle's aria-pressed, and the viewer lockout.
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

function mountState(overrides: { isViewer?: boolean } = {}) {
  const status = createSignal<DeviceStatus | null>({ ...BASE_STATUS })
  const lenses = createSignal<LensInfo[]>(TWO_LENSES)
  const settings = createSignal<AllSettings | null>(baseSettings())
  const isViewer = createSignal(overrides.isViewer ?? false)
  const cameraPatches: Partial<CameraSettings>[] = []
  const state = {
    status: status[0] as Accessor<DeviceStatus | null>,
    lenses: lenses[0],
    settings: settings[0],
    isViewer: isViewer[0],
    updateCamera: (patch: Partial<CameraSettings>) => cameraPatches.push(patch),
    handleSelectLens: async () => undefined,
  } as unknown as NextState
  render(() => <CameraStrip state={state} />)
  return { status, lenses, settings, isViewer, cameraPatches }
}

describe('nx camera strip', () => {
  it('renders the strip with the zoom slider, quick-selects, and the lens chip row', () => {
    mountState()
    expect(document.querySelector('.nx-cam-strip')).toBeTruthy()
    const slider = document.querySelector('.nx-cam-strip .nx-slider') as HTMLInputElement
    expect(slider).toBeTruthy()
    expect(slider.getAttribute('aria-label')).toBe('Remote zoom')
    const chips = [...document.querySelectorAll('.nx-cam-strip .nx-chip')]
    expect(chips.length).toBeGreaterThanOrEqual(3) // wide reset + two lenses
    // The selected lens chip carries the active class inside its group.
    const lensChips = [...document.querySelectorAll('.nx-cam-strip [aria-label="Lens"] .nx-chip')]
    expect(lensChips.length).toBe(2)
    expect(lensChips[0].classList.contains('nx-chip-active')).toBe(true)
    expect(lensChips[0].getAttribute('aria-pressed')).toBe('true')
    const selects = [...document.querySelectorAll('.nx-cam-strip .nx-select')] as HTMLSelectElement[]
    expect(selects.length).toBe(2)
    const torch = document.querySelector('.nx-cam-strip button[aria-label="Torch"]')!
    expect(torch.getAttribute('aria-pressed')).toBe('false')
  })

  it('mirrors the device torch into the toggled state', async () => {
    const { status } = mountState()
    status[1]({ ...BASE_STATUS, torchOn: true })
    await Promise.resolve()
    const torch = document.querySelector('.nx-cam-strip button[aria-label="Torch ON"]')!
    expect(torch.getAttribute('aria-pressed')).toBe('true')
  })

  it('locks every control for the viewer pair', () => {
    mountState({ isViewer: true })
    const slider = document.querySelector('.nx-cam-strip .nx-slider') as HTMLInputElement
    expect(slider.disabled).toBe(true)
    const lockedButtons = [...document.querySelectorAll('.nx-cam-strip button')]
      .filter((b) => (b as HTMLButtonElement).disabled && b.getAttribute('title') === 'Admin access required')
    expect(lockedButtons.length).toBeGreaterThanOrEqual(5)
  })

  it('collapses to the slim handle and back', () => {
    mountState()
    expect(document.querySelector('.nx-cam-strip')).toBeTruthy()
    const collapse = [...document.querySelectorAll('.nx-cam-strip button')]
      .find((b) => b.getAttribute('aria-expanded') === 'true') as HTMLButtonElement
    collapse.click()
    expect(document.querySelector('.nx-cam-strip')).toBeNull()
    expect(document.querySelector('.nx-cam-strip-handle')).toBeTruthy()
    ;(document.querySelector('.nx-cam-strip-handle') as HTMLButtonElement).click()
    expect(document.querySelector('.nx-cam-strip')).toBeTruthy()
  })
})
