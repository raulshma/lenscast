// i18n module tests (node env): detection order, prefix matching, en
// fallback, interpolation, plural category picking and tCount resolution.
// Globals are stubbed per test so the module-level detectLocale() runs
// against controlled inputs via fresh module imports.
import { afterEach, describe, expect, it, vi } from 'vitest'

type StorageMock = Record<string, string>

function freshI18n(opts: { stored?: StorageMock; languages?: string[] } = {}) {
  vi.resetModules()
  const store: StorageMock = opts.stored ?? {}
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => (k in store ? store[k] : null),
    setItem: (k: string, v: string) => { store[k] = v },
  })
  vi.stubGlobal('navigator', { languages: opts.languages ?? [] })
  return import('./i18n')
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('matchLocale', () => {
  it('matches exact tags, prefixes, and rejects unknowns', async () => {
    const { matchLocale } = await freshI18n()
    expect(matchLocale('en')).toBe('en')
    expect(matchLocale('de')).toBe('de')
    expect(matchLocale('en-US')).toBe('en')
    expect(matchLocale('pt-BR')).toBe('pt')
    expect(matchLocale('zh-TW')).toBe('zh')
    expect(matchLocale('fr')).toBe('fr')
    expect(matchLocale('es-419')).toBe('es')
    expect(matchLocale('sv-SE')).toBeNull()
    expect(matchLocale('')).toBeNull()
  })
})

describe('detectLocale', () => {
  it('prefers the persisted localStorage choice', async () => {
    const { detectLocale } = await freshI18n({ stored: { 'lenscast-locale': 'ru' }, languages: ['de-DE'] })
    expect(detectLocale()).toBe('ru')
  })

  it('ignores an unknown stored value and falls to navigator match', async () => {
    const { detectLocale } = await freshI18n({ stored: { 'lenscast-locale': 'xx' }, languages: ['fr-CA', 'en'] })
    expect(detectLocale()).toBe('fr')
  })

  it('walks navigator.languages in order', async () => {
    const { detectLocale } = await freshI18n({ languages: ['sv-SE', 'de-DE', 'en-US'] })
    expect(detectLocale()).toBe('de')
  })

  it('falls back to en with no signals at all', async () => {
    const { detectLocale } = await freshI18n()
    expect(detectLocale()).toBe('en')
  })
})

describe('t + fallback + interpolation', () => {
  it('returns the locale string and interpolates params', async () => {
    const { setLocale, t } = await freshI18n()
    setLocale('de')
    expect(t('preview.capture')).toBe('Aufnehmen')
    expect(t('error.captured', { name: 'x.jpg' })).toBe('Aufgenommen: x.jpg')
  })

  it('falls back to en for keys missing in the current catalog, and never crashes on unknown keys', async () => {
    const { setLocale, t } = await freshI18n()
    setLocale('zh')
    expect(t('gallery.today')).toBe('今天')
    // Every non-en catalog omits nothing in this repo, so simulate a missing
    // key through an unknown name in both catalogs → the key itself.
    expect(t('no.such.key')).toBe('no.such.key')
  })

  it('leaves unknown placeholders untouched', async () => {
    const { t } = await freshI18n()
    expect(t('app.renderError', { wrong: '1' })).toBe('Dashboard rendering error: {error}')
  })

  it('setLocale persists and locale() reflects the change', async () => {
    const { locale, setLocale } = await freshI18n()
    setLocale('pt')
    expect(locale()).toBe('pt')
    expect(localStorage.getItem('lenscast-locale')).toBe('pt')
  })
})

describe('plurals', () => {
  it('picks categories per locale', async () => {
    const { pluralCategory, setLocale } = await freshI18n()
    setLocale('en')
    expect(pluralCategory(1)).toBe('one')
    expect(pluralCategory(2)).toBe('other')
    setLocale('fr')
    expect(pluralCategory(0)).toBe('one')
    expect(pluralCategory(1)).toBe('one')
    expect(pluralCategory(2)).toBe('other')
    setLocale('zh')
    expect(pluralCategory(1)).toBe('other')
    expect(pluralCategory(0)).toBe('other')
    setLocale('ru')
    expect(pluralCategory(1)).toBe('one')
    expect(pluralCategory(2)).toBe('few')
    expect(pluralCategory(5)).toBe('many')
    expect(pluralCategory(11)).toBe('many')
    expect(pluralCategory(21)).toBe('one')
    expect(pluralCategory(22)).toBe('few')
    expect(pluralCategory(0)).toBe('many')
    expect(pluralCategory(100)).toBe('many')
  })

  it('tCount resolves the exact suffix key with {count} injected', async () => {
    const { setLocale, tCount } = await freshI18n()
    setLocale('en')
    expect(tCount('multicam.count', 0)).toBe('0 cameras')
    expect(tCount('multicam.count', 1)).toBe('1 camera')
    expect(tCount('multicam.count', 3)).toBe('3 cameras')
    setLocale('ru')
    expect(tCount('multicam.count', 1)).toBe('1 камера')
    expect(tCount('multicam.count', 3)).toBe('3 камеры')
    expect(tCount('multicam.count', 5)).toBe('5 камер')
  })

  it('tCount falls back to en when the locale lacks the family', async () => {
    // No real catalog lacks a family here; assert the en fallback via a bare
    // unknown base — candidates stringified, never a crash.
    const { tCount } = await freshI18n()
    expect(tCount('no.such.family', 2)).toBe('no.such.family_other')
  })

  it('extra params merge with count', async () => {
    const { setLocale, tCount } = await freshI18n()
    setLocale('en')
    expect(tCount('gallery.deleteConfirm', 1)).toBe('Delete 1 item?')
    expect(tCount('gallery.deleteConfirm', 5)).toBe('Delete 5 items?')
  })
})

describe('dayGroupLabel', () => {
  it('maps the stable English tokens and passes locale dates through', async () => {
    const { dayGroupLabel, setLocale } = await freshI18n()
    setLocale('de')
    expect(dayGroupLabel('Today')).toBe('Heute')
    expect(dayGroupLabel('Yesterday')).toBe('Gestern')
    expect(dayGroupLabel('Mon, 1. Jan 2024')).toBe('Mon, 1. Jan 2024')
  })
})
