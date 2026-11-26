import { For, Show, createEffect, createSignal, createUniqueId, onCleanup } from 'solid-js'
import type { MaskingType, MaskingZone } from '../../../../../types'
import { MASKING_TYPE_LABELS } from '../../../../../types'
import { t } from '../../../../../lib/i18n'
import { IconChevronDown } from '../../../../icons'
import {
  Badge,
  Button,
  Empty,
  Field,
  FieldRow,
  Panel,
  Select,
  SliderField,
  Switch,
  TextInput,
  ToggleField,
} from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'
import type { MaskEditSession, MaskEditZonePatch } from './maskEditSession'

/** Wire value → i18n key for the masking-type options. */
const MASK_KEYS: Record<MaskingType, string> = {
  BLACKOUT: 'option.mask.blackout',
  PIXELATE: 'option.mask.pixelate',
  BLUR: 'option.mask.blur',
}

/** The type menu, in the panel's order, built on each read so a locale switch reaches it too. */
const typeOptions = () =>
  (Object.keys(MASKING_TYPE_LABELS) as MaskingType[]).map((value) => ({ value, label: t(MASK_KEYS[value]) }))

/** How long after the last keystroke or drag step a typed or dragged field reaches the session. */
export const FIELD_COMMIT_DELAY_MS = 300

// ── Whole percent ───────────────────────────────────────────────────
// Manual entry works in whole percent of the picture, the unit the readout
// and the classic panel's sliders speak. The session keeps the fraction.

const toPercent = (fraction: number): number => Math.round(fraction * 100)
const fromPercent = (percent: number): number => percent / 100

/** The four geometry fields, each labelled with its own bounds (the spinners stop there). */
const GEOMETRY = [
  { key: 'x', min: 0, max: 100 },
  { key: 'y', min: 0, max: 100 },
  { key: 'width', min: 1, max: 100 },
  { key: 'height', min: 1, max: 100 },
] as const

// ── A field that saves once the hand settles ────────────────────────

/**
 * One typed or dragged field of the selected zone. The value on screen is
 * this field's own draft and follows the hand at once; the session — and so
 * the device, at one PUT per `updateZone` — hears it only once the hand goes
 * quiet for FIELD_COMMIT_DELAY_MS, or right away when the field loses the
 * focus. So a word typed is one save, not one per keystroke, and a slider
 * dragged across its range is one save, not one per pixel.
 *
 * A field mid-edit is the operator's: a change arriving from the session
 * (there is none while it has the focus, but the rule is general) waits for
 * the pending commit to land first. When the field goes away — the selection
 * moved, the zone went, the editor closed — whatever is still pending is
 * committed for the zone it was typed into; the session drops it harmlessly
 * if that zone is already gone.
 */
function lateField<T extends string | number>(read: () => T, write: (value: T) => void) {
  // The draft is typed from the outside. Solid's setter takes its type from
  // the argument and refuses a value that might be a function — which a bare
  // type parameter might — so the signal holds the plain union, and its one
  // setter call goes through a plainly typed function. The value is read
  // back as T once; every write to it was a T to begin with.
  const [held, setHeld] = createSignal<string | number>(read())
  const draft = (): T => held() as T
  const show = (value: string | number): void => {
    setHeld(value)
  }
  let timer: ReturnType<typeof setTimeout> | undefined

  /** The final commit: what is on screen goes to the session now, if anything is waiting. */
  function flush(): void {
    if (timer === undefined) return
    clearTimeout(timer)
    timer = undefined
    write(draft())
  }

  createEffect(() => {
    const committed = read()
    if (timer === undefined) show(committed)
  })
  onCleanup(flush)

  return {
    draft,
    /** The hand moved: show it now, save it when it settles. */
    edit(value: T) {
      show(value)
      if (timer !== undefined) clearTimeout(timer)
      timer = setTimeout(flush, FIELD_COMMIT_DELAY_MS)
    },
    flush,
  }
}

/** Enter in a field commits it: the blur is the commit. */
function commitOnEnter(e: KeyboardEvent): void {
  if (e.key !== 'Enter') return
  e.preventDefault()
  ;(e.target as HTMLInputElement).blur()
}

export interface MaskZoneInspectorProps {
  /** The mounted editor's controller. */
  session: MaskEditSession
}

/**
 * The masking-zone editor's inspector, in the Live view's inspector column
 * while an edit is open: the zones on the left of the operator's attention,
 * and the selected one's settings.
 *
 * Like the edit bar, it owns nothing — the list is the session's draft, the
 * selection is the session's, and every control is a session command. What
 * it adds is the part the picture cannot carry: names, types, the mask
 * parameters, and exact numbers for a zone the hand cannot place precisely.
 *
 * Saves are the session's, one PUT per command, so this file is careful
 * about how many commands it issues: discrete controls (the switch, the
 * type) commit on the change; typed and dragged fields go through
 * `lateField`.
 */
export default function MaskZoneInspector(props: MaskZoneInspectorProps) {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const session = props.session
  const zones = () => session.zones()

  return (
    <div class="nx-mask-inspector">
      {/* The master switch is the device's gate: with it off, no drawn zone
          paints on the stream. The inspector leads with the reason and the
          one action — the kit's warning banner, amber at soft strength (the
          operator's-attention voice, not a fault). */}
      <SettingItem id="masking.enabled">
      <Show when={!session.maskingEnabled()}>
        <div class="nx-banner nx-banner-warning" role="status">
          <span class="nx-mask-warn-text">{t('privacy.editor.maskingOff')}</span>
          <Button
            size="sm"
            variant="secondary"
            disabled={readOnly()}
            onClick={() => { if (!readOnly()) session.setMaskingEnabled(true) }}
          >
            {t('privacy.editor.maskingOffAction')}
          </Button>
        </div>
      </Show>
      </SettingItem>
      <SettingItem id="masking.zones">
      <Panel title={t('privacy.zones', { count: zones().length })}>
        <Show
          when={zones().length > 0}
          fallback={
            // The editor is open here — the wording that sends the operator
            // to "Edit zones" is the System panel's, not this one's.
            <Empty title={t('privacy.editor.drawHint')} />
          }
        >
          <div class="nx-mask-zone-list">
            <For each={zones()}>
              {(zone, index) => {
                const selected = () => session.selectedId() === zone.id
                const name = () => zone.label || t('privacy.zone', { n: index() + 1 })
                return (
                  <div
                    class="nx-mask-zone-row"
                    classList={{
                      'nx-mask-zone-row-selected': selected(),
                      'nx-mask-zone-row-disabled': !zone.enabled,
                    }}
                    data-zone-id={zone.id}
                    // The whole row selects — its highlight says so. The one
                    // exception is the switch: that click is the zone's
                    // on/off, and never moves the selection.
                    onClick={(e) => {
                      if ((e.target as Element).closest('.nx-switch')) return
                      session.select(zone.id)
                    }}
                  >
                    {/* The pick: the row's keyboard and screen-reader target.
                        Its click is the row's (a button's activation bubbles). */}
                    <button type="button" class="nx-mask-zone-pick" aria-pressed={selected()}>
                      <span class="nx-mask-zone-name">{name()}</span>
                      <Badge tone={zone.type === 'BLACKOUT' ? 'neutral' : 'info'}>
                        {t(MASK_KEYS[zone.type])}
                      </Badge>
                    </button>
                    {/* The switch is named for its zone, so turning a zone off
                        never has to move the selection to do it. */}
                    <Switch
                      checked={zone.enabled}
                      disabled={readOnly()}
                      ariaLabel={`${name()} · ${t('privacy.enabled')}`}
                      onChange={(enabled) => { if (!readOnly()) session.updateZone({ enabled }, zone.id) }}
                    />
                  </div>
                )
              }}
            </For>
          </div>
        </Show>
      </Panel>
      </SettingItem>

      <Show
        when={session.selectedId()}
        keyed
        fallback={
          // Zones but no selection: say how to pick one. With no zones the
          // empty state above has already said what to do.
          <Show when={zones().length > 0}>
            <p class="nx-mask-hint">{t('privacy.editor.selectHint')}</p>
          </Show>
        }
      >
        {(zoneId) => <ZoneDetails session={session} zoneId={zoneId} />}
      </Show>
    </div>
  )
}

// ── The selected zone ───────────────────────────────────────────────

/** Keyed by zone id (the Show above): one instance per selection, so every draft starts from the zone. */
function ZoneDetails(props: { session: MaskEditSession; zoneId: string }) {
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const session = props.session
  const updateZone = (patch: MaskEditZonePatch) => {
    if (!readOnly()) session.updateZone(patch, props.zoneId)
  }
  const zone = () => session.zones().find((item) => item.id === props.zoneId) ?? null
  const name = () => {
    const list = session.zones()
    const current = zone()
    return current ? current.label || t('privacy.zone', { n: list.indexOf(current) + 1 }) : ''
  }
  const [fineTune, setFineTune] = createSignal(false)
  const gridId = createUniqueId()

  const label = lateField(
    () => zone()?.label ?? '',
    (value) => updateZone({ label: value }),
  )
  const pixelSize = lateField(
    () => zone()?.pixelateSize ?? 16,
    (value) => updateZone({ pixelateSize: value }),
  )
  const blurRadius = lateField(
    () => zone()?.blurRadius ?? 10,
    (value) => updateZone({ blurRadius: Math.round(value) }),
  )

  return (
    <SettingItem id="masking.zones">
    <Panel
      title={name()}
      actions={
        <Button
          size="sm"
          variant="danger-soft"
          disabled={readOnly()}
          onClick={() => { if (!readOnly()) session.removeZone(props.zoneId) }}
        >
          {t('privacy.removeZone')}
        </Button>
      }
    >
      <FieldRow label={t('privacy.label')}>
        <div class="nx-mask-field" onKeyDown={commitOnEnter}>
          <TextInput
            disabled={readOnly()}
            class="nx-mask-label-input"
            small
            value={label.draft()}
            placeholder={t('privacy.labelPlaceholder')}
            onInput={label.edit}
            onBlur={label.flush}
            ariaLabel={t('privacy.label')}
          />
        </div>
      </FieldRow>

      <ToggleField
        disabled={readOnly()}
        label={t('privacy.enabled')}
        checked={zone()?.enabled}
        onChange={(enabled) => updateZone({ enabled })}
      />

      <FieldRow label={t('privacy.maskType')}>
        <Select
          disabled={readOnly()}
          small
          value={zone()?.type}
          options={typeOptions}
          onChange={(value) => updateZone({ type: value as MaskingType })}
          ariaLabel={t('privacy.maskType')}
        />
      </FieldRow>

      {/* The parameter belongs to the type: one slider is on screen at a time. */}
      <Show when={zone()?.type === 'PIXELATE'}>
        <SliderField
          disabled={readOnly()}
          label={t('privacy.pixelSize')}
          display={`${pixelSize.draft()}px`}
          min={4}
          max={64}
          step={1}
          value={pixelSize.draft()}
          onChange={pixelSize.edit}
        />
      </Show>
      <Show when={zone()?.type === 'BLUR'}>
        <SliderField
          disabled={readOnly()}
          label={t('privacy.blurRadius')}
          display={String(Math.round(blurRadius.draft()))}
          min={1}
          max={50}
          step={1}
          value={Math.round(blurRadius.draft())}
          onChange={blurRadius.edit}
        />
      </Show>

      {/* Exact numbers, one deliberate interaction away: collapsed, because
          the picture is where a zone is placed. */}
      <button
        type="button"
        class="nx-mask-finetune"
        aria-expanded={fineTune()}
        aria-controls={gridId}
        onClick={() => setFineTune(!fineTune())}
      >
        <span class="nx-mask-finetune-chevron" classList={{ 'nx-mask-finetune-chevron-open': fineTune() }}>
          <IconChevronDown size={16} />
        </span>
        {t('privacy.editor.fineTune')}
      </button>
      <Show when={fineTune()}>
        <div class="nx-mask-grid" id={gridId} onKeyDown={commitOnEnter}>
          <For each={GEOMETRY}>
            {(field) => (
              <PercentField
                label={t(`privacy.${field.key}`)}
                value={() => toPercent(zone()?.[field.key] ?? 0)}
                min={field.min}
                max={field.max}
                disabled={readOnly()}
                onCommit={(percent) => {
                  if (readOnly()) return
                  const patch: MaskEditZonePatch = {}
                  patch[field.key] = fromPercent(percent)
                  updateZone(patch)
                }}
              />
            )}
          </For>
        </div>
      </Show>
    </Panel>
    </SettingItem>
  )
}

// ── One number, in whole percent ────────────────────────────────────

/**
 * A geometry field typed as a whole number of percent. It commits on blur
 * and on Enter — never per keystroke, so a half-typed "1" is not a zone
 * moved to the frame's edge. The field's min/max stop the spinners; a typed
 * number outside them is left to the session, whose frame rule is the one
 * owner of what a legal rectangle is (an origin past the far edge slides
 * back, a width with no room is cut). The field then shows that result, not
 * what was typed; a typed fraction ("12.5") rounds to the whole percent it
 * names. Anything that is not a number puts the zone's value back.
 */
function PercentField(props: {
  label: string
  value: () => number
  min: number
  max: number
  disabled?: boolean
  onCommit: (percent: number) => void
}) {
  const [text, setText] = createSignal(String(props.value()))
  // True from the first keystroke until the commit: meanwhile the field is the operator's, not the zone's.
  let dirty = false

  createEffect(() => {
    const current = props.value()
    if (!dirty) setText(String(current))
  })

  function commit(): void {
    dirty = false
    // A typed fraction ("12.5") rounds to the whole percent it names — a
    // truncating parse would silently take it for 12.
    const parsed = Math.round(Number.parseFloat(text()))
    const current = props.value()
    if (!Number.isFinite(parsed)) {
      setText(String(current))
      return
    }
    if (parsed !== current) props.onCommit(parsed)
    setText(String(props.value()))
  }

  return (
    <Field label={props.label}>
      <TextInput
        disabled={props.disabled}
        class="nx-mask-percent"
        type="number"
        min={props.min}
        max={props.max}
        step={1}
        small
        mono
        value={text()}
        onInput={(value) => {
          dirty = true
          setText(value)
        }}
        onBlur={commit}
        ariaLabel={props.label}
      />
    </Field>
  )
}
