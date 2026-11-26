// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { observeChildTransitions } from './animateList'

interface RecordedAnimation {
  node: Element
  keyframes: unknown[]
  options?: KeyframeAnimationOptions
}

const recorded: RecordedAnimation[] = []
const tops = new WeakMap<Element, number>()
const heights = new WeakMap<Element, number>()
const widths = new WeakMap<Element, number>()

async function flushMicrotasks(): Promise<void> {
  for (let i = 0; i < 6; i += 1) await Promise.resolve()
}

beforeEach(() => {
  recorded.length = 0
  Object.defineProperty(Element.prototype, 'animate', {
    configurable: true,
    writable: true,
    value(this: Element, keyframes: unknown[], options?: KeyframeAnimationOptions) {
      recorded.push({ node: this, keyframes, options })
      return { onfinish: null, oncancel: null, cancel: vi.fn() }
    },
  })
  Object.defineProperty(HTMLElement.prototype, 'offsetTop', {
    configurable: true,
    get(this: HTMLElement) { return tops.get(this) ?? 0 },
  })
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
    configurable: true,
    get(this: HTMLElement) { return heights.get(this) ?? 0 },
  })
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
    configurable: true,
    get(this: HTMLElement) { return widths.get(this) ?? 0 },
  })
})

afterEach(() => {
  document.body.innerHTML = ''
  delete (Element.prototype as unknown as { animate?: unknown }).animate
  delete (HTMLElement.prototype as unknown as { offsetTop?: unknown }).offsetTop
  delete (HTMLElement.prototype as unknown as { offsetHeight?: unknown }).offsetHeight
  delete (HTMLElement.prototype as unknown as { offsetWidth?: unknown }).offsetWidth
  delete (window as unknown as { matchMedia?: unknown }).matchMedia
  vi.restoreAllMocks()
})

describe('observeChildTransitions', () => {
  it('animates added items, FLIPs moved siblings, ghosts removed items, and transitions container height', async () => {
    const root = document.createElement('div')
    const first = document.createElement('div')
    const second = document.createElement('div')
    heights.set(root, 100)
    tops.set(first, 0); widths.set(first, 240); heights.set(first, 40)
    tops.set(second, 50); widths.set(second, 240); heights.set(second, 40)
    root.append(first, second)
    document.body.appendChild(root)

    const stop = observeChildTransitions(root, { animateHeight: true })

    // Remove `first`, add `third`, shift `second` up and shrink root.
    const third = document.createElement('div')
    heights.set(root, 80)
    tops.set(second, 0)
    tops.set(third, 50); widths.set(third, 240); heights.set(third, 30)
    first.remove()
    root.appendChild(third)

    await flushMicrotasks()

    const ghost = root.querySelector<HTMLElement>('[data-nx-ghost="true"]')
    expect(ghost).not.toBeNull()
    expect(ghost!.getAttribute('aria-hidden')).toBe('true')
    expect(ghost!.style.top).toBe('0px')
    expect(ghost!.style.height).toBe('40px')

    const byNode = new Map(recorded.map((entry) => [entry.node, entry.keyframes]))
    expect(byNode.get(third)).toEqual([
      { opacity: 0, transform: 'translateY(6px)' },
      { opacity: 1, transform: 'translateY(0px)' },
    ])
    expect(byNode.get(second)).toEqual([
      { transform: 'translateY(50px)' },
      { transform: 'translateY(0px)' },
    ])
    expect(byNode.get(ghost!)).toEqual([
      { opacity: 1, transform: 'translateY(0px)' },
      { opacity: 0, transform: 'translateY(-4px)' },
    ])
    expect(byNode.get(root)).toEqual([
      { height: '100px' },
      { height: '80px' },
    ])

    stop()
  })

  it('skips animations while suspended (e.g. collapsed panel)', async () => {
    const root = document.createElement('div')
    const item = document.createElement('div')
    root.appendChild(item)
    document.body.appendChild(root)

    const stop = observeChildTransitions(root, { isSuspended: () => true })
    const added = document.createElement('div')
    root.appendChild(added)
    await flushMicrotasks()

    expect(recorded).toHaveLength(0)
    stop()
  })

  it('degrades cleanly under prefers-reduced-motion', async () => {
    ;(window as unknown as { matchMedia: () => { matches: boolean } }).matchMedia = () => ({ matches: true })
    const root = document.createElement('div')
    document.body.appendChild(root)
    const stop = observeChildTransitions(root)
    root.appendChild(document.createElement('div'))
    await flushMicrotasks()
    expect(recorded).toHaveLength(0)
    stop()
  })
})
