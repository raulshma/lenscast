import { createEffect, onCleanup, Show } from 'solid-js'
import { For } from 'solid-js'
import { SHORTCUT_HELP } from '../lib/shortcuts'
import { t } from '../lib/i18n'

/**
 * The keyboard-shortcut help overlay ('?' or the navbar button). A modal twin
 * of the gallery viewer styling: Escape or backdrop click closes, and the
 * rows render from the pure SHORTCUT_HELP table so the overlay can never
 * drift from lib/shortcuts' key map.
 */
export default function ShortcutsOverlay(props: { onClose: () => void }) {
  function handleKeyDown(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      e.preventDefault()
      props.onClose()
    }
  }

  createEffect(() => {
    document.addEventListener('keydown', handleKeyDown)
    onCleanup(() => document.removeEventListener('keydown', handleKeyDown))
  })

  return (
    <div class="gallery-overlay" onClick={props.onClose}>
      <div class="shortcuts-modal" onClick={(e) => e.stopPropagation()}>
        <div class="gallery-header">
          <h2>{t('shortcuts.title')}</h2>
          <button class="navbar-icon-btn" onClick={props.onClose} title={t('common.close')} aria-label={t('shortcuts.closeAria')}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>
        <div class="shortcuts-list" role="table" aria-label={t('shortcuts.title')}>
          <For each={SHORTCUT_HELP}>
            {(row) => (
              <div class="shortcuts-row" role="row">
                <kbd class="shortcuts-key">{row.key}</kbd>
                <span class="shortcuts-label">{t(`shortcuts.action.${row.action}`)}</span>
              </div>
            )}
          </For>
          <div class="shortcuts-row" role="row">
            <kbd class="shortcuts-key">Esc</kbd>
            <span class="shortcuts-label">{t('shortcuts.closeDialogs')}</span>
          </div>
        </div>
        <Show when={typeof navigator !== 'undefined' && !('ontouchstart' in navigator)}>
          <p class="shortcuts-hint">{t('shortcuts.hint')}</p>
        </Show>
      </div>
    </div>
  )
}
