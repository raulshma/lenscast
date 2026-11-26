# LensCast β console — design contract ("Console")

Design source of truth for the `next` UI. Every file under
`src/components/next/**` and `src/styles/next/**` implements this
document. Tokens live in `tokens.css`; the scoped document floor lives
in `base.css`. Hallmark Console theme, full redesign of the Viewfinder
system (2026-11-25).

## The idea

LensCast turns an Android phone into a camera rig. This console is the
operator's **monitor and control deck**, and it behaves like one
instrument kept by careful people: one graphite surface, hairline
panels cut from it, amber for everything the operator does, cool blue
for everything the rig measures. **Minimal does not mean fewer
controls — it means nothing competes for attention, and information
arrives when it is valuable:**

- **The picture leads.** On Live, the video is the deck's reason to
  exist; every control stands aside until the hand reaches for it.
- **Information on demand, not all at once.** A constant is chrome; a
  signal is news. Battery at 87% is chrome until it drops low; a
  channel idling is chrome until it faults. Surfaces show the state
  that needs a decision, and hold the rest behind one deliberate
  interaction (a panel, a popover, the inspector).
- **One surface, not many plates.** Related controls sit in panels
  divided by 1px rules, never floating cards, never shadows, never
  glass, never glow.

## Signals — the colour law

Four meanings, nothing decorative. Amber and cool are the two voices;
ok and bad exist for status only.

| Meaning | Token | Dark | Light | Notes |
|---|---|---|---|---|
| The operator (interactive, selection, on air) | `--color-accent` | `oklch(82% .15 80)` amber | `oklch(54% .14 70)` | primary button, active nav, selected row, REC + LIVE + the tally, focus ring |
| The instrument (readings) | `--color-cool` | `oklch(84% .1 245)` | `oklch(50% .14 250)` | bitrates, fps, counts, timestamps, channel codes, versions, links on hover |
| Healthy | `--color-ok` | `oklch(76% .15 150)` | `oklch(49% .13 150)` | connected/ok status **only** |
| Fault or degradation | `--color-bad` | `oklch(70% .17 25)` | `oklch(52% .17 25)` | errors, failed states, degraded link, low battery |

Rule of thumb: *amber = you or your rig are doing something; cool =
the rig is telling you a number; red = look now.* REC and LIVE are
amber (the tally), never red — red is only ever a fault. Degraded
(reads as "the person should look") takes amber at soft strength
(`--color-accent-soft`); a hard fault takes `--color-bad`.

Surfaces: `--color-paper` (ground) > `--color-paper-2` (panel) >
`--color-paper-3` (well: inputs, logs, the stage surround). Panels
that belong together are **one surface divided by 1px `--color-rule`
seams** (the `.cut` pattern in components.css). No shadows on panels;
the one real shadow is for overlays (`--shadow-overlay`). Edge law: a
boundary that identifies an interactive component (fields, buttons,
switches, chips, toasts) takes `--color-muted`, the only edge token
that clears 3:1 on every surface; `--color-rule` / `--color-rule-2`
hairlines are decorative seams only.

## Type law

- **Speaking face: Golos Text** (`--font-body` = `--font-display`).
  All labels, nav, buttons, headings, body. Sentence case, weights
  400/500/600, headings 600, tracking −0.02em. Kept from the previous
  system because it carries cyrillic (ru locale); the theme's face is
  latin-only and would render ru as a fallback mid-sentence.
- **Measured face: Atkinson Hyperlegible Mono** (`--font-outlier`).
  Strictly what an instrument wrote: timers, bitrates, fps, counts,
  timestamps, channel codes, versions, IP/ports. Tabular, slashed
  zero. Never a label, a head, a button or a nav item.
- **Banned**: mono on non-measured text; ALL-CAPS anything; tracked
  labels; letter-spacing past 0.03em on anything smaller than a
  wordmark.

## Density and controls

- App console density: controls 32px tall (`--control-h`), 36px tall
  (`--control-h-tall`) for primary rows; every hit target expands to
  44px (`::before { inset: -8px }` pattern) without changing the look.
- One primary (amber fill, `--color-accent-ink` text) per screen;
  secondaries are 1px `--color-rule-2` outlines on `--color-paper-2`.
  Destructive actions are outlined, never filled red.
- Every control ships all eight states (default, hover, focus-visible,
  active, disabled × 3 channels + native attribute, loading, error,
  success). Inputs: constant border width, inset ring; panel forms run
  label-left / control-right on ≥1024px and stack below that.
- Every region that loads data has four states: skeleton loading,
  empty (what belongs here + the one action), error (what broke + what
  to do), full.

## Tooltips

- The console speaks one tooltip dialect: **`data-tooltip`** — the
  styled bubble (styles/next/tooltip.css) rendered by the host's own
  `::after`. The kit's `Button`/`IconButton` carry it through the
  `tooltip`/`tooltipEdge` props; `IconButton` defaults the bubble to
  its required `label`. Never set a native `title` on a beta control.
- The accessible name still comes from `aria-label` (icon-only
  buttons must say what they do); the bubble is the visual echo, and
  `:focus-visible` reveals it so a keyboard pass sees what the hand
  sees. Hover shows on pointer devices only (`@media (hover: hover)`).
- The host must be a containing block for the absolute `::after`:
  positioned classes already are; static ones add `position: relative`
  in their own rule. Nothing in tooltip.css forces position, so
  absolutely-placed hosts (zone rects, tile deletes, timeline
  segments) keep their placement.
- Re-anchor with `data-tooltip-edge="below|left|right"` when the
  default centered-above placement would clip: `below` for hosts at a
  clipping container's top (PiP chrome row, inspector column toggle,
  fullscreen exit), `left`/`right` to keep bar-end keys inside the
  frame.
- Native `title` survives only where a bubble physically cannot
  render: the language `<select>` (form controls have no `::after`),
  self-clipping truncation hints on non-interactive text (ellipsis
  filenames, ids, timers), and the status-strip chips (clipped both
  ways by `.nx-strip-chips`). A disabled button shows no tooltip of
  any kind — an important reason stands on the row as text, not only
  in the bubble.

## Progressive disclosure (the redesign's spine)

1. **Decide what is news.** A value is news when it changed, when it
   faults, or when the operator must act. Everything else is chrome.
2. **Chrome is quiet:** muted ink, no borders of its own, no chip
   unless it carries state.
3. **News is legible at a glance:** at most one amber and one red
   signal visible per region at rest.
4. **Depth is one deliberate interaction away:** a disclosure, a
   drawer, a tab — never a nested card, never a modal for reading.
5. **The stage shows the state, not the settings:** live view leads
   with the picture and the transport truth (on air, REC, clients);
   tuning lives in the strip and the inspector.

## Motion

Transitions answer the hand only: `--dur-micro` for press and colour,
`--dur-short` for hover and menus, `--dur-long` for drawer and modal.
`transform` and `opacity` only. No reveal on load, no autonomous
motion except a value changing when its source does. Reduced motion
collapses all of it (base.css).

## Hard contract (do not break)

- **Class names are API.** Every existing `nx-*` class stays (tests
  and cross-file CSS depend on them: `.nx-cam-strip`, `.nx-chip`,
  `.nx-slider`, `.nx-select`, `.nx-pip*`, `.nx-vital-*`,
  `.nx-strip-chips`…). Restyle freely; add classes; never rename or
  drop.
- **Component and state APIs stay.** Props of `ui/*` components,
  `NextState` accessors, router calls, i18n keys (`t(...)`) are
  untouched.
- **File ownership is disjoint** per agent run; the current mapping is
  recorded in `.hallmark/preflight.json` and each run's brief.
- **Shared components are read-only markup:** `StreamPreview.tsx`,
  `MediaViewer*.tsx`, `ShortcutsOverlay.tsx`, `RecordingTimer.tsx`,
  `src/components/icons/*`, `src/lib/*`, `src/hooks/*`, the classic
  shell, `App.css`. Restyle them only with `[data-ui='next']`-scoped
  rules inside `styles/next/**`. Need a new icon: inline it in your
  own file.
- **i18n:** reuse existing keys wherever the wording serves. A new key
  goes in the agent's report (proposed en string); the integrator adds
  it to all seven catalogs, so catalogs stay single-owner.
- **Accessibility floor:** visible `:focus-visible` ring; labels/aria
  on icon-only buttons; contrast AA (body 4.5:1, edges 3:1); hit
  targets ≥ 44px expanded; `role="alert"` stays on error banners.
- Breakpoints keep their behaviour: ≤1023px sidebar compacts, ≤767px
  bottom tab bar, tablet inspector via Drawer.
- Fonts self-hosted (`@fontsource/*`), no runtime CDN.
- Light theme (`[data-theme='light']`) reaches parity for every
  surface restyled.
