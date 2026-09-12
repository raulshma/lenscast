import { onCleanup, onMount } from 'solid-js'
import { isEditableTarget, shortcutFor, type ShortcutAction } from '../lib/shortcuts'

export type ShortcutHandlers = Partial<Record<ShortcutAction, () => void>>

/**
 * The one global keydown listener behind the dashboard's keyboard shortcuts.
 * Every guard rule lives in the pure lib/shortcuts module (editable-target
 * focus, modifier keys, the key→action table); this hook only owns the
 * browser primitive and dispatches to the current handlers.
 */
export function useKeyboardShortcuts(handlers: ShortcutHandlers, options: { enabled?: () => boolean } = {}) {
  function onKeyDown(e: KeyboardEvent) {
    if (options.enabled && !options.enabled()) return
    if (isEditableTarget(e.target as HTMLElement | null)) return
    const action = shortcutFor(e)
    if (!action) return
    const handler = handlers[action]
    if (!handler) return
    e.preventDefault()
    handler()
  }

  onMount(() => {
    document.addEventListener('keydown', onKeyDown)
    onCleanup(() => document.removeEventListener('keydown', onKeyDown))
  })
}
