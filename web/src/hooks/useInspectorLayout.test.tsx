// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, renderHook } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import type { NextState } from '../components/next/NextState'
import type { WebUiLayoutDto } from '../types'
import { defaultInspectorLayout, INSPECTOR_LAYOUT_STORAGE_KEY, setColumnWidth, toggleSectionCollapsed } from '../lib/inspectorLayout'
import { useInspectorLayout } from './useInspectorLayout'

const api = vi.hoisted(() => ({
  getUiLayout: vi.fn(),
  saveUiLayout: vi.fn(),
  resetUiLayout: vi.fn(),
}))

vi.mock('../api/client', () => api)

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

async function settle(): Promise<void> {
  for (let index = 0; index < 30; index += 1) await Promise.resolve()
}

function mount(viewer = false) {
  const [isViewer] = createSignal(viewer)
  const state = { isViewer } as unknown as NextState
  return renderHook(() => useInspectorLayout(state))
}

beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
  api.getUiLayout.mockReset().mockResolvedValue(defaultInspectorLayout())
  api.saveUiLayout.mockReset().mockImplementation(async (layout: WebUiLayoutDto) => ({ ...clone(layout), updatedAtMs: 1 }))
  api.resetUiLayout.mockReset()
})

afterEach(async () => {
  cleanup()
  await settle()
  vi.useRealTimers()
})

describe('useInspectorLayout hydration', () => {
  it('paints the local cache immediately, then replaces it with device truth', async () => {
    const cached = setColumnWidth(defaultInspectorLayout(), 400, 'custom')
    localStorage.setItem(INSPECTOR_LAYOUT_STORAGE_KEY, JSON.stringify(cached))
    const pending = deferred<WebUiLayoutDto>()
    api.getUiLayout.mockReturnValueOnce(pending.promise)
    const { result } = mount()
    expect(result.layout().column.widthPx).toBe(400)
    expect(result.ready()).toBe(false)
    const server = setColumnWidth(defaultInspectorLayout(), 360, 'custom')
    pending.resolve(server)
    await settle()
    expect(result.ready()).toBe(true)
    expect(result.layout().column.widthPx).toBe(360)
    expect(JSON.parse(localStorage.getItem(INSPECTOR_LAYOUT_STORAGE_KEY)!)).toEqual(server)
  })

  it('sanitizes corrupt cache and server documents', async () => {
    localStorage.setItem(INSPECTOR_LAYOUT_STORAGE_KEY, '{broken')
    api.getUiLayout.mockResolvedValueOnce({ column: { widthPx: 9999 }, sections: 'bad' })
    const { result } = mount()
    await settle()
    expect(result.layout().column.widthPx).toBe(560)
    expect(result.layout().sections).toHaveLength(5)
    expect(result.ready()).toBe(true)
  })

  it('falls back to local-only mode on a legacy 404 without an error', async () => {
    api.getUiLayout.mockRejectedValueOnce(Object.assign(new Error('Not found'), { status: 404 }))
    const { result } = mount()
    await settle()
    expect(result.localOnly()).toBe(true)
    expect(result.ready()).toBe(true)
    expect(result.error()).toBe('')
    result.mutate((layout) => setColumnWidth(layout, 420, 'wide'))
    await vi.advanceTimersByTimeAsync(1_000)
    expect(result.layout().column.widthPx).toBe(420)
    expect(api.saveUiLayout).not.toHaveBeenCalled()
  })

  it('keeps a usable cache and exposes retryable non-404 failures', async () => {
    api.getUiLayout.mockRejectedValueOnce(new Error('offline'))
    const { result } = mount()
    await settle()
    expect(result.ready()).toBe(true)
    expect(result.error()).toBe('offline')
    expect(result.layout()).toEqual(defaultInspectorLayout())
  })
})

describe('useInspectorLayout saves', () => {
  it('coalesces quick edits into one serialized PUT and reconciles the response', async () => {
    const { result } = mount()
    await settle()
    result.mutate((layout) => setColumnWidth(layout, 340, 'custom'))
    result.mutate((layout) => setColumnWidth(layout, 360, 'custom'))
    result.mutate((layout) => setColumnWidth(layout, 380, 'custom'))
    expect(result.layout().column.widthPx).toBe(380)
    expect(JSON.parse(localStorage.getItem(INSPECTOR_LAYOUT_STORAGE_KEY)!).column.widthPx).toBe(380)
    expect(api.saveUiLayout).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(350)
    expect(api.saveUiLayout).toHaveBeenCalledTimes(1)
    expect(api.saveUiLayout.mock.calls[0][0].column.widthPx).toBe(380)
    expect(result.layout().updatedAtMs).toBe(1)
    expect(result.saving()).toBe(false)
  })

  it('drops a stale hydration response when an optimistic edit wins', async () => {
    const pending = deferred<WebUiLayoutDto>()
    api.getUiLayout.mockReturnValueOnce(pending.promise)
    const { result } = mount()
    result.mutate((layout) => setColumnWidth(layout, 410, 'custom'))
    pending.resolve(setColumnWidth(defaultInspectorLayout(), 300, 'custom'))
    await settle()
    expect(result.ready()).toBe(true)
    expect(result.layout().column.widthPx).toBe(410)
    await vi.advanceTimersByTimeAsync(350)
    expect(api.saveUiLayout.mock.calls[0][0].column.widthPx).toBe(410)
  })

  it('keeps a newer optimistic edit and sends one trailing write after an in-flight save', async () => {
    const first = deferred<WebUiLayoutDto>()
    api.saveUiLayout.mockReturnValueOnce(first.promise)
    const { result } = mount()
    await settle()
    result.mutate((layout) => setColumnWidth(layout, 340, 'custom'))
    await vi.advanceTimersByTimeAsync(350)
    expect(result.saving()).toBe(true)
    result.mutate((layout) => setColumnWidth(layout, 460, 'custom'))
    await vi.advanceTimersByTimeAsync(350)
    expect(api.saveUiLayout).toHaveBeenCalledTimes(1)
    first.resolve({ ...defaultInspectorLayout(), updatedAtMs: 10, column: { widthPx: 340, widthPreset: 'custom', collapsed: false } })
    await settle()
    expect(result.layout().column.widthPx).toBe(460)
    expect(result.layout().updatedAtMs).toBe(10)
    await vi.advanceTimersByTimeAsync(350)
    expect(api.saveUiLayout).toHaveBeenCalledTimes(2)
    expect(api.saveUiLayout.mock.calls[1][0].column.widthPx).toBe(460)
  })

  it('latches local-only mode when a PUT reports legacy firmware', async () => {
    api.saveUiLayout.mockRejectedValueOnce(Object.assign(new Error('Not found'), { status: 404 }))
    const { result } = mount()
    await settle()
    result.mutate((layout) => setColumnWidth(layout, 420, 'wide'))
    await vi.advanceTimersByTimeAsync(350)
    expect(result.localOnly()).toBe(true)
    expect(result.error()).toBe('')
    expect(result.layout().column.widthPx).toBe(420)
  })

  it('resets through DELETE without issuing a redundant PUT', async () => {
    api.resetUiLayout.mockResolvedValue(defaultInspectorLayout())
    const { result } = mount()
    await settle()
    result.mutate((layout) => setColumnWidth(layout, 440, 'custom'))
    await result.reset()
    expect(api.resetUiLayout).toHaveBeenCalledTimes(1)
    expect(api.saveUiLayout).not.toHaveBeenCalled()
    expect(result.layout()).toEqual(defaultInspectorLayout())
    await vi.advanceTimersByTimeAsync(1_000)
    expect(api.saveUiLayout).not.toHaveBeenCalled()
  })

  it('serializes reset after a PUT and preserves edits made during DELETE', async () => {
    const firstSave = deferred<WebUiLayoutDto>()
    const pendingReset = deferred<WebUiLayoutDto>()
    api.saveUiLayout.mockReturnValueOnce(firstSave.promise)
    api.resetUiLayout.mockReturnValueOnce(pendingReset.promise)
    const { result } = mount()
    await settle()
    result.mutate((layout) => setColumnWidth(layout, 340, 'custom'))
    await vi.advanceTimersByTimeAsync(350)
    void result.reset()
    expect(result.layout()).toEqual(defaultInspectorLayout())
    expect(api.resetUiLayout).not.toHaveBeenCalled()
    firstSave.resolve({ ...defaultInspectorLayout(), updatedAtMs: 5 })
    await settle()
    expect(api.resetUiLayout).toHaveBeenCalledTimes(1)
    result.mutate((layout) => setColumnWidth(layout, 460, 'custom'))
    pendingReset.resolve(defaultInspectorLayout())
    await settle()
    expect(result.layout().column.widthPx).toBe(460)
    await vi.advanceTimersByTimeAsync(350)
    expect(api.saveUiLayout).toHaveBeenCalledTimes(2)
    expect(api.saveUiLayout.mock.calls[1][0].column.widthPx).toBe(460)
  })

  it('retains dirty local state after a server failure', async () => {
    api.saveUiLayout.mockRejectedValueOnce(new Error('disk full'))
    const { result } = mount()
    await settle()
    result.mutate((layout) => setColumnWidth(layout, 420, 'wide'))
    await vi.advanceTimersByTimeAsync(350)
    expect(result.error()).toBe('disk full')
    expect(result.layout().column.widthPx).toBe(420)
    expect(result.saving()).toBe(false)
    await result.flush()
    expect(api.saveUiLayout).toHaveBeenCalledTimes(2)
  })
})

describe('useInspectorLayout refresh and roles', () => {
  it('refreshes on the slow lane, accepts newer snapshots, and observes a device reset', async () => {
    const { result } = mount()
    await settle()
    api.getUiLayout.mockResolvedValueOnce({ ...defaultInspectorLayout(), updatedAtMs: 2 })
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.getUiLayout).toHaveBeenCalledTimes(2)
    expect(result.layout().updatedAtMs).toBe(2)
    api.getUiLayout.mockResolvedValueOnce({ ...defaultInspectorLayout(), updatedAtMs: 1 })
    await vi.advanceTimersByTimeAsync(30_000)
    expect(result.layout().updatedAtMs).toBe(2)
    api.getUiLayout.mockResolvedValueOnce(defaultInspectorLayout())
    await vi.advanceTimersByTimeAsync(30_000)
    expect(result.layout()).toEqual(defaultInspectorLayout())
  })

  it('refreshes on visibility resume but never while a local save is dirty', async () => {
    const { result } = mount()
    await settle()
    result.mutate((layout) => setColumnWidth(layout, 400, 'custom'))
    document.dispatchEvent(new Event('visibilitychange'))
    await settle()
    expect(api.getUiLayout).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(350)
    document.dispatchEvent(new Event('visibilitychange'))
    await settle()
    expect(api.getUiLayout).toHaveBeenCalledTimes(2)
  })

  it('lets viewers read the operator layout but makes every mutation a no-op', async () => {
    const operator = setColumnWidth(defaultInspectorLayout(), 440, 'custom')
    api.getUiLayout.mockResolvedValueOnce(operator)
    const { result } = mount(true)
    await settle()
    expect(result.layout().column.widthPx).toBe(440)
    result.mutate((layout) => setColumnWidth(layout, 260, 'compact'))
    result.reset()
    result.replaceLayout(defaultInspectorLayout())
    await vi.advanceTimersByTimeAsync(1_000)
    expect(result.layout().column.widthPx).toBe(440)
    expect(api.saveUiLayout).not.toHaveBeenCalled()
    expect(api.resetUiLayout).not.toHaveBeenCalled()
  })

  it('releases stale request locks across role changes and preserves unsaved admin edits', async () => {
    const firstSave = deferred<WebUiLayoutDto>()
    api.saveUiLayout.mockReturnValueOnce(firstSave.promise)
    const [isViewer, setIsViewer] = createSignal(false)
    let controller!: ReturnType<typeof useInspectorLayout>
    render(() => {
      controller = useInspectorLayout({ isViewer } as unknown as NextState)
      return null
    })
    await settle()
    controller.mutate((layout) => setColumnWidth(layout, 420, 'custom'))
    await vi.advanceTimersByTimeAsync(350)
    expect(controller.saving()).toBe(true)

    setIsViewer(true)
    await settle()
    expect(controller.saving()).toBe(false)
    firstSave.resolve({ ...defaultInspectorLayout(), updatedAtMs: 10 })
    await settle()
    expect(controller.layout().column.widthPx).toBe(420)

    setIsViewer(false)
    await settle()
    expect(controller.layout().column.widthPx).toBe(420)
    await vi.advanceTimersByTimeAsync(350)
    expect(api.saveUiLayout).toHaveBeenCalledTimes(2)
    expect(api.saveUiLayout.mock.calls[1][0].column.widthPx).toBe(420)
    expect(controller.saving()).toBe(false)
  })

  it('invalidates pending continuations on unmount', async () => {
    const pending = deferred<WebUiLayoutDto>()
    api.getUiLayout.mockReturnValueOnce(pending.promise)
    const [isViewer] = createSignal(false)
    let controller!: ReturnType<typeof useInspectorLayout>
    const { unmount } = render(() => {
      controller = useInspectorLayout({ isViewer } as unknown as NextState)
      return null
    })
    unmount()
    pending.resolve(setColumnWidth(defaultInspectorLayout(), 500, 'custom'))
    await settle()
    expect(controller.ready()).toBe(false)
  })
})

describe('useInspectorLayout reference stability', () => {
  it('keeps section object identity across an optimistic edit and its server echo', async () => {
    // The column's <For> reconciles by reference: if the sanitized edit or
    // the echoed save rebuilt every section, every panel would remount and
    // re-fetch on a one-section toggle.
    const { result } = mount()
    await settle()
    const before = result.layout().sections
    result.mutate((layout) => toggleSectionCollapsed(layout, 'storage'))
    const afterEdit = result.layout()
    expect(afterEdit.sections[0]).toBe(before[0])
    expect(afterEdit.sections[2]).toBe(before[2])
    expect(afterEdit.sections[3]).toBe(before[3])
    expect(afterEdit.sections[4]).toBe(before[4])
    expect(afterEdit.sections[1]).not.toBe(before[1])
    await vi.advanceTimersByTimeAsync(350)
    expect(api.saveUiLayout).toHaveBeenCalledTimes(1)
    const afterSave = result.layout()
    expect(afterSave.sections[0]).toBe(before[0])
    expect(afterSave.sections[1].collapsed).toBe(true)
    expect(afterSave.updatedAtMs).toBe(1)
  })

  it('issues no write when an edit sanitizes back to the current document', async () => {
    const { result } = mount()
    await settle()
    const reference = result.layout()
    // The padded id survives inspectorLayoutsEqual but trims back to the
    // current document — a no-op, not a save.
    result.mutate((layout) => ({ ...layout, activePresetId: '  default  ' }))
    expect(result.layout()).toBe(reference)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(api.saveUiLayout).not.toHaveBeenCalled()
  })
})
