// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render } from '@solidjs/testing-library'
import { createSignal } from 'solid-js'
import { useZoomable } from './useZoomable'

// The zoom gesture lock (`enabled`): while another surface owns the picture
// — the masking-zone editor draws on it — every gesture handler returns
// before its preventDefault, so the wheel and a touch scroll the page as if
// the listeners were not there, and nothing pans or zooms. The option is
// read at event time, so flipping it is enough; absent, nothing changes (the
// classic shell's contract). jsdom lays nothing out, so the box's rect is
// the usual 0 × 0: the zoom math runs on it, which is all these need.

afterEach(() => cleanup())

type Zoom = ReturnType<typeof useZoomable>

/** A box wired to the hook exactly as the preview container is. */
function mountZoom(options: { enabled?: () => boolean } = {}) {
  let zoom!: Zoom
  const view = render(() => {
    zoom = useZoomable({ wheelZoomFactor: 0.1, ...options })
    return <div data-testid="box" ref={zoom.containerRef} />
  })
  const box = view.getByTestId('box') as HTMLElement
  // The pan clamp reads the box's client size, which jsdom never lays out:
  // at scale 1.1 a 200 × 100 box has 10px / 5px of travel per axis.
  Object.defineProperty(box, 'clientWidth', { value: 200, configurable: true })
  Object.defineProperty(box, 'clientHeight', { value: 100, configurable: true })
  return { zoom: () => zoom, box: () => box }
}

/** A cancelable event dispatched on the box; `defaultPrevented` is the observable. */
function fire<T extends Event>(target: Element, event: T): T {
  target.dispatchEvent(event)
  return event
}

const wheel = (target: Element) => fire(target, new WheelEvent('wheel', { deltaY: -100, bubbles: true, cancelable: true }))
const mouse = (target: Element, type: string, init: MouseEventInit = {}) =>
  fire(target, new MouseEvent(type, { button: 0, bubbles: true, cancelable: true, ...init }))

describe('useZoomable gesture lock', () => {
  it('a locked wheel neither zooms nor prevents default', () => {
    const { zoom, box } = mountZoom({ enabled: () => false })
    const event = wheel(box())
    expect(zoom().scale()).toBe(1)
    expect(zoom().isZoomed()).toBe(false)
    expect(event.defaultPrevented).toBe(false)
  })

  it('the lock is read at event time: unlocking lets the same wheel zoom, re-locking makes it inert', () => {
    const [enabled, setEnabled] = createSignal(false)
    const { zoom, box } = mountZoom({ enabled })
    setEnabled(true)
    expect(wheel(box()).defaultPrevented).toBe(true)
    expect(zoom().scale()).toBeCloseTo(1.1, 12)
    // Re-locking does not touch the scale: the caller owns resetting it.
    setEnabled(false)
    const locked = wheel(box())
    expect(locked.defaultPrevented).toBe(false)
    expect(zoom().scale()).toBeCloseTo(1.1, 12)
  })

  it('a lock that falls mid-drag drops the pan: the drag stops, and after unlocking the pointer does not drag on', () => {
    const [enabled, setEnabled] = createSignal(true)
    const { zoom, box } = mountZoom({ enabled })
    wheel(box()) // zoomed in, so a drag pans
    mouse(box(), 'mousedown', { clientX: 10, clientY: 10 })
    mouse(box(), 'mousemove', { clientX: 15, clientY: 12 })
    expect(zoom().translateX()).toBe(5)
    expect(zoom().translateY()).toBe(2)

    // Locked mid-drag: the drag stops where it was and its events are left alone.
    setEnabled(false)
    expect(mouse(box(), 'mousemove', { clientX: 60, clientY: 50 }).defaultPrevented).toBe(false)
    mouse(box(), 'mouseup', { clientX: 60, clientY: 50 })
    expect(zoom().translateX()).toBe(5)
    expect(zoom().translateY()).toBe(2)

    // Unlocked again, the pointer moves with no button held: the press the
    // lock swallowed must not come back to life as a drag.
    setEnabled(true)
    mouse(box(), 'mousemove', { clientX: 0, clientY: 0 })
    expect(zoom().translateX()).toBe(5)
    expect(zoom().translateY()).toBe(2)

    // And a locked double-click is left to the browser too.
    setEnabled(false)
    expect(mouse(box(), 'dblclick').defaultPrevented).toBe(false)
    expect(zoom().scale()).toBeCloseTo(1.1, 12)
  })

  it('without the option the wheel zooms and prevents default, as it always has', () => {
    const { zoom, box } = mountZoom()
    expect(wheel(box()).defaultPrevented).toBe(true)
    expect(zoom().scale()).toBeCloseTo(1.1, 12)
    expect(zoom().isZoomed()).toBe(true)
  })
})
