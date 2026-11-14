# LensCast β console — design contract ("Viewfinder")

Design source of truth for the `next` UI. Every file under
`src/components/next/**` and `src/styles/next/**` implements this document.
Tokens live in `tokens.css`; the quality floor (focus, motion, scrollbars)
lives in `base.css`.

## The idea

LensCast turns an Android phone into a camera rig. This console is the
operator's **monitor and control deck**: mostly used while a live picture is
going out, often next to real broadcast tools. The redesign leans into that
world instead of a generic ops dashboard:

- **The picture is the hero.** On Live, the video runs near edge-to-edge;
  chrome frames it and never competes with it.
- The identity comes from the **logo's own palette**: camera-body black,
  the cyan lens, the magenta ring. Dark default, sharpened light parity.
- One orchestrated moment per session: the **on-air transition** (chip,
  LED, hairline edge all turn signal-magenta together when the stream
  starts). Everything else moves only when the hand acts.

## Signals — the color law

Color is a signal, never decoration. Exactly five meanings:

| Meaning | Token | Dark | Light | Notes |
|---|---|---|---|---|
| Touchable (interactive) | `--nx-accent` | `#3EC8FF` cyan | `#0A7EB5` | buttons, links, focus, toggles-on, selection, active nav |
| On air (live/rec) | `--nx-signal` | `#FF2E87` magenta | `#D60E6C` | LIVE chip, REC dot + timer, Led `rec`, recording timeline, detection hit marks |
| Wrong (error/fault) | `--nx-danger` | `#FF4757` red | `#C4273C` | error banners, failed states, Led `danger` |
| Degraded | `--nx-warning` | `#FFB020` amber | `#9A5B00` | connecting, thermal warn, degraded link |
| Healthy | `--nx-success` | `#37D98A` green | `#0E8A50` | connected/ok status **only** |

Rule of thumb: *magenta = your picture is going out; red = something is
wrong.* Anywhere the old CSS pointed a live/rec meaning at `--nx-danger`
(`.nx-chip-rec`, `Led tone="rec"`, recording timers, the LIVE badge),
re-point the color at `--nx-signal`. Keep tone *names* (`danger`, `rec`)
— they are API; only the color mapping changes.

Surfaces: `--nx-bg` (body black) < `--nx-surface` (plate) <
`--nx-surface-2` < `--nx-surface-3`; inset wells read `--nx-well`.
Depth is plates + hairlines; **glass only** for floating stage chrome
(`--nx-glass`); real shadows only on overlays (Drawer, Toaster, viewer).

## Type law

- **UI face: Golos Text** (`--nx-font`). All labels, nav, buttons, headings,
  body. Sentence case, weights 400/500/600, headings 600, tracking −0.01em.
- **Data face: JetBrains Mono** (`--nx-mono`). Strictly for *measurements*:
  timers, bitrates, fps, counts, timestamps, channel codes, versions,
  IP/ports. `.nx-num`/`.nx-mono` classes, tabular numerals.
- **Banned**: mono + uppercase + letter-spacing for labels/brand/nav/panel
  titles (the old tell). Panel titles are Golos 600 sentence case.
  No ALL-CAPS eyebrows anywhere. No numbered section codes (01, 02…)
  — nothing in this console is a numbered sequence.

## Voice law

- Sentence case everywhere; no ellipses on buttons (spinners show waiting).
- Status text states the fact: "Recording 04:12", "3 viewers",
  "Connection lost — retrying".
- Empty states name the object and the first action ("No events yet —
  detection is armed Mon–Fri"). No apology, no mood.

## Banned template chrome (hunt it down)

1. Uppercase tracked-out labels, mono label styling on non-data text.
2. Meta strings joined with middle dots (`84% · Charging`) — separate
   chips, or plain sentences.
3. `→` appended to buttons/links.
4. Identical rounded cards in uniform grids with the same soft shadow.
5. Decorative gradients/washes; glow on anything that is not an LED or
   the on-air state.
6. Fade-and-slide-up entrances on load; hover transitions on everything.

Motion: transitions answer the hand (open, expand, confirm, toggle) at
`--nx-duration`; the one autonomous moment is the on-air transition.
`prefers-reduced-motion` already collapses all of it — keep that intact.

## Hard contract (do not break)

- **Class names are API.** Every existing `nx-*` class stays (tests and
  cross-file CSS depend on them, e.g. `.nx-cam-strip`, `.nx-chip`,
  `.nx-slider`, `.nx-select`). Restyle freely; add classes; never rename
  or drop.
- **Component and state APIs stay.** Props of `ui/*` components, `NextState`
  accessors, router calls, i18n keys (`t(...)`) are untouched, except:
  - `Panel.code` is removed from `Panel`'s props (primitives agent removes
    it; view agents remove the `code={...}` usages in their files).
  - `Led` keeps tone names; CSS re-maps `rec` to signal magenta.
- **File ownership is disjoint** (no two agents touch one file).
  `src/components/icons/*`, `src/lib/*`, `src/hooks/*`, classic shell,
  `App.css` are read-only. Need a new icon? Note it in your report; the
  integrator adds it.
- **Accessibility floor**: visible `:focus-visible` ring on every control;
  labels/aria on icon-only buttons; contrast AA for text; hit targets ≥ 28px
  (desktop console density); `role="alert"` stays on error banners.
- Breakpoints keep their behavior: ≤1023px sidebar compacts, ≤767px bottom
  tab bar, tablet inspector via Drawer.
- Fonts are self-hosted (`@fontsource/golos-text` + jetbrains-mono), no
  runtime CDN. Light theme (`[data-theme='light']`) must reach parity for
  every surface you restyle.

## Per-surface direction

- **Shell** (`layout.css`, `NextShell.tsx`): a slim rail-first sidebar —
  brand mark, four view items (active item carries a cyan left tick and
  plate background), vitals dock as three quiet rows, utility row pinned
  bottom. The telemetry strip becomes a 36px statusline: view name left;
  channel LEDs, TX/fps/clients, REC (magenta, mono), thermal/battery right,
  each a quiet chip, no borders between. Canvas `--nx-bg`; the sidebar
  shares it, a hairline divides.
- **Login** (`NxLogin.tsx`): one plate, centered, on the body black — the
  logo's lens ring oversized and dim behind the form is *the* brand moment;
  a single cyan submit. No gradient hero, no caps.
- **Live** (`views/live.css`): the stage gets `--nx-well` surround and
  viewfinder corner ticks (1px, `--nx-border-strong`, ~24px arms, CSS only);
  floating command bar under the picture uses `--nx-glass` + blur — the only
  glass in the app; inspector column stacks faceplates (channel rack first).
- **System** (`views/cards.css`): panels are faceplates — plate surface,
  machined top bevel, hairline, radius 6, section title Golos 600 with a
  2px cyan tick on the left edge of the header; forms label-left /
  control-right on ≥1024px, stacked below. Danger zone: red hairline
  top, never a red wash.
- **Detection** (`views/detection.css`): event rows with a 2px signal tick
  on new/hit rows; stats panel leads with one big tabular numeral per
  metric; the arm schedule reads as a plain sentence, not chips.
- **Media** (`views/media.css`): gallery on `--nx-well`; tiles hairline,
  radius 6, hover raises plate + cyan hairline; selection is cyan edge +
  subtle cyan fill; no rounded-photo-app look.
