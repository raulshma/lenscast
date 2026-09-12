// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@solidjs/testing-library'
import { MediaViewerOverlay } from './MediaViewer'
import { closeMedia, openMedia, viewerTarget } from '../lib/viewerStore'

// Light integration test for the shared viewer store: any component can
// openMedia(id) and the single App-mounted overlay renders it. The type
// hint keeps this fetch-free (a hinted target renders via fallback media).
beforeEach(() => closeMedia())
afterEach(() => {
  cleanup()
  closeMedia()
})

describe('MediaViewerOverlay + viewer store', () => {
  it('renders nothing while the store is closed', () => {
    render(() => <MediaViewerOverlay />)
    expect(screen.queryByTitle('Close viewer')).toBeNull()
    expect(viewerTarget()).toBeNull()
  })

  it('openMedia shows the overlay with the hinted video and closeMedia removes it', async () => {
    render(() => <MediaViewerOverlay />)
    openMedia('clip 7', { type: 'VIDEO', fileName: 'motion.mp4' })
    // The effect (and thus the overlay render) resolves a microtask later.
    await screen.findByText('motion.mp4')
    expect(document.querySelector('video')).toBeTruthy()
    expect(document.querySelector('video')?.getAttribute('src')).toBe('/api/media/clip%207')
    closeMedia()
    expect(document.querySelector('video')).toBeNull()
  })

  it('the viewer bar offers copy-link and download for the open media', () => {
    render(() => <MediaViewerOverlay />)
    openMedia('x1', { type: 'VIDEO' })
    expect(screen.getByText('Copy link')).toBeTruthy()
    const download = screen.getByText('Download').closest('a') as HTMLAnchorElement
    expect(download.getAttribute('href')).toBe('/api/media/x1?download=1')
  })
})
