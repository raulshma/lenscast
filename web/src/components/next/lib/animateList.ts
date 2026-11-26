export const REDUCED_MOTION = '(prefers-reduced-motion: reduce)'

/** Motion tokens, read so JS-driven WAAPI timings match CSS custom properties. */
export function motionToken(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

export function reducedMotion(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia(REDUCED_MOTION).matches
}

interface ChildRect {
  top: number
  left: number
  width: number
  height: number
}

export interface ChildTransitionOptions {
  /** Animate the container's own height between before/after states. */
  animateHeight?: boolean
  /** Duration in ms for entrances, FLIP moves, and height transitions. */
  durationMs?: number
  /** Duration in ms for exit ghost fades. */
  exitDurationMs?: number
  /** When true, skip animations and only refresh measurements. */
  isSuspended?: () => boolean
  /** Invoked when settled child layout changes (e.g. ResizeObserver). */
  onLayoutChange?: () => void
}

function measureChildren(root: HTMLElement): Map<HTMLElement, ChildRect> {
  const map = new Map<HTMLElement, ChildRect>()
  for (const child of Array.from(root.children)) {
    if (!(child instanceof HTMLElement)) continue
    if (child.hasAttribute('data-nx-ghost')) continue
    map.set(child, {
      top: child.offsetTop,
      left: child.offsetLeft,
      width: child.offsetWidth,
      height: child.offsetHeight,
    })
  }
  return map
}

function isGhostNode(node: Node): boolean {
  return node instanceof HTMLElement && node.hasAttribute('data-nx-ghost')
}

/**
 * Animates direct child additions, removals (via a fading static ghost),
 * sibling FLIP moves, and optional container height transitions inside an
 * existing list/body element. Degrades to a no-op when WAAPI,
 * MutationObserver, or motion is unavailable.
 */
export function observeChildTransitions(
  root: HTMLElement,
  options: ChildTransitionOptions = {},
): () => void {
  if (
    typeof MutationObserver === 'undefined'
    || typeof root.animate !== 'function'
    || reducedMotion()
  ) {
    return () => {}
  }

  const duration = options.durationMs ?? 180
  const exitDuration = options.exitDurationMs ?? 140
  let snapshot = measureChildren(root)
  let lastHeight = root.offsetHeight
  let activeAnimations = new Set<Animation>()
  let heightAnimation: Animation | null = null
  let ghosting = false

  const track = (anim: Animation | undefined): Animation | undefined => {
    if (!anim) return undefined
    activeAnimations.add(anim)
    const cleanup = () => { activeAnimations.delete(anim) }
    anim.onfinish = cleanup
    anim.oncancel = cleanup
    return anim
  }

  const cancelActive = () => {
    for (const anim of activeAnimations) anim.cancel()
    activeAnimations.clear()
    if (heightAnimation) {
      heightAnimation.cancel()
      heightAnimation = null
    }
  }

  const spawnGhost = (node: HTMLElement, rect: ChildRect, easing: string) => {
    if (rect.height <= 0 || rect.width <= 0) return
    const ghost = node.cloneNode(true) as HTMLElement
    ghost.setAttribute('data-nx-ghost', 'true')
    ghost.setAttribute('aria-hidden', 'true')
    ghost.style.position = 'absolute'
    ghost.style.top = `${rect.top}px`
    ghost.style.left = `${rect.left}px`
    ghost.style.width = `${rect.width}px`
    ghost.style.height = `${rect.height}px`
    ghost.style.margin = '0'
    ghost.style.pointerEvents = 'none'
    ghost.style.zIndex = '1'
    ghosting = true
    root.appendChild(ghost)
    ghosting = false
    if (typeof ghost.animate !== 'function') {
      ghost.remove()
      return
    }
    const anim = ghost.animate(
      [
        { opacity: 1, transform: 'translateY(0px)' },
        { opacity: 0, transform: 'translateY(-4px)' },
      ],
      { duration: exitDuration, easing, fill: 'forwards' },
    )
    const drop = () => {
      if (ghost.isConnected) {
        ghosting = true
        ghost.remove()
        ghosting = false
      }
    }
    anim.onfinish = drop
    anim.oncancel = drop
    window.setTimeout(drop, exitDuration + 200)
  }

  const mutationObserver = new MutationObserver((records) => {
    if (ghosting) return
    const hasRelevantChange = records.some((record) =>
      Array.from(record.addedNodes).some((node) => node instanceof HTMLElement && !isGhostNode(node))
      || Array.from(record.removedNodes).some((node) => node instanceof HTMLElement && !isGhostNode(node)),
    )
    if (!hasRelevantChange) return

    if (!root.isConnected || options.isSuspended?.() || reducedMotion()) {
      cancelActive()
      snapshot = measureChildren(root)
      lastHeight = root.offsetHeight
      return
    }

    cancelActive()
    const easing = motionToken('--ease-out', 'ease-out')
    const afterHeight = root.offsetHeight
    const current = measureChildren(root)

    for (const [node, rect] of current) {
      const before = snapshot.get(node)
      if (!before) {
        track(node.animate(
          [
            { opacity: 0, transform: 'translateY(6px)' },
            { opacity: 1, transform: 'translateY(0px)' },
          ],
          { duration, easing },
        ))
      } else {
        const shift = before.top - rect.top
        if (Math.abs(shift) >= 1) {
          track(node.animate(
            [
              { transform: `translateY(${shift}px)` },
              { transform: 'translateY(0px)' },
            ],
            { duration, easing },
          ))
        }
      }
    }

    for (const [node, rect] of snapshot) {
      if (!current.has(node)) {
        spawnGhost(node, rect, easing)
      }
    }

    if (options.animateHeight && lastHeight !== afterHeight && (lastHeight > 0 || afterHeight > 0)) {
      const anim = root.animate(
        [{ height: `${lastHeight}px` }, { height: `${afterHeight}px` }],
        { duration, easing },
      )
      heightAnimation = anim
      const clearHeight = () => {
        if (heightAnimation === anim) heightAnimation = null
      }
      anim.onfinish = clearHeight
      anim.oncancel = clearHeight
    }

    snapshot = current
    lastHeight = afterHeight
  })

  mutationObserver.observe(root, { childList: true })

  let resizeObserver: ResizeObserver | undefined
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => {
      if (ghosting || heightAnimation || activeAnimations.size > 0 || options.isSuspended?.()) return
      snapshot = measureChildren(root)
      lastHeight = root.offsetHeight
      options.onLayoutChange?.()
    })
    resizeObserver.observe(root)
  }

  return () => {
    mutationObserver.disconnect()
    resizeObserver?.disconnect()
    cancelActive()
  }
}
