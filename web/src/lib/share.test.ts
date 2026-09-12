import { describe, expect, it, vi } from 'vitest'
import {
  canShare,
  canShareFiles,
  copyText,
  dashboardUrl,
  mediaHash,
  mediaShareUrl,
  shareSnapshotImage,
  snapshotFileName,
  type LocationLike,
  type NavigatorShareLike,
} from './share'

const LAN: LocationLike = { origin: 'http://192.168.1.10:8080', pathname: '/' }

describe('dashboardUrl / mediaHash / mediaShareUrl', () => {
  it('builds the absolute LAN URL another device can type', () => {
    expect(dashboardUrl(LAN)).toBe('http://192.168.1.10:8080/')
    expect(dashboardUrl({ ...LAN, pathname: '/index.html' })).toBe('http://192.168.1.10:8080/index.html')
  })

  it('encodes the media id into the viewer deep link', () => {
    expect(mediaHash('clip 7')).toBe('#/gallery/clip%207')
    expect(mediaShareUrl(LAN, 'clip 7')).toBe('http://192.168.1.10:8080/#/gallery/clip%207')
  })
})

describe('canShare / canShareFiles', () => {
  it('detects the Web Share API surface', () => {
    expect(canShare({})).toBe(false)
    expect(canShare({ share: 'not a function' })).toBe(false)
    expect(canShare({ share: () => Promise.resolve() })).toBe(true)
  })

  it('file sharing needs both share and canShare as functions', () => {
    expect(canShareFiles({ share: () => Promise.resolve() })).toBe(false)
    expect(canShareFiles({ share: () => Promise.resolve(), canShare: () => false })).toBe(false)
    expect(canShareFiles({ share: () => Promise.resolve(), canShare: () => true })).toBe(true)
  })

  it('a throwing canShare probe reads as no file sharing', () => {
    expect(canShareFiles({
      share: () => Promise.resolve(),
      canShare: () => {
        throw new TypeError('bad payload')
      },
    })).toBe(false)
  })
})

describe('snapshotFileName', () => {
  it('stamps `YYYYMMDD_HHmmss.jpg`, zero-padded', () => {
    expect(snapshotFileName(new Date(2026, 8, 9, 7, 5, 3))).toBe('SNAP_20260909_070503.jpg')
    expect(snapshotFileName(new Date(2026, 11, 31, 23, 59, 59))).toBe('SNAP_20261231_235959.jpg')
  })
})

describe('copyText', () => {
  it('prefers the async Clipboard API', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    const execCopy = vi.fn()
    await expect(copyText('x', { writeText, execCopy })).resolves.toBe(true)
    expect(writeText).toHaveBeenCalledWith('x')
    expect(execCopy).not.toHaveBeenCalled()
  })

  it('falls back to execCommand when the Clipboard API rejects', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    const execCopy = vi.fn(() => true)
    await expect(copyText('x', { writeText, execCopy })).resolves.toBe(true)
    expect(execCopy).toHaveBeenCalledWith('x')
  })

  it('reports failure when both paths are missing or fail', async () => {
    await expect(copyText('x', {})).resolves.toBe(false)
    await expect(copyText('x', { execCopy: () => false })).resolves.toBe(false)
    await expect(copyText('x', { execCopy: () => {
      throw new Error('nope')
    } })).resolves.toBe(false)
  })
})

describe('shareSnapshotImage', () => {
  const blob = new Blob(['jpeg-bytes'], { type: 'image/jpeg' })

  it('shares a File payload when the browser supports file sharing', async () => {
    const shareFiles = vi.fn().mockResolvedValue(undefined)
    const download = vi.fn()
    await expect(shareSnapshotImage(blob, 'SNAP.jpg', { shareFiles, download })).resolves.toBe('shared')
    expect(shareFiles).toHaveBeenCalledTimes(1)
    const arg = shareFiles.mock.calls[0][0] as { files: File[]; title: string }
    expect(arg.files[0]).toBeInstanceOf(File)
    expect(arg.files[0].name).toBe('SNAP.jpg')
    expect(download).not.toHaveBeenCalled()
  })

  it('treats a share-sheet cancel as cancelled — not an error, not a download', async () => {
    const abort = Object.assign(new Error('user closed the sheet'), { name: 'AbortError' })
    const download = vi.fn()
    await expect(shareSnapshotImage(blob, 'SNAP.jpg', {
      shareFiles: () => Promise.reject(abort),
      download,
    })).resolves.toBe('cancelled')
    expect(download).not.toHaveBeenCalled()
  })

  it('a failing share falls through to the download rung', async () => {
    const download = vi.fn()
    await expect(shareSnapshotImage(blob, 'SNAP.jpg', {
      shareFiles: () => Promise.reject(new Error('share failed')),
      download,
    })).resolves.toBe('downloaded')
    expect(download).toHaveBeenCalledWith(blob, 'SNAP.jpg')
  })

  it('downloads straight away when no share surface exists', async () => {
    const download = vi.fn()
    await expect(shareSnapshotImage(blob, 'SNAP.jpg', { download })).resolves.toBe('downloaded')
  })

  it('throws when neither rung is available', async () => {
    await expect(shareSnapshotImage(blob, 'SNAP.jpg', {})).rejects.toThrow('not available')
  })
})
