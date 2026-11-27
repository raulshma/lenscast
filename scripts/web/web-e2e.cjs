// Drives the real beta web UI (vite dev + mock-api) in headless Chromium
// and measures every inspector field-row switch across a column-width
// sweep — the wire-level check behind the narrow-inspector CSS contract
// (a layout.css edit can satisfy the vitest pins and still break the
// computed geometry, and only a real layout pass sees that).
//
//   npm install            # once, in this dir (playwright-core)
//   node web-e2e.cjs       # sweep the default widths, exit 1 on any BAD
//
// Prereqs running elsewhere: mock-api.cjs on MOCK_PORT and
// `DEV_API_ORIGIN=http://localhost:8099 npm run dev` in web/.
// For a different regression, keep the skeleton and replace `measure` /
// `checkRow` — the valuable parts are the boot flow (localStorage-seeded
// layout, ready waits) and the width sweep.
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { chromium } = require('playwright-core')

// playwright-core's pinned browser revision rarely matches whatever sits in
// ~/.cache/ms-playwright — resolve the executable directly: CHROMIUM env
// wins, else the newest chromium-* cache dir.
function findChromium() {
  if (process.env.CHROMIUM) return process.env.CHROMIUM
  const root = path.join(os.homedir(), '.cache', 'ms-playwright')
  const dirs = fs.readdirSync(root)
    .filter((d) => d.startsWith('chromium-'))
    .sort()
  for (const d of dirs.reverse()) {
    for (const sub of ['chrome-linux64/chrome', 'chrome-linux/chrome']) {
      const exe = path.join(root, d, sub)
      if (fs.existsSync(exe)) return exe
    }
  }
  throw new Error('no chromium in ~/.cache/ms-playwright — set CHROMIUM=<path>')
}

function parseWidths() {
  const arg = process.argv.find((a) => a.startsWith('--widths='))
  const raw = arg ? arg.split('=')[1] : '280,300,340,380,400,480'
  return raw.split(',').map((n) => Number(n.trim())).filter((n) => n > 0)
}

async function measure(page) {
  return page.evaluate(() => {
    const aside = document.querySelector('aside.nx-inspector')
    if (!aside) return { error: 'no inspector' }
    const inner = aside.querySelector('.nx-inspector-inner')
    const rows = []
    for (const row of aside.querySelectorAll('.nx-field-row')) {
      const label = row.querySelector('.nx-field-label')?.textContent ?? '?'
      const sw = row.querySelector('.nx-switch')
      if (!sw) continue
      const r = row.getBoundingClientRect()
      const s = sw.getBoundingClientRect()
      const cs = getComputedStyle(sw)
      rows.push({
        label: label.slice(0, 30),
        rowW: Math.round(r.width),
        switchW: Math.round(s.width),
        switchH: Math.round(s.height),
        switchVisible: s.width > 20 && cs.display !== 'none' && cs.visibility === 'visible',
        rightEdgeAligned: Math.abs(r.right - s.right) < 30,
        computedWidth: cs.width,
      })
    }
    return {
      columnPx: getComputedStyle(aside).getPropertyValue('--nx-inspector-w'),
      innerClientW: inner.clientWidth,
      rows,
    }
  })
}

async function main() {
  const url = process.argv.find((a) => a.startsWith('--url='))?.split('=')[1] ?? 'http://localhost:3000/'
  const widths = parseWidths()
  const outDir = process.argv.find((a) => a.startsWith('--out='))?.split('=')[1]
    ?? '/tmp/lenscast-web-e2e'

  const browser = await chromium.launch({ executablePath: findChromium(), headless: true, args: ['--no-sandbox'] })
  const page = await browser.newPage({ viewport: { width: 1280, height: 950 } })
  const errors = []
  page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text().slice(0, 200)) })
  page.on('pageerror', (err) => errors.push('pageerror: ' + String(err).slice(0, 200)))

  const layoutDoc = {
    schemaVersion: 1,
    updatedAtMs: 1,
    activePresetId: 'streaming-links',
    column: { widthPx: widths[0], widthPreset: 'custom', collapsed: false },
    sections: ['channels', 'web-stream', 'audio', 'rtsp', 'whip', 'rtmp', 'clients', 'share'].map((sectionId) => ({
      id: sectionId, kind: 'builtin', sectionId, visible: true, collapsed: false,
      hiddenItems: [], customTitle: null, items: [],
    })),
    presets: [],
  }
  // localStorage is the local-only fallback path; the mock also serves
  // /api/ui/layout with the same shape, so either load path lands here.
  await page.addInitScript((doc) => {
    try {
      localStorage.setItem('lenscast.ui', 'next')
      localStorage.setItem('lenscast.nextInspectorLayout', JSON.stringify(doc))
    } catch { }
  }, layoutDoc)
  await page.goto(url, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('aside.nx-inspector', { timeout: 15000 })
  await page.waitForSelector('[data-section-id="web-stream"] .nx-field-row', { timeout: 15000 })
  await page.waitForTimeout(800)

  const aside = page.locator('aside.nx-inspector')
  const report = []
  for (const w of widths) {
    await aside.evaluate((el, px) => { el.style.setProperty('--nx-inspector-w', px + 'px') }, w)
    await page.waitForTimeout(250)
    const m = await measure(page)
    report.push({ width: w, ...m })
  }

  let bad = 0
  for (const entry of report) {
    console.log(`\n== column ${entry.width}px (inner ${entry.innerClientW})`)
    if (entry.error) { console.log(`  ${entry.error}`); bad += 1; continue }
    for (const r of entry.rows) {
      const ok = r.switchVisible && r.rightEdgeAligned
      bad += !ok
      console.log(`  [${r.label}] switch=${r.switchW}x${r.switchH} computed=${r.computedWidth} rightAligned=${r.rightEdgeAligned} ${ok ? 'ok' : 'BAD'}`)
    }
  }

  fs.mkdirSync(outDir, { recursive: true })
  await aside.evaluate((el, px) => { el.style.setProperty('--nx-inspector-w', px + 'px') }, widths[0])
  await page.waitForTimeout(300)
  await page.screenshot({ path: path.join(outDir, `e2e-${widths[0]}.png`), clip: { x: 1280 - 400, y: 0, width: 400, height: 950 } })
  const wide = widths[widths.length - 1]
  await aside.evaluate((el, px) => { el.style.setProperty('--nx-inspector-w', px + 'px') }, wide)
  await page.waitForTimeout(300)
  await page.screenshot({ path: path.join(outDir, `e2e-${wide}.png`), clip: { x: 1280 - 540, y: 0, width: 540, height: 950 } })

  console.log(`\nconsole errors: ${errors.length ? errors.slice(0, 8).join(' | ') : 'none'}`)
  console.log(`screenshots: ${outDir}/e2e-{${widths[0]},${wide}}.png`)
  await browser.close()
  process.exit(bad ? 1 : 0)
}

main().catch((err) => { console.error(err); process.exit(1) })
