// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, fireEvent, render } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import StreamPreview from './StreamPreview'
import { previewActionsModeStore } from '../lib/previewActionsMode'
import { streamStatsOverlayStore } from '../lib/streamStatsOverlay'
import { en } from '../i18n/en'
import type { PlayerMode } from '../video/playerLadder'

function renderCompactPreview(opts: { webActive?: boolean; rtspActive?: boolean } = {}) {
  const [playerMode, setPlayerMode] = createSignal<PlayerMode>('whep')
  return render(() => (
    <StreamPreview
      status={() => ({
        streaming: {
          isActive: !!(opts.webActive || opts.rtspActive),
          webStreamingEnabled: true,
          webStreamingActive: !!opts.webActive,
          rtspEnabled: true,
          rtspStreamingActive: !!opts.rtspActive,
        },
      } as any)}
      previewVisible={() => !!opts.webActive}
      streamNonce={() => 0}
      streamActionLoading={() => false}
      isRecording={() => false}
      captureMsg={() => ''}
      liveAudioStatus={() => 'idle'}
      recordingTimer={{ formatElapsed: () => '00:00' }}
      playerMode={playerMode}
      setPlayerMode={setPlayerMode}
      handleCapture={() => {}}
      handleStartWebStream={() => {}}
      handleStopWebStream={() => {}}
      handleStartRtspStream={() => {}}
      handleStopRtspStream={() => {}}
      setPreviewVisible={() => {}}
      connectionLost={() => false}
      compactActions
    />
  ))
}

describe('StreamPreview compact action bar (beta UI)', () => {
  beforeEach(() => {
    localStorage.removeItem('lenscast.previewActions')
    previewActionsModeStore().set('compact')
    streamStatsOverlayStore().set('auto')
  })

  afterEach(() => {
    cleanup()
  })

  it('defaults to compact mode with icon buttons for secondary controls and full text for important actions', () => {
    renderCompactPreview()
    const bar = document.querySelector('.preview-actions')!
    expect(bar.classList.contains('preview-actions-compact')).toBe(true)
    expect(bar.classList.contains('preview-actions-full')).toBe(false)

    const leftButtons = [...document.querySelectorAll('.preview-actions-left .action-btn')]
    expect(leftButtons).toHaveLength(9)

    // Secondary actions carry .action-btn-icon (collapsed to 32px icons via CSS).
    const iconButtons = leftButtons.filter((b) => b.classList.contains('action-btn-icon'))
    expect(iconButtons).toHaveLength(6)

    // Important actions (Capture, Web Stream, RTSP Stream) remain full-text buttons.
    const fullTextButtons = leftButtons.filter((b) => !b.classList.contains('action-btn-icon'))
    expect(fullTextButtons.map((b) => b.textContent?.trim())).toEqual([
      en['preview.capture'],
      en['preview.webStream'],
      en['preview.rtspStream'],
    ])
  })

  it('attaches data-tooltip and aria-label instead of native title on all controls', () => {
    renderCompactPreview({ webActive: false, rtspActive: true })
    const controls = [...document.querySelectorAll('.preview-actions .action-btn')]
    expect(controls).toHaveLength(10) // 9 action buttons + 1 label toggle

    for (const el of controls) {
      expect(el.getAttribute('data-tooltip')).toBeTruthy()
      expect(el.getAttribute('aria-label')).toBe(el.getAttribute('data-tooltip'))
      expect(el.hasAttribute('title')).toBe(false)
    }

    expect(document.querySelector(`[data-tooltip="${en['preview.webStreamStartTitle']}"]`)).not.toBeNull()
    expect(document.querySelector(`[data-tooltip="${en['preview.rtspStreamStopTitle']}"]`)).not.toBeNull()
  })

  it('allows switching between compact icon buttons and full-text buttons and persists the choice', () => {
    renderCompactPreview()
    const bar = document.querySelector('.preview-actions')!
    const toggle = document.querySelector('.preview-actions-toggle') as HTMLButtonElement

    expect(toggle.getAttribute('aria-pressed')).toBe('false')
    expect(toggle.getAttribute('data-tooltip')).toBe(en['preview.actions.showLabels'])

    fireEvent.click(toggle)

    expect(bar.classList.contains('preview-actions-compact')).toBe(false)
    expect(bar.classList.contains('preview-actions-full')).toBe(true)
    expect(toggle.getAttribute('aria-pressed')).toBe('true')
    expect(toggle.getAttribute('data-tooltip')).toBe(en['preview.actions.compactButtons'])
    expect(localStorage.getItem('lenscast.previewActions')).toBe('full')

    fireEvent.click(toggle)

    expect(bar.classList.contains('preview-actions-compact')).toBe(true)
    expect(bar.classList.contains('preview-actions-full')).toBe(false)
    expect(localStorage.getItem('lenscast.previewActions')).toBe('compact')
  })

  it('reflects stats overlay state visually on the stats icon button', () => {
    renderCompactPreview()
    const statsBtn = document.querySelectorAll('.preview-actions-left .action-btn')[1]

    expect(statsBtn.classList.contains('action-btn-toggled')).toBe(false)
    expect(statsBtn.classList.contains('action-btn-muted')).toBe(false)

    fireEvent.click(statsBtn) // auto -> on
    expect(statsBtn.classList.contains('action-btn-toggled')).toBe(true)
    expect(statsBtn.classList.contains('action-btn-muted')).toBe(false)

    fireEvent.click(statsBtn) // on -> off
    expect(statsBtn.classList.contains('action-btn-toggled')).toBe(false)
    expect(statsBtn.classList.contains('action-btn-muted')).toBe(true)
  })

  it('leaves classic StreamPreview (without compactActions) untouched', () => {
    const [playerMode, setPlayerMode] = createSignal<PlayerMode>('whep')
    render(() => (
      <StreamPreview
        status={() => null}
        previewVisible={() => false}
        streamNonce={() => 0}
        streamActionLoading={() => false}
        isRecording={() => false}
        captureMsg={() => ''}
        liveAudioStatus={() => 'idle'}
        recordingTimer={{ formatElapsed: () => '00:00' }}
        playerMode={playerMode}
        setPlayerMode={setPlayerMode}
        handleCapture={() => {}}
        handleStartWebStream={() => {}}
        handleStopWebStream={() => {}}
        handleStartRtspStream={() => {}}
        handleStopRtspStream={() => {}}
        setPreviewVisible={() => {}}
        connectionLost={() => false}
      />
    ))

    const bar = document.querySelector('.preview-actions')!
    expect(bar.classList.contains('preview-actions-compact')).toBe(false)
    expect(bar.classList.contains('preview-actions-full')).toBe(false)
    expect(document.querySelector('.preview-actions-toggle')).toBeNull()
  })
})
