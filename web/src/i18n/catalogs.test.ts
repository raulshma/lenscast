// Catalog integrity: every non-en catalog's key set must be a subset of en's
// (en is the typed source of truth), no catalog may carry an empty value, and
// en itself must not declare a key twice (a duplicate object literal would
// silently shadow the earlier string). Plural families make the ru subset
// check strict on purpose: en carries all four _one/_few/_many/_other
// suffixes of each family.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { en } from './en'
import { de } from './de'
import { es } from './es'
import { fr } from './fr'
import { ru } from './ru'
import { zh } from './zh'
import { pt } from './pt'

const CATALOGS: Record<string, Partial<Record<string, string>>> = { de, es, fr, ru, zh, pt }

/** Key-name tokens declared in the module source, in order — duplicates show up twice. */
function sourceKeys(file: string): string[] {
  const text = readFileSync(new URL(`./${file}`, import.meta.url), 'utf8')
  return [...text.matchAll(/^\s*'([^']+)':/gm)].map((m) => m[1])
}

describe('catalog integrity', () => {
  it('en has no duplicate keys and no empty values', () => {
    const keys = sourceKeys('en.ts')
    expect(new Set(keys).size).toBe(keys.length)
    for (const [key, value] of Object.entries(en)) {
      expect(value.length, `en['${key}'] is empty`).toBeGreaterThan(0)
    }
  })

  it('every non-en key exists in en (the type union, enforced at runtime too)', () => {
    for (const [name, catalog] of Object.entries(CATALOGS)) {
      const extras = Object.keys(catalog).filter((key) => !(key in en))
      expect(extras, `${name} has keys missing from en`).toEqual([])
    }
  })

  it('no non-en catalog has empty values', () => {
    for (const [name, catalog] of Object.entries(CATALOGS)) {
      for (const [key, value] of Object.entries(catalog)) {
        expect((value ?? '').length, `${name}['${key}'] is empty`).toBeGreaterThan(0)
      }
    }
  })

  it('plural families exist with all four suffixes in en and ru, one/other elsewhere', () => {
    const families = [...new Set(Object.keys(en)
      .filter((k) => /_(one|few|many|other)$/.test(k))
      .map((k) => k.replace(/_(one|few|many|other)$/, '')))]
    expect(families.length).toBeGreaterThan(5)
    for (const base of families) {
      for (const suffix of ['one', 'few', 'many', 'other']) {
        expect(`${base}_${suffix}` in en, `en missing ${base}_${suffix}`).toBe(true)
      }
      expect(`${base}_one` in ru, `ru missing ${base}_one`).toBe(true)
      expect(`${base}_few` in ru, `ru missing ${base}_few`).toBe(true)
      expect(`${base}_many` in ru, `ru missing ${base}_many`).toBe(true)
      expect(`${base}_other` in ru, `ru missing ${base}_other`).toBe(true)
      for (const name of ['de', 'es', 'fr', 'zh', 'pt']) {
        expect(`${base}_one` in CATALOGS[name], `${name} missing ${base}_one`).toBe(true)
        expect(`${base}_other` in CATALOGS[name], `${name} missing ${base}_other`).toBe(true)
      }
    }
  })

  it('zh stays plural-invariant: every plural value equals its _other sibling', () => {
    for (const [key, value] of Object.entries(zh)) {
      if (!/_(one|few|many|other)$/.test(key)) continue
      const other = key.replace(/_(one|few|many|other)$/, '_other')
      expect(value).toBe(zh[other])
    }
  })
})
