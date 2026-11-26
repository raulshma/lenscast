// The beta console speaks one tooltip dialect: interactive controls carry
// `data-tooltip` (tooltip.css renders the bubble), never a native `title`.
// The kit half is enforced by types — Button has no `title` prop, IconButton
// defaults the bubble to its label — but plain elements have no compiler
// gate, and the manual grep audit that preceded this test let an interactive
// native title through (the inspector FAB). So the dialect is pinned here,
// parsed as real JSX rather than text-grepped, because `title=` is also a
// heading prop on Panel/Drawer/ConfirmDialog/Empty and must not
// false-positive. Native titles survive exactly where the dialect documents
// them (see the tooltip.css header and CONTEXT.md "Web UI Tooltip"): form
// controls with no ::after, self-clipping truncation hints on
// non-interactive text, and the status-strip chips. A new exception is a
// human decision — add it here with its reason, not silently in a component.
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse } from '@babel/parser'
import { describe, expect, it } from 'vitest'

const NEXT_ROOT = dirname(fileURLToPath(import.meta.url))

/** File (relative to next/) → how many native titles the dialect documents there, and why. */
const DOCUMENTED_NATIVE_TITLES: Record<string, { count: number; why: string }> = {
  'NextShell.tsx': {
    count: 4,
    why: 'the language <select> (form controls have no ::after), the side-rec elapsed-timer readout, and the status-strip chips (.nx-strip-chips clips the bubble both ways)',
  },
  'views/MediaView.tsx': {
    count: 1,
    why: 'the ellipsized tile-name filename readout (non-interactive)',
  },
  'views/live/ClientsPanel.tsx': {
    count: 1,
    why: 'the ellipsized client id readout (non-interactive)',
  },
  'views/live/CameraStrip.tsx': {
    count: 1,
    why: 'the cam-strip exposure readout (non-interactive)',
  },
  'views/detection/StatsPanel.tsx': {
    count: 1,
    why: 'the chart bar event-count hint (non-interactive)',
  },
  'views/detection/SecurityPanel.tsx': {
    count: 1,
    why: 'the mask-zone rect readout (non-interactive; the label rides the title)',
  },
  'views/system/cards/AuditPanel.tsx': {
    count: 2,
    why: 'the ellipsized audit action and detail spans (non-interactive)',
  },
  'views/system/cards/AuthPanel.tsx': {
    count: 1,
    why: 'the ellipsized auth-session prefix readout (non-interactive)',
  },
  'views/system/cards/MultiCamPanel.tsx': {
    count: 1,
    why: 'the ellipsized multicam tile name (non-interactive; the baseUrl rides the title)',
  },
  'views/system/cards/RecordingTimelinePanel.tsx': {
    count: 2,
    why: 'the absolutely-placed timeline segment and marker divs (non-interactive hover hints)',
  },
  'views/system/cards/SharePanel.tsx': {
    count: 1,
    why: 'the share QR wrap hint (non-interactive)',
  },
  'views/system/cards/UnifiedPushPanel.tsx': {
    count: 1,
    why: 'the ellipsized UnifiedPush endpoint readout (non-interactive)',
  },
}

function* walkTsx(dir: string): Generator<string> {
  for (const entry of readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) yield* walkTsx(path)
    else if (entry.name.endsWith('.tsx') && !entry.name.endsWith('.test.tsx')) yield path
  }
}

interface BabelNode {
  type?: string
  name?: unknown
  loc?: { start?: { line?: number } }
  [key: string]: unknown
}

/** Intrinsic-element `title` attributes — component props (Panel headings etc.) never match. */
function intrinsicNativeTitles(source: string): { line: number; tag: string }[] {
  const ast = parse(source, { sourceType: 'module', plugins: ['typescript', 'jsx'] })
  const hits: { line: number; tag: string }[] = []
  const visit = (node: unknown, parent: BabelNode | null): void => {
    if (!node || typeof node !== 'object') return
    if (Array.isArray(node)) {
      for (const item of node) visit(item, parent)
      return
    }
    const record = node as BabelNode
    if (record.type === 'JSXAttribute' && parent?.type === 'JSXOpeningElement') {
      const attrName = record.name as BabelNode | undefined
      const tagName = parent.name as BabelNode | undefined
      const isPlainTitle = attrName?.type === 'JSXIdentifier' && attrName.name === 'title'
      const intrinsic =
        (tagName?.type === 'JSXIdentifier' && typeof tagName.name === 'string' && /^[a-z]/.test(tagName.name)) ||
        tagName?.type === 'JSXNamespacedName'
      if (isPlainTitle && intrinsic) {
        hits.push({ line: record.loc?.start?.line ?? 0, tag: String((tagName as { name?: string }).name) })
      }
    }
    for (const [key, value] of Object.entries(record)) {
      if (key === 'loc' || key === 'leadingComments' || key === 'trailingComments' || key === 'innerComments') continue
      visit(value, record)
    }
  }
  visit(ast.program, null)
  return hits
}

describe('beta UI tooltip dialect (no native titles)', () => {
  it('plain elements carry no native title outside the documented exceptions', () => {
    const offenders: string[] = []
    for (const file of walkTsx(NEXT_ROOT)) {
      const rel = relative(NEXT_ROOT, file)
      if (DOCUMENTED_NATIVE_TITLES[rel] !== undefined) continue
      for (const hit of intrinsicNativeTitles(readFileSync(file, 'utf8'))) {
        offenders.push(
          `${rel}:${hit.line} <${hit.tag} title> — speak data-tooltip (styles/next/tooltip.css), ` +
            `or document the exception in DOCUMENTED_NATIVE_TITLES`,
        )
      }
    }
    expect(offenders, 'native title on a plain beta-UI element').toEqual([])
  })

  it('every documented exception still exists, exactly as counted', () => {
    for (const [rel, entry] of Object.entries(DOCUMENTED_NATIVE_TITLES)) {
      const actual = intrinsicNativeTitles(readFileSync(join(NEXT_ROOT, rel), 'utf8')).length
      expect(
        actual,
        `${rel} documents ${entry.count} native title(s) — ${entry.why} — but carries ${actual}; ` +
          `update DOCUMENTED_NATIVE_TITLES in this test`,
      ).toBe(entry.count)
    }
  })
})
