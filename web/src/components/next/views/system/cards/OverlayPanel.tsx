import { Show } from 'solid-js'
import type { OverlayPosition, StreamingSettings } from '../../../../../types'
import { OVERLAY_POSITION_LABELS } from '../../../../../types'
import { t } from '../../../../../lib/i18n'
import { Panel, FieldRow, ToggleField, Select, TextInput, SliderField, Led } from '../../../ui'
import { SettingItem, useInspectorSection } from '../../../ui/SettingItem'

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
  const inspector = useInspectorSection()
  const readOnly = () => inspector?.readOnly() === true
  const update = (patch: Partial<StreamingSettings>) => {
    if (!readOnly()) props.onUpdate(patch)
  }
  const s = () => props.streaming()

  return (
    <Panel
      title={t('overlay.title')}
      badge={<Led tone={s().overlayEnabled ? 'ok' : 'off'} />}
    >
      <SettingItem id="overlay.enabled">
      <ToggleField
        disabled={readOnly()}
        label={t('overlay.enable')}
        checked={s().overlayEnabled}
        onChange={(v) => update({ overlayEnabled: v })}
      />
      </SettingItem>

      <Show when={s().overlayEnabled}>
        <SettingItem id="overlay.timestamp">
        <>
        <ToggleField
          disabled={readOnly()}
          label={t('overlay.timestamp')}
          checked={s().showTimestamp}
          onChange={(v) => update({ showTimestamp: v })}
        />
        <Show when={s().showTimestamp}>
          <FieldRow label={t('overlay.format')}>
            <TextInput
              disabled={readOnly()}
              small
              mono
              value={s().timestampFormat}
              placeholder="yyyy-MM-dd HH:mm:ss"
              onInput={(v) => update({ timestampFormat: v })}
              ariaLabel={t('overlay.format')}
            />
          </FieldRow>
        </Show>
        </>
        </SettingItem>

        <SettingItem id="overlay.branding">
        <>
        <ToggleField
          disabled={readOnly()}
          label={t('overlay.branding')}
          checked={s().showBranding}
          onChange={(v) => update({ showBranding: v })}
        />
        <Show when={s().showBranding}>
          <FieldRow label={t('overlay.text')}>
            <TextInput
              disabled={readOnly()}
              small
              value={s().brandingText}
              placeholder="LensCast"
              onInput={(v) => update({ brandingText: v })}
              ariaLabel={t('overlay.text')}
            />
          </FieldRow>
        </Show>
        </>
        </SettingItem>

        <SettingItem id="overlay.status">
        <ToggleField
          disabled={readOnly()}
          label={t('overlay.status')}
          checked={s().showStatus}
          onChange={(v) => update({ showStatus: v })}
        />
        </SettingItem>

        <SettingItem id="overlay.customText">
        <>
        <ToggleField
          disabled={readOnly()}
          label={t('overlay.custom')}
          checked={s().showCustomText}
          onChange={(v) => update({ showCustomText: v })}
        />
        <Show when={s().showCustomText}>
          <FieldRow label={t('overlay.text')}>
            <TextInput
              disabled={readOnly()}
              small
              value={s().customText}
              placeholder={t('overlay.customPlaceholder')}
              onInput={(v) => update({ customText: v })}
              ariaLabel={t('overlay.text')}
            />
          </FieldRow>
        </Show>
        </>
        </SettingItem>

        <SettingItem id="overlay.style">
        <>
        <FieldRow label={t('overlay.position')}>
          <Select
            disabled={readOnly()}
            value={s().overlayPosition}
            options={Object.keys(OVERLAY_POSITION_LABELS).map((value) => ({ value, label: t(POS_KEYS[value as OverlayPosition]) }))}
            onChange={(v) => update({ overlayPosition: v as OverlayPosition })}
            ariaLabel={t('overlay.position')}
          />
        </FieldRow>

        <SliderField
          disabled={readOnly()}
          label={t('overlay.fontSize')}
          display={`${s().overlayFontSize}px`}
          min={8}
          max={72}
          step={1}
          value={s().overlayFontSize}
          onChange={(v) => update({ overlayFontSize: v })}
        />

        <FieldRow label={t('overlay.textColor')}>
          <input
            type="color"
            disabled={readOnly()}
            class="nx-color"
            value={s().overlayTextColor.slice(-7)}
            onInput={(e) => update({ overlayTextColor: e.currentTarget.value })}
            aria-label={t('overlay.textColor')}
          />
        </FieldRow>

        <FieldRow label={t('overlay.background')}>
          <input
            type="color"
            disabled={readOnly()}
            class="nx-color"
            value={`#${s().overlayBackgroundColor.slice(-6)}`}
            onInput={(e) => {
              const hex = e.currentTarget.value
              const alpha = s().overlayBackgroundColor.slice(1, 3) || '80'
              update({ overlayBackgroundColor: `#${alpha}${hex.slice(1)}` })
            }}
            aria-label={t('overlay.background')}
          />
        </FieldRow>

        <SliderField
          disabled={readOnly()}
          label={t('overlay.padding')}
          display={`${s().overlayPadding}px`}
          min={0}
          max={32}
          step={1}
          value={s().overlayPadding}
          onChange={(v) => update({ overlayPadding: v })}
        />

        <SliderField
          disabled={readOnly()}
          label={t('overlay.lineHeight')}
          display={`${s().overlayLineHeight}px`}
          min={0}
          max={20}
          step={1}
          value={s().overlayLineHeight}
          onChange={(v) => update({ overlayLineHeight: v })}
        />
        </>
        </SettingItem>
      </Show>
    </Panel>
  )
}
