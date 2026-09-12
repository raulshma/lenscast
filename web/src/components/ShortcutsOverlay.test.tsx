// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, fireEvent } from '@solidjs/testing-library'
import ShortcutsOverlay from './ShortcutsOverlay'
import { SHORTCUT_HELP } from '../lib/shortcuts'

afterEach(() => cleanup())

describe('ShortcutsOverlay', () => {
  it('lists every shortcut from the pure help table plus the Escape row', () => {
    render(() => <ShortcutsOverlay onClose={() => { }} />)
    expect(screen.getByText('Keyboard shortcuts')).toBeTruthy()
    for (const row of SHORTCUT_HELP) {
      expect(screen.getByText(row.label)).toBeTruthy()
    }
    expect(screen.getByText('Close dialogs')).toBeTruthy()
  })

  it('Escape closes the overlay', () => {
    const onClose = vi.fn()
    render(() => <ShortcutsOverlay onClose={onClose} />)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('the close button and a backdrop click close the overlay', () => {
    const onClose = vi.fn()
    const { container } = render(() => <ShortcutsOverlay onClose={onClose} />)
    fireEvent.click(screen.getByTitle('Close'))
    expect(onClose).toHaveBeenCalledTimes(1)
    // The backdrop is the overlay root itself (the modal stops propagation).
    fireEvent.click(container.firstElementChild!)
    expect(onClose).toHaveBeenCalledTimes(2)
    expect(vi.mocked(onClose).mock.calls.length).toBe(2)
  })
})
