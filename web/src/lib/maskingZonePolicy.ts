/**
 * Masking-zone geometry for the visual editor — pure math: no Solid, no DOM.
 * The picture layer maps its pixels to normalized points, and everything
 * here runs on those, so the editor's rules vitest without a render.
 *
 * A zone's rectangle is normalized against the web-stream frame: `x` and `y`
 * are the top-left origin, `width` and `height` the size, each in 0..1 — the
 * unit is the PICTURE, never the letterboxed container around it.
 *
 * The frame rule is the editor's own. The device clamps each field on its own
 * (x, y to 0..1; width, height to 0.01..1) and silently clips the overhang,
 * so a rectangle wider than the room it has is legal on the wire. Every
 * rectangle these functions return sits inside the frame: x, y >= 0,
 * x + width and y + height <= 1, and both edges >= ZONE_MIN_SIZE.
 */
import type { MaskingZone } from '../types'

// ── Shapes ───────────────────────────────────────────────────────────

/** A point on the picture, normalized: (0, 0) is the top-left corner, (1, 1) the bottom-right. */
export interface ZonePoint {
  x: number
  y: number
}

/** A zone's rectangle on the picture, normalized, origin top-left. */
export interface ZoneRect {
  x: number
  y: number
  width: number
  height: number
}

/** A box's size in CSS pixels. */
export interface PixelSize {
  width: number
  height: number
}

/** A rectangle in CSS pixels, offset from its container's top-left corner. */
export interface PixelRect extends PixelSize {
  x: number
  y: number
}

/** The resize handles of the selected zone: the four edges and four corners. */
export type ResizeHandle = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw'

/** All eight handles, clockwise from the top edge. */
export const RESIZE_HANDLES: readonly ResizeHandle[] = ['n', 'ne', 'e', 'se', 's', 'sw', 'w', 'nw']

// ── Bounds ───────────────────────────────────────────────────────────

/** The smallest zone edge, as a fraction of the picture — the device's own floor (1%). */
export const ZONE_MIN_SIZE = 0.01

/** A draw drag shorter than this in either dimension is a tap, not a zone. */
export const MIN_DRAW_DRAG = 0.02

/**
 * What counts as "the same number" when a zone is compared with its
 * read-back. The device keeps the rectangle as 32-bit floats, so a stored
 * 0.3 comes back as 0.30000001192092896 — five orders below anything a
 * gesture can resolve, and inside this tolerance.
 */
export const ZONE_NUMBER_TOLERANCE = 1e-4

// Float error guard: 0.7 − 0.68 is 0.01999999999999995, which is a 2% drag
// in every sense except `>= 0.02`.
const FLOAT_EPSILON = 1e-9

function clamp(value: number, min: number, max: number): number {
  return value < min ? min : value > max ? max : value
}

/** A usable number, or `fallback` for NaN / Infinity (a zero-size picture divides to those). */
function finite(value: number, fallback: number): number {
  return Number.isFinite(value) ? value : fallback
}

/** A non-positive or non-finite extent is "no size at all". */
function extent(value: number): number {
  return Number.isFinite(value) && value > 0 ? value : 0
}

/** A coordinate moved to `next`, stopping at the frame's near and far edges. */
function slide(next: number, max: number): number {
  return next < 0 ? 0 : next > max ? max : next
}

function round4(value: number): number {
  return Math.round(value * 10_000) / 10_000
}

function near(a: number, b: number): boolean {
  return Math.abs(a - b) <= ZONE_NUMBER_TOLERANCE
}

// ── Picture fit ──────────────────────────────────────────────────────

/**
 * The `object-fit: contain` rectangle of a media box inside a container
 * box, in CSS px and offset from the container's top-left corner — the
 * picture's own box, which is what zones map onto. The preview's <img>
 * fills its container and letterboxes inside itself, so the container box
 * is the wrong target whenever the two aspect ratios differ: a 16:9 picture
 * in a 4:3 container leaves bars above and below (landscape), a portrait
 * picture in a landscape container leaves them left and right (pillarbox).
 *
 * A container with no size (unmounted, collapsed) yields a 0 × 0 rect at
 * the origin. Unknown media size — 0 × 0, what an <img>/<video> reports
 * before its first frame — yields the whole container box: there is no
 * aspect ratio to honor yet.
 */
export function containRect(container: PixelSize, media: PixelSize): PixelRect {
  const containerWidth = extent(container.width)
  const containerHeight = extent(container.height)
  const mediaWidth = extent(media.width)
  const mediaHeight = extent(media.height)
  if (containerWidth === 0 || containerHeight === 0) return { x: 0, y: 0, width: 0, height: 0 }
  if (mediaWidth === 0 || mediaHeight === 0) {
    return { x: 0, y: 0, width: containerWidth, height: containerHeight }
  }
  // The smaller of the two scales is the one that fits both ways. The axis
  // it belongs to is filled exactly rather than "up to a float ulp", and
  // the spare axis is split evenly (and never exceeds the container when
  // the ratios match).
  const byWidth = containerWidth / mediaWidth
  const byHeight = containerHeight / mediaHeight
  const [width, height] = byWidth <= byHeight
    ? [containerWidth, Math.min(mediaHeight * byWidth, containerHeight)]
    : [Math.min(mediaWidth * byHeight, containerWidth), containerHeight]
  return { x: (containerWidth - width) / 2, y: (containerHeight - height) / 2, width, height }
}

// ── Points and draws ─────────────────────────────────────────────────

/**
 * A pointer position pinned inside the picture, or null when it carries no
 * usable number. A pointer may leave the picture mid-gesture (pointer
 * capture outlives the element); the gesture simply runs on to the edge.
 */
export function clampPoint(point: ZonePoint): ZonePoint | null {
  if (!Number.isFinite(point.x) || !Number.isFinite(point.y)) return null
  return { x: clamp(point.x, 0, 1), y: clamp(point.y, 0, 1) }
}

/**
 * The rectangle a drag between two already-pinned points spans, whichever
 * way it went: right-to-left and bottom-up drags give the same rectangle as
 * their mirrored twins. No size rule — this is the rubber band, shown from
 * the first pixel of movement.
 */
export function dragBounds(from: ZonePoint, to: ZonePoint): ZoneRect {
  return {
    x: Math.min(from.x, to.x),
    y: Math.min(from.y, to.y),
    width: Math.abs(to.x - from.x),
    height: Math.abs(to.y - from.y),
  }
}

/**
 * The zone a rubber-band drag between two normalized points draws, or null
 * when the drag is smaller than 2% in either dimension (a tap, or a slip of
 * the pointer). Both ends are pinned inside the picture first, so a drag
 * that starts or ends off the picture is clipped to it.
 */
export function zoneFromDrag(from: ZonePoint, to: ZonePoint): ZoneRect | null {
  const start = clampPoint(from)
  const end = clampPoint(to)
  if (!start || !end) return null
  const zone = dragBounds(start, end)
  if (zone.width < MIN_DRAW_DRAG - FLOAT_EPSILON || zone.height < MIN_DRAW_DRAG - FLOAT_EPSILON) {
    return null
  }
  return zone
}

// ── The frame rule ───────────────────────────────────────────────────

/**
 * A rectangle brought inside the frame: size to [ZONE_MIN_SIZE, 1], then
 * origin to 0..1. A far edge that overhangs means the rectangle is wider
 * than the room its origin has, so the overhang is cut off — exactly the
 * part the device clips, which keeps the zone where it is seen instead of
 * sliding it back. Whatever is left with less than the minimum slides back
 * until it fits. A NaN or Infinity field lands on a safe value, so the
 * result is always a valid rectangle whatever comes in. A rectangle already
 * inside the frame comes back unchanged.
 *
 * This matters because the panel's percent sliders position a zone with no
 * such rule: x = 90%, width = 30% is a legal stored zone, overhanging by 20%.
 */
export function clampZone(zone: ZoneRect): ZoneRect {
  let width = clamp(finite(zone.width, ZONE_MIN_SIZE), ZONE_MIN_SIZE, 1)
  let height = clamp(finite(zone.height, ZONE_MIN_SIZE), ZONE_MIN_SIZE, 1)
  let x = clamp(finite(zone.x, 0), 0, 1)
  let y = clamp(finite(zone.y, 0), 0, 1)
  if (x + width > 1) width = Math.max(ZONE_MIN_SIZE, 1 - x)
  if (y + height > 1) height = Math.max(ZONE_MIN_SIZE, 1 - y)
  if (x + width > 1) x = 1 - width
  if (y + height > 1) y = 1 - height
  return { x, y, width, height }
}

/**
 * A zone moved by (dx, dy), in picture units. The size never changes; at
 * the frame's edges the zone stops instead of leaving the picture.
 */
export function moveZone(start: ZoneRect, dx: number, dy: number): ZoneRect {
  const zone = clampZone(start)
  return {
    x: slide(zone.x + finite(dx, 0), 1 - zone.width),
    y: slide(zone.y + finite(dy, 0), 1 - zone.height),
    width: zone.width,
    height: zone.height,
  }
}

/**
 * A zone with one edge or corner dragged by (dx, dy), in picture units. The
 * opposite edge or corner stays put, the dragged one stops at the frame and
 * ZONE_MIN_SIZE short of it — an edge can be dragged up to its partner but
 * never past it, so the zone never flips and never shrinks below 1%.
 *
 * `start` is the zone as it was when the gesture began and (dx, dy) the
 * pointer's total travel since then. Every frame of the drag recomputes
 * from that pair, never from the previous frame's result: a cumulative
 * resize drifts and sticks at the clamps, an absolute one tracks the
 * pointer exactly and comes back to the start zone when the pointer does.
 */
export function resizeZone(start: ZoneRect, handle: ResizeHandle, dx: number, dy: number): ZoneRect {
  const zone = clampZone(start)
  const moveX = finite(dx, 0)
  const moveY = finite(dy, 0)
  let left = zone.x
  let top = zone.y
  let right = zone.x + zone.width
  let bottom = zone.y + zone.height
  // The far edges go first: each near edge is bounded by its far partner.
  if (handle.includes('e')) right = clamp(right + moveX, left + ZONE_MIN_SIZE, 1)
  if (handle.includes('s')) bottom = clamp(bottom + moveY, top + ZONE_MIN_SIZE, 1)
  if (handle.includes('w')) left = clamp(left + moveX, 0, right - ZONE_MIN_SIZE)
  if (handle.includes('n')) top = clamp(top + moveY, 0, bottom - ZONE_MIN_SIZE)
  // Clamp last so the subtraction above can never leave the minimum a float ulp short.
  return clampZone({ x: left, y: top, width: right - left, height: bottom - top })
}

/**
 * The keyboard twin of `moveZone`: the same frame rule for a fixed step
 * (the arrow keys' 1%, 10% with Shift). It is the same operation, spelled
 * for the caller that moves a zone a known distance rather than a pointer's.
 */
export function nudgeZone(zone: ZoneRect, dx: number, dy: number): ZoneRect {
  return moveZone(zone, dx, dy)
}

/**
 * A rectangle on the four-decimal grid — finer than a gesture can resolve
 * (0.01% of a 4K frame's width is 0.38 px) and short on the wire. Rounding
 * can carry a far edge one step over the frame; when it does, the
 * width/height lose that step rather than the zone growing past the picture.
 */
export function roundZone(zone: ZoneRect): ZoneRect {
  const inside = clampZone(zone)
  const x = round4(inside.x)
  const y = round4(inside.y)
  return {
    x,
    y,
    width: round4(Math.min(inside.width, 1 - x)),
    height: round4(Math.min(inside.height, 1 - y)),
  }
}

// ── Equality ─────────────────────────────────────────────────────────

/**
 * Two zone lists as the same content: same length, same order, each pair
 * equal field for field. Numbers compare within ZONE_NUMBER_TOLERANCE
 * (the device's float-widened read-back), every other field exactly.
 */
export function zonesEqual(a: readonly MaskingZone[], b: readonly MaskingZone[]): boolean {
  if (a.length !== b.length) return false
  return a.every((zone, index) => sameZone(zone, b[index]))
}

/** Two zones with the same id, label, enabled flag and type, and a rectangle that reads back the same. */
export function sameZone(a: MaskingZone, b: MaskingZone): boolean {
  return (
    a.id === b.id &&
    a.label === b.label &&
    a.enabled === b.enabled &&
    a.type === b.type &&
    sameRect(a, b) &&
    near(a.pixelateSize, b.pixelateSize) &&
    near(a.blurRadius, b.blurRadius)
  )
}

/** Two rectangles whose four fields each differ by less than a rounding step. */
export function sameRect(a: ZoneRect, b: ZoneRect): boolean {
  return near(a.x, b.x) && near(a.y, b.y) && near(a.width, b.width) && near(a.height, b.height)
}

// ── Commit queue ─────────────────────────────────────────────────────

/** A commit queue with at most one write on the wire. */
export interface LatestWinsQueue<T> {
  /**
   * Sends `value`, unless a commit is already in flight: then `value` (or
   * whichever was pushed latest) is held as the one trailing commit and
   * goes out as soon as the in-flight one settles. Returns immediately.
   */
  push(value: T): void
  /** True while a commit is in flight or held behind one. */
  busy(): boolean
  /** Resolves once nothing is in flight or held — immediately when idle. */
  idle(): Promise<void>
  /**
   * Drops a commit still held behind an in-flight one. The in-flight
   * commit is already on the wire and cannot be recalled.
   */
  drop(): void
}

/**
 * The latest-wins commit queue. Every edit of the masking zones wants to be
 * saved, and a drag can end twice a second: sent one by one, the writes
 * queue up and the device applies them in order long after the user has
 * moved on. Here only one is ever in flight; the edits arriving meanwhile
 * collapse into a single trailing commit that carries the latest value —
 * the only one the device needs, since each commit is the whole zone list.
 *
 * A failed commit is reported through `onError` and forgotten: it never
 * blocks the trailing commit, which still carries the user's latest edit.
 * The draft stays local to the editor, so the commit after a failure
 * simply sends that draft again — the retry is the next edit.
 */
export function createLatestWinsQueue<T>(
  commit: (value: T) => Promise<void>,
  onError: (error: unknown) => void = () => {},
): LatestWinsQueue<T> {
  let inFlight = false
  let held: { value: T } | null = null
  let waiters: Array<() => void> = []

  function settle(): void {
    inFlight = false
    if (held) {
      // The trailing commit takes the slot synchronously, so no caller ever
      // observes the queue idle between the two commits.
      const next = held
      held = null
      void send(next.value)
      return
    }
    const done = waiters
    waiters = []
    for (const resolve of done) resolve()
  }

  async function send(value: T) {
    inFlight = true
    try {
      await commit(value)
    } catch (error) {
      // A reporter that throws must not take the trailing commit down with it.
      try {
        onError(error)
      } catch {
        /* the report is best-effort; the queue goes on */
      }
    } finally {
      settle()
    }
  }

  return {
    push(value) {
      if (inFlight) {
        held = { value }
        return
      }
      void send(value)
    },
    busy() {
      return inFlight
    },
    idle() {
      if (!inFlight) return Promise.resolve()
      return new Promise<void>((resolve) => {
        waiters.push(resolve)
      })
    },
    drop() {
      held = null
    },
  }
}
