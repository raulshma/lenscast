// Dashboard i18n — the pollLadder pattern adapted to a locale: the current
// locale is one module-level Solid signal, `t()` reads it (so any JSX that
// calls t() re-renders on switch), and the catalogs are plain Record objects
// in src/i18n (en is the typed source of truth, every other locale is Partial
// with en fallback at lookup — a missing key can never crash, it shows the
// English string). Plural handling is deliberately ICU-lite: `tCount(base, n)`
// picks a `_one/_few/_many/_other` suffix (ru uses all four, fr 0/1→one, zh
// always `_other`, the rest n===1→one) and falls back `_other` → bare key, in
// the current locale first, then en.
import { createSignal } from 'solid-js'
import { en } from '../i18n/en'
import { de } from '../i18n/de'
import { es } from '../i18n/es'
import { fr } from '../i18n/fr'
import { ru } from '../i18n/ru'
import { zh } from '../i18n/zh'
import { pt } from '../i18n/pt'

export type Locale = 'en' | 'de' | 'es' | 'fr' | 'ru' | 'zh' | 'pt'

export const LOCALES: readonly Locale[] = ['en', 'de', 'es', 'fr', 'ru', 'zh', 'pt']

/** Native display names for the switcher — never translated. */
export const LOCALE_DISPLAY_NAMES: Record<Locale, string> = {
  en: 'English',
  de: 'Deutsch',
  es: 'Español',
  fr: 'Français',
  ru: 'Русский',
  zh: '简体中文',
  pt: 'Português (BR)',
}

/** BCP 47 tags for Intl formatting per supported locale. */
const BCP47_TAGS: Record<Locale, string> = {
  en: 'en',
  de: 'de',
  es: 'es',
  fr: 'fr',
  ru: 'ru',
  zh: 'zh-CN',
  pt: 'pt-BR',
}

const CATALOGS: Record<Locale, Partial<Record<string, string>>> = { en, de, es, fr, ru, zh, pt }

const STORAGE_KEY = 'lenscast-locale'

/** Map one navigator language tag onto a supported locale (exact, then prefix). */
export function matchLocale(tag: string): Locale | null {
  const lower = tag.toLowerCase()
  for (const locale of LOCALES) {
    if (lower === locale) return locale
  }
  const prefix = lower.split('-')[0]
  for (const locale of LOCALES) {
    if (prefix === locale) return locale
  }
  return null
}

/** localStorage → navigator.languages (prefix match) → en. Safe without a DOM. */
export function detectLocale(): Locale {
  try {
    const stored = typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null
    if (stored && (LOCALES as readonly string[]).includes(stored)) return stored as Locale
  } catch {
    // Private-mode storage: fall through to navigator detection.
  }
  const candidates: readonly string[] =
    typeof navigator !== 'undefined' && Array.isArray(navigator.languages) ? navigator.languages : []
  for (const tag of candidates) {
    const matched = matchLocale(tag)
    if (matched) return matched
  }
  return 'en'
}

const [locale, setLocaleSignal] = createSignal<Locale>(detectLocale())

/** The reactive current locale — read inside components (t() already does). */
export { locale }

/** Persist the choice and update the document lang attribute. */
export function setLocale(next: Locale): void {
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // Private-mode storage: the signal still flips for this session.
  }
  setLocaleSignal(next)
  if (typeof document !== 'undefined') document.documentElement.lang = BCP47_TAGS[next]
}

/** The Intl locale tag for the current locale (zh → zh-CN, pt → pt-BR). */
export function localeTag(): string {
  return BCP47_TAGS[locale()]
}

export type TParams = Record<string, string | number>

/** `{name}` interpolation — unknown placeholders pass through untouched. */
function interpolate(template: string, params?: TParams): string {
  if (!params) return template
  return template.replace(/\{(\w+)\}/g, (match, name: string) =>
    name in params ? String(params[name]) : match,
  )
}

/** Translate one key (current locale → en fallback → the key itself). */
export function t(key: string, params?: TParams): string {
  const template = CATALOGS[locale()][key] ?? en[key]
  return template === undefined ? key : interpolate(template, params)
}

export type PluralCategory = 'one' | 'few' | 'many' | 'other'

/** Pragmatic CLDR-lite category pick: ru four-way, fr 0/1, zh invariant, rest n===1. */
export function pluralCategory(count: number, forLocale: Locale = locale()): PluralCategory {
  const n = Math.abs(count)
  switch (forLocale) {
    case 'zh':
      return 'other'
    case 'ru': {
      const mod10 = n % 10
      const mod100 = n % 100
      if (mod10 === 1 && mod100 !== 11) return 'one'
      if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'few'
      return 'many'
    }
    case 'fr':
      return n <= 1 ? 'one' : 'other'
    default:
      return n === 1 ? 'one' : 'other'
  }
}

/**
 * Plural lookup over `${base}_${category}`: the exact category first, then
 * `_other`, then the bare key — the current locale wins over en at every rung
 * so a partially translated plural never mixes languages.
 */
export function tCount(baseKey: string, count: number, params?: TParams): string {
  const category = pluralCategory(count)
  const candidates = [`${baseKey}_${category}`, `${baseKey}_other`, baseKey]
  const merged: TParams = { ...params, count }
  const table = CATALOGS[locale()]
  for (const key of candidates) {
    const template = table[key]
    if (template !== undefined) return interpolate(template, merged)
  }
  for (const key of candidates) {
    const template = en[key]
    if (template !== undefined) return interpolate(template, merged)
  }
  return candidates[0]
}

// ── Intl formatting with the current locale ──
// Lightweight helpers for the date/time call sites that show localized dates
// (gallery day groups, event times, sessions, audit rows). Pass the same
// options the previous toLocaleDateString(undefined, …) calls used.

export function formatDateTime(value: Date | number, options?: Intl.DateTimeFormatOptions): string {
  return new Intl.DateTimeFormat(localeTag(), options).format(value)
}

export function formatDate(value: Date | number, options?: Intl.DateTimeFormatOptions): string {
  return new Intl.DateTimeFormat(localeTag(), options).format(value)
}

export function formatTime(value: Date | number, options?: Intl.DateTimeFormatOptions): string {
  return new Intl.DateTimeFormat(localeTag(), options).format(value)
}

/**
 * Localized gallery day-group label: gallery/groupByDay keeps returning its
 * stable English tokens (pinned by its tests); the UI layer maps those two
 * tokens and passes locale dates through.
 */
export function dayGroupLabel(label: string): string {
  if (label === 'Today') return t('gallery.today')
  if (label === 'Yesterday') return t('gallery.yesterday')
  return label
}
