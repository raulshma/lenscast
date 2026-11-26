import { For, createEffect, createMemo, on, onCleanup, type Accessor, type JSX } from 'solid-js'
import type { WebUiSectionPlacementDto } from '../../../types'
import { motionToken, reducedMotion } from '../lib/animateList'

interface Props {
  /** The ordered, visible section placements — one frame per entry. */
  each: () => readonly WebUiSectionPlacementDto[]
  /** The list container the frames sit in: measuring stick for the moves
      (frames' offsetParent) and the host the exit ghosts anchor to. */
  root: () => HTMLElement | undefined
  /** Renders one frame. `section` is a live accessor over the placement
      with this row's id — edits (collapse, hidden items, title) update the
      existing frame in place; only a new id mounts a frame. */
  children: (section: Accessor<WebUiSectionPlacementDto>) => JSX.Element
}

interface FrameState {
  node: HTMLElement
  /** Layout top within the root — `offsetTop`, so a FLIP animation still
      in flight (transforms) can never pollute the next measurement. */
  top: number
  height: number
}

function sameIdOrder(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((id, index) => id === b[index])
}

/**
 * The inspector column's section stack, and the keeper of its stability
 * contract: frames are keyed by placement id — never by the placement
 * object — so an edit that rewrites one section's document does not
 * remount the column (Solid's `<For>` reconciles by reference, and a
 * reference-keyed row would tear down and re-fetch every panel on every
 * layout write). Children therefore receive an id's live placement
 * accessor, not a snapshot.
 *
 * On top of that stability, membership and order changes animate:
 * - Kept frames whose row moved: FLIP — one transform from the previous
 *   offsetTop, settled with the strong ease-out, compositor only.
 * - Added frames: fade-and-settle on mount (opacity, 8px rise).
 * - Removed frames: the node Solid just dropped is ghosted — a static
 *   clone pinned where the row stood, fading out while the kept frames
 *   and container height settle smoothly into the freed space.
 *
 * In-place edits (collapse/expand, hidden items, title edits, save echoes
 * bumping updatedAtMs) keep the exact same id order and never trigger FLIP.
 * Everything degrades silently: no WAAPI, a reduced-motion preference, or
 * a detached root simply leaves the plain keyed `<For>`.
 */
export default function AnimatedInspectorStack(props: Props) {
  let primed = false
  let frames = new Map<string, FrameState>()
  let previousOrder: string[] = []
  let previousHeight = 0
  let rootHeightAnim: Animation | null = null
  const activeAnimations = new Set<Animation>()

  // The rows are ids — stable across content edits — so `<For>` keeps a
  // frame's DOM until its placement enters or leaves the column, and
  // slides it on reorder. Placement lookups resolve live per read.
  const order = createMemo(() => props.each().map((section) => section.id))

  const cancelActive = () => {
    for (const anim of activeAnimations) anim.cancel()
    activeAnimations.clear()
    if (rootHeightAnim) {
      rootHeightAnim.cancel()
      rootHeightAnim = null
    }
  }

  const track = (anim: Animation | undefined): void => {
    if (!anim) return
    activeAnimations.add(anim)
    const done = () => { activeAnimations.delete(anim) }
    anim.onfinish = done
    anim.oncancel = done
  }

  const measureFrames = (root: HTMLElement): Map<string, FrameState> => {
    const measured = new Map<string, FrameState>()
    // Direct children only: an exit ghost still fading in the root must
    // never be measured as a live frame.
    for (const node of root.querySelectorAll<HTMLElement>(':scope > [data-placement-id]')) {
      measured.set(node.dataset.placementId!, { node, top: node.offsetTop, height: node.offsetHeight })
    }
    return measured
  }

  const refreshSettled = (root: HTMLElement): void => {
    if (!primed || !root.isConnected || rootHeightAnim || activeAnimations.size > 0) return
    if (root.closest('.nx-inspector-collapsed')) return
    frames = measureFrames(root)
    previousHeight = root.offsetHeight
  }

  const ghostOut = (root: HTMLElement, state: FrameState): void => {
    const ghost = state.node.cloneNode(true) as HTMLElement
    ghost.removeAttribute('data-placement-id')
    ghost.setAttribute('data-nx-ghost', 'true')
    ghost.setAttribute('aria-hidden', 'true')
    ghost.style.position = 'absolute'
    ghost.style.top = `${state.top}px`
    ghost.style.left = '0'
    ghost.style.right = '0'
    ghost.style.height = `${state.height}px`
    ghost.style.pointerEvents = 'none'
    ghost.style.zIndex = '1'
    root.appendChild(ghost)
    // No WAAPI (jsdom, old engines): the ghost would never fade — drop it
    // immediately rather than leave a stuck clone in the list.
    if (typeof ghost.animate !== 'function') {
      ghost.remove()
      return
    }
    const fade = ghost.animate(
      [{ opacity: 1 }, { opacity: 0 }],
      { duration: 160, easing: motionToken('--ease-out', 'ease-out'), fill: 'forwards' },
    )
    const drop = () => ghost.remove()
    fade.onfinish = drop
    fade.oncancel = drop
    window.setTimeout(drop, 500)
  }

  // Keep layout snapshots current once CSS accordion folds or async panel
  // content resizes settle, so a subsequent removal/reorder FLIPs from true
  // positions without ever re-animating the fold itself.
  createEffect(() => {
    const root = props.root()
    if (!root) return
    const onTransitionEnd = (event: TransitionEvent) => {
      if (event.propertyName === 'grid-template-rows') refreshSettled(root)
    }
    root.addEventListener('transitionend', onTransitionEnd)
    let resizeObserver: ResizeObserver | undefined
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => refreshSettled(root))
      resizeObserver.observe(root)
    }
    onCleanup(() => {
      root.removeEventListener('transitionend', onTransitionEnd)
      resizeObserver?.disconnect()
    })
  })

  // Runs after every list change (Solid settles the rows before user
  // effects). Animates ONLY when membership or order changed; in-place
  // edits (collapse, hidden items, updatedAtMs save echo) refresh
  // measurements quietly without triggering FLIP.
  createEffect(on(() => props.each(), () => {
    const root = props.root()
    const nextOrder = order()
    if (!root || !root.isConnected || reducedMotion()) {
      cancelActive()
      primed = false
      frames = new Map()
      previousOrder = []
      previousHeight = 0
      return
    }

    if (!primed) {
      primed = true
      previousOrder = nextOrder
      frames = measureFrames(root)
      previousHeight = root.offsetHeight
      return
    }

    if (sameIdOrder(previousOrder, nextOrder)) {
      frames = measureFrames(root)
      previousHeight = root.offsetHeight
      return
    }

    cancelActive()
    const easing = motionToken('--ease-out', 'ease-out')
    const animate = (node: HTMLElement, keyframes: Keyframe[], duration: number): void => {
      // jsdom and older engines carry no WAAPI — the plain list is the
      // degradation, not a broken animation.
      track(node.animate?.(keyframes, { duration, easing }))
    }

    const previous = frames
    const beforeHeight = previousHeight
    const current = measureFrames(root)
    const afterHeight = root.offsetHeight

    for (const [id, state] of current) {
      const before = previous.get(id)
      if (!before) {
        // Added: settle in — opacity carries the arrival, the rise is small
        // enough to stay near-imperceptible on an editing surface.
        animate(state.node, [
          { opacity: 0, transform: 'translateY(8px)' },
          { opacity: 1, transform: 'translateY(0px)' },
        ], 220)
      } else {
        const shift = before.top - state.top
        if (Math.abs(shift) >= 1) {
          animate(state.node, [
            { transform: `translateY(${shift}px)` },
            { transform: 'translateY(0px)' },
          ], 220)
        }
      }
    }
    for (const [id, state] of previous) {
      // Solid has already detached these — the ghost is a static clone
      // of the dropped node, which cloneNode takes from fine.
      if (!current.has(id)) ghostOut(root, state)
    }

    if (beforeHeight !== afterHeight && (beforeHeight > 0 || afterHeight > 0) && typeof root.animate === 'function') {
      const anim = root.animate(
        [{ height: `${beforeHeight}px` }, { height: `${afterHeight}px` }],
        { duration: 220, easing },
      )
      rootHeightAnim = anim
      const clear = () => {
        if (rootHeightAnim === anim) rootHeightAnim = null
      }
      anim.onfinish = clear
      anim.oncancel = clear
    }

    previousOrder = nextOrder
    frames = current
    previousHeight = afterHeight
  }))

  onCleanup(cancelActive)

  return (
    <For each={order()}>
      {(id) => {
        // Cache the last seen placement so any downstream memo reading section()
        // during the same update pass in which `id` was removed never throws
        // before `<For>` disposes this row.
        let last: WebUiSectionPlacementDto | undefined
        return props.children(() => {
          const found = props.each().find((entry) => entry.id === id)
          if (found) {
            last = found
            return found
          }
          if (last) return last
          throw new Error(`inspector placement ${id} left the layout while mounted`)
        })
      }}
    </For>
  )
}
