import { createSignal } from 'solid-js'
import type { MaskEditSession } from '../components/next/views/live/masking/maskEditSession'

// Module-level masking-editor store — the pipStore pattern: "the user is
// editing masking zones on the Live stage" is shared state, not one view's.
// The entry points (the Privacy Masking panel's "Edit zones", the Live
// inspector's) arm it from wherever they sit; the Live view answers it by
// creating the editor (maskEditSession.ts) and publishing it here, so every
// other surface — the stage's chrome, the persistent stream host — can tell
// that an edit is running without prop-drilling it.
//
// Arming and owning are two steps on purpose. startMaskEdit only records
// the intent — it does not navigate and creates nothing — so an entry
// point can arm first and route to Live second, and the Live view finds
// the request waiting however long the route change takes.

const [active, setActive] = createSignal(false)

/** True from "Edit zones" until the editor finishes or its owner ends the edit. */
export const maskEditActive = active

const [session, setSession] = createSignal<MaskEditSession | null>(null)

/** The mounted editor's controller; null while no editor is mounted. */
export const maskEditSession = session

const [focusZoneId, setFocusZoneId] = createSignal<string | null>(null)

/** The zone "Edit zones" was clicked for — the editor selects it first. Null when none was named. */
export const maskEditFocusZoneId = focusZoneId

/**
 * Arm the edit: the Live view should open the zone editor over its stage.
 * Names the zone to select first when `zoneId` is given; a new request
 * replaces the previous one's focus. Arms the store only — no navigation.
 */
export function startMaskEdit(zoneId?: string): void {
  setFocusZoneId(zoneId ?? null)
  setActive(true)
}

/** End the edit (the editor's Done, or its owner tearing it down). Leaves the published session to its owner. */
export function endMaskEdit(): void {
  setActive(false)
  setFocusZoneId(null)
}

/** The Live view publishes the editor it created, and clears it on cleanup. */
export function setMaskEditSession(next: MaskEditSession | null): void {
  setSession(next)
}

/** Reset the module singletons between tests. */
export function resetMaskEditStoreForTests(): void {
  setActive(false)
  setSession(null)
  setFocusZoneId(null)
}
