// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import type { DeviceStatus } from '../types'
import ConnectionQualityIndicator from './ConnectionQualityIndicator'

// The panel's rows for the WHEP lane (issue #25): libwebrtc encodes the
// WebRTC preview per viewer, so the lane has no configured target — the rows
// must never invent one — and its codec/geometry exist only once a viewer
// session reported them. Mounted directly: jsdom cannot run the WHEP rung,
// but the rows depend on nothing but the rung's name and the status block.

afterEach(cleanup)

function openPanel(whepStream: NonNullable<DeviceStatus['whepStream']>) {
  const [status, setStatus] = createSignal<DeviceStatus | undefined>({ whepStream } as DeviceStatus)
  render(() => <ConnectionQualityIndicator status={status} previewMode={() => 'whep'} />)
  fireEvent.click(screen.getByText('24.0 fps · 1700 kbps'))
  return setStatus
}

/** The value cell beside a row label, or null when the panel has no such row. */
function rowValue(label: string): string | null {
  const cell = [...document.querySelectorAll('.cq-detail-panel span')].find((el) => el.textContent === label)
  return cell?.nextElementSibling?.textContent ?? null
}

describe('ConnectionQualityIndicator — the WHEP lane', () => {
  it('renders bitrate, fps, codec and resolution, and never a Target row', () => {
    openPanel({ measuredBitrateKbps: 1700, fps: 24, codec: 'vp8', width: 600, height: 800 })
    expect(rowValue('Bitrate')).toBe('1700 kbps')
    expect(rowValue('FPS')).toBe('24.0')
    expect(rowValue('Codec')).toBe('vp8')
    expect(rowValue('Resolution')).toBe('600×800')
    expect(rowValue('Target')).toBeNull()
  })

  it('shows codec and resolution only once the viewer session reported them', () => {
    openPanel({ measuredBitrateKbps: 1700, fps: 24 })
    expect(rowValue('Bitrate')).toBe('1700 kbps')
    expect(rowValue('FPS')).toBe('24.0')
    expect(rowValue('Codec')).toBeNull()
    expect(rowValue('Resolution')).toBeNull()
    expect(rowValue('Target')).toBeNull()
  })
})
