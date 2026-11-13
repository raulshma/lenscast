import { Show } from 'solid-js'
import type { OverlayPosition, StreamingSettings } from '../../../../../types'
import { OVERLAY_POSITION_LABELS } from '../../../../../types'
import { t } from '../../../../../lib/i18n'
import { Panel, FieldRow, ToggleField, Select, TextInput, SliderField, Led } from '../../../ui'

/** Wire value → i18n key for the overlay-position options. */
const POS_KEYS: Record<OverlayPosition, string> = {
  TOP_LEFT: 'option.pos.topLeft',
  TOP_RIGHT: 'option.pos.topRight',
  BOTTOM_LEFT: 'option.pos.bottomLeft',
  BOTTOM_RIGHT: 'option.pos.bottomRight',
}

interface Props {
  streaming: () => StreamingSettings
  onUpdate: (patch: Partial<StreamingSettings>) => void
}

export default function OverlayPanel(props: Props) {
  const s = () => props.streaming()

  return (
    <Panel
      title={t('overlay.title')}
      badge={<Led tone={s().overlayEnabled ? 'ok' : 'off'} />}
    >
      <ToggleField
        label={t('overlay.enable')}
        checked={s().overlayEnabled}
        onChange={(v) => props.onUpdate({ overlayEnabled: v })}
      />

      <Show when={s().overlayEnabled}>
        <ToggleField
          label={t('overlay.timestamp')}
          checked={s().showTimestamp}
          onChange={(v) => props.onUpdate({ showTimestamp: v })}
        />
        <Show when={s().showTimestamp}>
          <FieldRow label={t('overlay.format')}>
            <TextInput
              small
              mono
              value={s().timestampFormat}
              placeholder="yyyy-MM-dd HH:mm:ss"
              onInput={(v) => props.onUpdate({ timestampFormat: v })}
              ariaLabel={t('overlay.format')}
            />
          </FieldRow>
        </Show>

        <ToggleField
          label={t('overlay.branding')}
          checked={s().showBranding}
          onChange={(v) => props.onUpdate({ showBranding: v })}
        />
        <Show when={s().showBranding}>
          <FieldRow label={t('overlay.text')}>
            <TextInput
              small
              value={s().brandingText}
              placeholder="LensCast"
              onInput={(v) => props.onUpdate({ brandingText: v })}
              ariaLabel={t('overlay.text')}
            />
          </FieldRow>
        </Show>

        <ToggleField
          label={t('overlay.status')}
          checked={s().showStatus}
          onChange={(v) => props.onUpdate({ showStatus: v })}
        />

        <ToggleField
          label={t('overlay.custom')}
          checked={s().showCustomText}
          onChange={(v) => props.onUpdate({ showCustomText: v })}
        />
        <Show when={s().showCustomText}>
          <FieldRow label={t('overlay.text')}>
            <TextInput
              small
              value={s().customText}
              placeholder={t('overlay.customPlaceholder')}
              onInput={(v) => props.onUpdate({ customText: v })}
              ariaLabel={t('overlay.text')}
            />
          </FieldRow>
        </Show>

        <FieldRow label={t('overlay.position')}>
          <Select
            value={s().overlayPosition}
            options={Object.keys(OVERLAY_POSITION_LABELS).map((value) => ({ value, label: t(POS_KEYS[value as OverlayPosition]) }))}
            onChange={(v) => props.onUpdate({ overlayPosition: v as OverlayPosition })}
            ariaLabel={t('overlay.position')}
          />
        </FieldRow>

        <SliderField
          label={t('overlay.fontSize')}
          display={`${s().overlayFontSize}px`}
          min={8}
          max={72}
          step={1}
          value={s().overlayFontSize}
          onChange={(v) => props.onUpdate({ overlayFontSize: v })}
        />

        <FieldRow label={t('overlay.textColor')}>
          <input
            type="color"
            class="nx-color"
            value={s().overlayTextColor.slice(-7)}
            onInput={(e) => props.onUpdate({ overlayTextColor: e.currentTarget.value })}
            aria-label={t('overlay.textColor')}
          />
        </FieldRow>

        <FieldRow label={t('overlay.background')}>
          <input
            type="color"
            class="nx-color"
            value={`#${s().overlayBackgroundColor.slice(-6)}`}
            onInput={(e) => {
              const hex = e.currentTarget.value
              const alpha = s().overlayBackgroundColor.slice(1, 3) || '80'
              props.onUpdate({ overlayBackgroundColor: `#${alpha}${hex.slice(1)}` })
            }}
            aria-label={t('overlay.background')}
          />
        </FieldRow>

        <SliderField
          label={t('overlay.padding')}
          display={`${s().overlayPadding}px`}
          min={0}
          max={32}
          step={1}
          value={s().overlayPadding}
          onChange={(v) => props.onUpdate({ overlayPadding: v })}
        />

        <SliderField
          label={t('overlay.lineHeight')}
          display={`${s().overlayLineHeight}px`}
          min={0}
          max={20}
          step={1}
          value={s().overlayLineHeight}
          onChange={(v) => props.onUpdate({ overlayLineHeight: v })}
        />
      </Show>
    </Panel>
  )
}
