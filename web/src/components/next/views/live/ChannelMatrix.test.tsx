// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import statusFixture from '../../../../../contract/status.json'
import type { DeviceStatus } from '../../../../types'
import type { NextState } from '../../NextState'
import ChannelMatrix from './ChannelMatrix'

afterEach(cleanup)

function statusWith(overrides: Partial<DeviceStatus['streaming']> = {}): DeviceStatus {
  const base = JSON.parse(JSON.stringify(statusFixture)) as DeviceStatus
  return {
    ...base,
    streaming: {
      ...base.streaming,
      ...overrides,
    },
  }
}

describe('ChannelMatrix', () => {
  it('keeps channel row DOM nodes mounted across status polls instead of remounting', () => {
    const [status, setStatus] = createSignal<DeviceStatus | null>(statusWith({
      webStreamingEnabled: true,
      webStreamingActive: false,
      rtspEnabled: true,
      rtspStreamingActive: false,
    }))
    const state = {
      status,
      streamActionLoading: () => false,
      isViewer: () => false,
      handleStartWebStream: vi.fn(),
      handleStopWebStream: vi.fn(),
      handleStartRtspStream: vi.fn(),
      handleStopRtspStream: vi.fn(),
      handleStartRtmp: vi.fn(),
      handleStopRtmp: vi.fn(),
      handleStartWhip: vi.fn(),
      handleStopWhip: vi.fn(),
    } as unknown as NextState

    const { container } = render(() => <ChannelMatrix state={state} />)
    const beforeRows = Array.from(container.querySelectorAll('.nx-channel'))
    expect(beforeRows).toHaveLength(4)

    // Subsequent status poll creates a brand-new DeviceStatus object and flips WEB active.
    setStatus(statusWith({
      webStreamingEnabled: true,
      webStreamingActive: true,
      rtspEnabled: true,
      rtspStreamingActive: false,
    }))

    const afterRows = Array.from(container.querySelectorAll('.nx-channel'))
    expect(afterRows).toHaveLength(4)
    expect(afterRows[0]).toBe(beforeRows[0])
    expect(afterRows[1]).toBe(beforeRows[1])
    expect(afterRows[2]).toBe(beforeRows[2])
    expect(afterRows[3]).toBe(beforeRows[3])
    expect(afterRows[0].textContent).toContain('ON AIR')
  })
})
