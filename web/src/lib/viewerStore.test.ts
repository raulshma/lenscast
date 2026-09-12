import { describe, expect, it } from 'vitest'
import { fallbackViewerMedia, resolveViewerMedia, toViewerMedia, type ViewerTarget } from './viewerStore'

const GALLERY_ROW = {
  id: 'clip-1',
  type: 'VIDEO',
  fileName: 'motion_20260909.mp4',
  url: '/api/media/clip-1',
  thumbnailUrl: '/api/media/clip-1/thumbnail',
  downloadUrl: '/api/media/clip-1?download=1',
}

describe('resolveViewerMedia', () => {
  it('a known id keeps the server row (typed, named, thumbnailed)', () => {
    const target: ViewerTarget = { id: 'clip-1' }
    expect(resolveViewerMedia(target, [GALLERY_ROW])).toEqual({
      id: 'clip-1',
      type: 'VIDEO',
      fileName: 'motion_20260909.mp4',
      url: '/api/media/clip-1',
      thumbnailUrl: '/api/media/clip-1/thumbnail',
      downloadUrl: '/api/media/clip-1?download=1',
    })
  })

  it('null when the page does not contain the id — the caller falls back', () => {
    expect(resolveViewerMedia({ id: 'other' }, [GALLERY_ROW])).toBeNull()
  })

  it('degrades field-by-field on a malformed row, keeping the viewer renderable', () => {
    const resolved = resolveViewerMedia(
      { id: 'clip-1', type: 'VIDEO', fileName: 'hint.mp4' },
      [{ id: 'clip-1', type: 'unknown', fileName: 42, url: null, thumbnailUrl: null, downloadUrl: null }],
    )
    expect(resolved).toEqual({
      id: 'clip-1',
      type: 'VIDEO',
      fileName: 'hint.mp4',
      url: '/api/media/clip-1',
      thumbnailUrl: '',
      downloadUrl: '/api/media/clip-1?download=1',
    })
  })

  it('an unknown row type falls back to the target hint, then VIDEO', () => {
    const resolved = resolveViewerMedia({ id: 'clip-1', type: 'PHOTO' }, [
      { ...GALLERY_ROW, type: 'mystery' },
    ])
    expect(resolved?.type).toBe('PHOTO')
    const bare = resolveViewerMedia({ id: 'clip-1' }, [{ ...GALLERY_ROW, type: 'mystery' }])
    expect(bare?.type).toBe('VIDEO')
  })
})

describe('fallbackViewerMedia', () => {
  it('clips from the event feed render as videos on the media route', () => {
    expect(fallbackViewerMedia({ id: 'clip 7', type: 'VIDEO', fileName: 'sound.mp4' })).toEqual({
      id: 'clip 7',
      type: 'VIDEO',
      fileName: 'sound.mp4',
      url: '/api/media/clip%207',
      thumbnailUrl: '',
      downloadUrl: '/api/media/clip%207?download=1',
    })
  })

  it('a bare deep-link target defaults to the video player', () => {
    const media = fallbackViewerMedia({ id: 'x' })
    expect(media.type).toBe('VIDEO')
    expect(media.fileName).toBe('x')
  })
})

describe('toViewerMedia', () => {
  it('maps a gallery row straight onto the viewer view model', () => {
    expect(toViewerMedia(GALLERY_ROW)).toEqual({
      id: 'clip-1',
      type: 'VIDEO',
      fileName: 'motion_20260909.mp4',
      url: '/api/media/clip-1',
      thumbnailUrl: '/api/media/clip-1/thumbnail',
      downloadUrl: '/api/media/clip-1?download=1',
    })
  })

  it('an unexpected type value reads as a video rather than crashing', () => {
    expect(toViewerMedia({ ...GALLERY_ROW, type: 'weird' }).type).toBe('VIDEO')
    expect(toViewerMedia({ ...GALLERY_ROW, type: 'PHOTO' }).type).toBe('PHOTO')
  })
})
