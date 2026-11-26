// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@solidjs/testing-library'
import { WebStreamCard, RtspCard } from './StreamingCards'
import type { AllSettings } from '../../../../types'

afterEach(() => cleanup())

function makeSettings(overrides: Partial<AllSettings['streaming']> = {}): AllSettings {
  return {
    camera: {} as AllSettings['camera'],
    streaming: {
      webStreamingEnabled: true,
      rtspEnabled: false,
      rtspResolution: '720p',
      ...overrides,
    } as AllSettings['streaming'],
  }
}

describe('StreamingCards aspect ratio / resolution controls', () => {
  it('exposes the 4:3 / 16:9 stream resolution selector in WebStreamCard even when RTSP is disabled', () => {
    const updateStreamingAndSave = vi.fn()
    const settings = () => makeSettings({ rtspEnabled: false, webStreamingEnabled: true, rtspResolution: '720p' })

    render(() => (
      <>
        <WebStreamCard
          settings={settings}
          updateStreamingAndSave={updateStreamingAndSave}
          updateStreamingDebounced={vi.fn()}
          setRecordingConfigAudio={vi.fn()}
        />
        <RtspCard
          settings={settings}
          updateStreamingAndSave={updateStreamingAndSave}
          updateStreamingDebounced={vi.fn()}
          setRecordingConfigAudio={vi.fn()}
        />
      </>
    ))

    const select = screen.getByLabelText('Stream Resolution & Aspect Ratio') as HTMLSelectElement
    expect(select.disabled).toBe(false)
    expect(select.value).toBe('720p')
    expect([...select.options].map((o) => o.value)).toEqual(['480p', '720p', '720p43', '1080p', '1080p43'])

    fireEvent.change(select, { target: { value: '720p43' } })
    expect(updateStreamingAndSave).toHaveBeenCalledWith({ rtspResolution: '720p43' })
  })

  it('keeps the stream resolution selector editable when Web Stream is disabled and does not duplicate it in RtspCard', () => {
    const settings = () => makeSettings({ rtspEnabled: true, webStreamingEnabled: false, rtspResolution: '1080p43' })

    render(() => (
      <>
        <WebStreamCard
          settings={settings}
          updateStreamingAndSave={vi.fn()}
          updateStreamingDebounced={vi.fn()}
          setRecordingConfigAudio={vi.fn()}
        />
        <RtspCard
          settings={settings}
          updateStreamingAndSave={vi.fn()}
          updateStreamingDebounced={vi.fn()}
          setRecordingConfigAudio={vi.fn()}
        />
      </>
    ))

    const selects = screen.getAllByLabelText('Stream Resolution & Aspect Ratio') as HTMLSelectElement[]
    expect(selects).toHaveLength(1)
    expect(selects[0].disabled).toBe(false)
    expect(selects[0].value).toBe('1080p43')
    expect(screen.queryByLabelText('Resolution')).toBeNull()
  })
})

describe('Next UI preview container sizing CSS', () => {
  it('overrides base.css img/video height: auto so .preview-img letterboxes inside .preview-container', () => {
    const liveCss = readFileSync(resolve(process.cwd(), 'src/styles/next/views/live.css'), 'utf8')
    expect(liveCss).toMatch(/\[data-ui='next'\]\s+\.preview-container\s+\.preview-img\s*\{[^}]*width:\s*100%;[^}]*height:\s*100%;[^}]*object-fit:\s*contain;/s)
    expect(liveCss).toMatch(/\[data-ui='next'\]\s+\.preview-container:not\(\.preview-container-fullscreen\)\s*\{[^}]*min-height:/s)
  })
})
