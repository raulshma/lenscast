// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render } from '@solidjs/testing-library'
import { createMemo, createSignal } from 'solid-js'
import type { WebUiSectionPlacementDto } from '../../../types'
import { defaultInspectorLayout } from '../../../lib/inspectorLayout'
import AnimatedInspectorStack from './AnimatedInspectorStack'

interface RecordedAnimation {
  node: Element
  keyframes: unknown[]
  options?: KeyframeAnimationOptions
}

const recorded: RecordedAnimation[] = []

function placement(id: string): WebUiSectionPlacementDto {
  return { ...defaultInspectorLayout().sections[0], id, sectionId: id }
}

/** jsdom has no layout: offsetTop is steered per id, index-based, so the
 * FLIP math runs against controlled positions instead of zeros. */
const tops = new Map<string, number>()

beforeEach(() => {
  recorded.length = 0
  tops.clear()
  Object.defineProperty(Element.prototype, 'animate', {
    configurable: true,
    writable: true,
    value(this: Element, keyframes: unknown[], options?: KeyframeAnimationOptions) {
      recorded.push({ node: this, keyframes, options })
      // Inert stand-in: the real onfinish fires after the animation, and
      // the stack's ghost removal also carries a timeout backstop.
      return { onfinish: null, oncancel: null, cancel: vi.fn() }
    },
  })
  Object.defineProperty(HTMLElement.prototype, 'offsetTop', {
    configurable: true,
    get(this: HTMLElement) {
      return tops.get(this.dataset?.placementId ?? '') ?? 0
    },
  })
})

afterEach(() => {
  cleanup()
  delete (Element.prototype as unknown as { animate?: unknown }).animate
  delete (HTMLElement.prototype as unknown as { offsetTop?: unknown }).offsetTop
  delete (window as unknown as { matchMedia?: unknown }).matchMedia
  vi.restoreAllMocks()
})

function mountStack(initial: WebUiSectionPlacementDto[]) {
  const [list, setList] = createSignal(initial)
  let rootEl!: HTMLDivElement
  render(() => (
    <div ref={(el: HTMLDivElement) => { rootEl = el }}>
      <AnimatedInspectorStack each={() => list()} root={() => rootEl}>
        {(section) => (
          <div
            class="nx-inspector-frame"
            data-section-id={section().sectionId}
            data-placement-id={section().id}
          >
            {section().id}
          </div>
        )}
      </AnimatedInspectorStack>
    </div>
  ))
  // Live frames only — a fading ghost still carries data-section-id.
  const frameNodes = () => Array.from(rootEl.querySelectorAll<HTMLElement>(':scope > [data-placement-id]'))
  const frameIds = () => frameNodes().map((frame) => frame.getAttribute('data-placement-id'))
  return { setList, frameIds, frameNodes, root: () => rootEl }
}

describe('AnimatedInspectorStack', () => {
  it('primes the first observation without animating the initial paint', () => {
    const { frameIds } = mountStack([placement('a'), placement('b')])
    expect(frameIds()).toEqual(['a', 'b'])
    expect(recorded).toHaveLength(0)
  })

  it('keeps a frame\'s DOM node when only its placement content changes', () => {
    const initial = [placement('a'), placement('b')]
    const { setList, frameNodes } = mountStack(initial)
    const before = frameNodes()
    setList(initial.map((section) => ({ ...section, collapsed: !section.collapsed })))
    // The <For> keys rows by id, not by placement reference: an edit must
    // re-dress the frame in place, never remount (and re-fetch) it.
    expect(frameNodes()[0]).toBe(before[0])
    expect(frameNodes()[1]).toBe(before[1])
    expect(recorded).toHaveLength(0)
  })

  it('flips kept frames from their previous row when the order changes', () => {
    // Initial rows, before the mount primes them: a=0, b=100, c=200.
    tops.set('a', 0)
    tops.set('b', 100)
    tops.set('c', 200)
    const { setList, frameIds } = mountStack([placement('a'), placement('b'), placement('c')])
    // The reordered rows: c=0, a=100, b=200.
    tops.set('c', 0)
    tops.set('a', 100)
    tops.set('b', 200)
    setList([placement('c'), placement('a'), placement('b')])
    expect(frameIds()).toEqual(['c', 'a', 'b'])
    const byId = new Map(recorded.map((entry) => [entry.node.getAttribute('data-placement-id'), entry]))
    expect(byId.get('a')?.keyframes).toEqual([
      { transform: 'translateY(-100px)' },
      { transform: 'translateY(0px)' },
    ])
    expect(byId.get('b')?.keyframes).toEqual([
      { transform: 'translateY(-100px)' },
      { transform: 'translateY(0px)' },
    ])
    expect(byId.get('c')?.keyframes).toEqual([
      { transform: 'translateY(200px)' },
      { transform: 'translateY(0px)' },
    ])
    for (const entry of recorded) expect(entry.options?.duration).toBe(220)
  })

  it('settles an added frame in with a fade-and-rise', () => {
    const { setList, frameIds } = mountStack([placement('a')])
    setList([placement('a'), placement('b')])
    expect(frameIds()).toEqual(['a', 'b'])
    expect(recorded).toHaveLength(1)
    expect(recorded[0].node.getAttribute('data-placement-id')).toBe('b')
    expect(recorded[0].keyframes).toEqual([
      { opacity: 0, transform: 'translateY(8px)' },
      { opacity: 1, transform: 'translateY(0px)' },
    ])
  })

  it('ghosts a removed frame out and keeps it out of the live measurements', () => {
    tops.set('a', 0)
    tops.set('b', 100)
    tops.set('c', 200)
    const { setList, frameIds, root } = mountStack([placement('a'), placement('b'), placement('c')])
    recorded.length = 0
    setList([placement('a'), placement('b')])
    expect(frameIds()).toEqual(['a', 'b'])
    const ghosts = Array.from(root().querySelectorAll<HTMLElement>('[aria-hidden="true"]'))
    expect(ghosts).toHaveLength(1)
    const ghost = ghosts[0]
    expect(ghost.getAttribute('data-placement-id')).toBeNull()
    expect(ghost.textContent).toContain('c')
    expect(ghost.style.position).toBe('absolute')
    expect(ghost.style.top).toBe('200px')
    // The fade is the ghost's; the kept frames did not move (a and b keep
    // their rows), so nothing else animated.
    expect(recorded).toHaveLength(1)
    expect(recorded[0].node).toBe(ghost)
    expect(recorded[0].keyframes).toEqual([{ opacity: 1 }, { opacity: 0 }])
    // A re-run of the stack must not measure the fading ghost as a frame.
    setList([placement('a'), placement('b')])
    expect(frameIds()).toEqual(['a', 'b'])
    expect(root().querySelectorAll('[aria-hidden="true"]').length).toBeGreaterThanOrEqual(1)
  })

  it('runs no motion under prefers-reduced-motion', () => {
    // jsdom ships no matchMedia; the reduce query is the stub itself.
    ;(window as unknown as { matchMedia: () => { matches: boolean } }).matchMedia = () => ({ matches: true })
    const { setList } = mountStack([placement('a')])
    setList([placement('a'), placement('b')])
    expect(recorded).toHaveLength(0)
  })

  it('degrades to the plain list when WAAPI is unavailable', () => {
    delete (Element.prototype as unknown as { animate?: unknown }).animate
    const { setList, frameIds } = mountStack([placement('a'), placement('b')])
    setList([placement('b'), placement('a')])
    expect(frameIds()).toEqual(['b', 'a'])
  })

  it('never FLIPs on same-order updates such as collapse or save-echo updatedAtMs', () => {
    tops.set('a', 0)
    tops.set('b', 100)
    const initial = [placement('a'), placement('b')]
    const { setList } = mountStack(initial)

    // User collapses `a` (CSS accordion starts; at t=0 b's offsetTop is still 100).
    setList([{ ...initial[0], collapsed: true }, initial[1]])
    expect(recorded).toHaveLength(0)

    // 350ms later, the CSS accordion has finished (`b` is now at 40) and
    // the server save echo delivers a fresh array with the same id order.
    tops.set('b', 40)
    setList([{ ...initial[0], collapsed: true }, initial[1]])
    expect(recorded).toHaveLength(0)
  })

  it('does not throw when a removed section has a downstream createMemo reading section()', () => {
    const [list, setList] = createSignal([placement('a'), { ...placement('c'), kind: 'custom' as const, items: ['exposure.iso'] }])
    let rootEl!: HTMLDivElement
    render(() => (
      <div ref={(el: HTMLDivElement) => { rootEl = el }}>
        <AnimatedInspectorStack each={() => list()} root={() => rootEl}>
          {(section) => {
            const itemsMemo = createMemo(() => section().items.join(','))
            return (
              <div data-placement-id={section().id} data-items={itemsMemo()}>
                {section().id}
              </div>
            )
          }}
        </AnimatedInspectorStack>
      </div>
    ))

    expect(() => setList([placement('a')])).not.toThrow()
    expect(Array.from(rootEl.querySelectorAll('[data-placement-id]'), (el) => el.getAttribute('data-placement-id'))).toEqual(['a'])
  })

  it('animates the root container height smoothly when a section is removed or added', () => {
    let rootHeight = 200
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get(this: HTMLElement) {
        return this.dataset?.placementId ? 100 : rootHeight
      },
    })
    try {
      tops.set('a', 0)
      tops.set('b', 100)
      const { setList, root } = mountStack([placement('a'), placement('b')])
      recorded.length = 0
      rootHeight = 100
      setList([placement('a')])
      const rootAnim = recorded.find((entry) => entry.node === root())
      expect(rootAnim?.keyframes).toEqual([{ height: '200px' }, { height: '100px' }])
    } finally {
      delete (HTMLElement.prototype as unknown as { offsetHeight?: unknown }).offsetHeight
    }
  })
})
