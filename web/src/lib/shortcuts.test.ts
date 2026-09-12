import { describe, expect, it } from 'vitest'
import { isEditableTarget, shortcutFor, SHORTCUT_HELP, type ShortcutAction } from './shortcuts'

describe('shortcutFor', () => {
  it('maps each single key to its action', () => {
    expect(shortcutFor({ key: 'c' })).toBe<ShortcutAction>('capture')
    expect(shortcutFor({ key: 's' })).toBe<ShortcutAction>('snapshot')
    expect(shortcutFor({ key: 'w' })).toBe<ShortcutAction>('toggle-web')
    expect(shortcutFor({ key: 'r' })).toBe<ShortcutAction>('toggle-rtsp')
    expect(shortcutFor({ key: 'p' })).toBe<ShortcutAction>('cycle-player')
    expect(shortcutFor({ key: 'g' })).toBe<ShortcutAction>('gallery')
    expect(shortcutFor({ key: '/' })).toBe<ShortcutAction>('search')
    expect(shortcutFor({ key: '?' })).toBe<ShortcutAction>('help')
  })

  it('is case-insensitive (CapsLock and Shift+letter still fire)', () => {
    expect(shortcutFor({ key: 'G' })).toBe<ShortcutAction>('gallery')
    expect(shortcutFor({ key: 'C' })).toBe<ShortcutAction>('capture')
  })

  it('never fires while ctrl/alt/meta are held — the browser and OS keep those', () => {
    expect(shortcutFor({ key: 'r', ctrlKey: true })).toBeNull()
    expect(shortcutFor({ key: 's', metaKey: true })).toBeNull()
    expect(shortcutFor({ key: 'w', altKey: true })).toBeNull()
    // Shift is allowed: '?' is shift+'/' by construction.
    expect(shortcutFor({ key: '?', shiftKey: true })).toBe<ShortcutAction>('help')
  })

  it('returns null for unmapped keys', () => {
    expect(shortcutFor({ key: 'x' })).toBeNull()
    expect(shortcutFor({ key: 'Escape' })).toBeNull()
    expect(shortcutFor({ key: 'Enter' })).toBeNull()
  })
})

describe('isEditableTarget', () => {
  it('blocks the typing surfaces', () => {
    expect(isEditableTarget({ tagName: 'INPUT' })).toBe(true)
    expect(isEditableTarget({ tagName: 'TEXTAREA' })).toBe(true)
    expect(isEditableTarget({ tagName: 'SELECT' })).toBe(true)
    expect(isEditableTarget({ tagName: 'DIV', isContentEditable: true })).toBe(true)
  })

  it('is case-insensitive on the tag name (SVG elements report lowercase in some engines)', () => {
    expect(isEditableTarget({ tagName: 'input' })).toBe(true)
  })

  it('allows everything else, including missing targets', () => {
    expect(isEditableTarget({ tagName: 'BODY' })).toBe(false)
    expect(isEditableTarget({ tagName: 'BUTTON' })).toBe(false)
    expect(isEditableTarget({ tagName: 'DIV' })).toBe(false)
    expect(isEditableTarget(null)).toBe(false)
    expect(isEditableTarget(undefined)).toBe(false)
  })
})

describe('SHORTCUT_HELP', () => {
  it('covers exactly the actions the key map produces, each with a non-empty label', () => {
    const actions = SHORTCUT_HELP.map((row) => row.action)
    expect(new Set(actions).size).toBe(8)
    for (const row of SHORTCUT_HELP) {
      expect(row.label.length).toBeGreaterThan(0)
      expect(row.key.length).toBeGreaterThan(0)
    }
  })
})
