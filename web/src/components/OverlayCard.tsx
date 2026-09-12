import { Show } from 'solid-js'
import SettingsCard from './SettingsCard'
import type { OverlayPosition, StreamingSettings } from '../types'
import { OVERLAY_POSITION_LABELS } from '../types'
import { t } from '../lib/i18n'

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

export default function OverlayCard(props: Props) {
  const s = () => props.streaming()

  return (
    <SettingsCard
      icon={
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="2" y="3" width="20" height="14" rx="2" />
          <path d="M8 21h8M12 17v4" />
          <text x="6" y="13" font-size="6" fill="currentColor" stroke="none">T</text>
        </svg>
      }
      title={t('overlay.title')}
    >
      <div class="settings-group">
        <div class="field-row field-row-toggle">
          <span class="field-label">{t('overlay.enable')}</span>
          <label class="toggle-switch" for="overlay-enabled-toggle">
            <input
              id="overlay-enabled-toggle"
              type="checkbox"
              checked={s().overlayEnabled}
              onChange={(e) => props.onUpdate({ overlayEnabled: e.currentTarget.checked })}
            />
            <span class="toggle-slider" />
          </label>
        </div>
      </div>

      <Show when={s().overlayEnabled}>
        <div class="settings-group">
          <label class="setting-row">
            <span class="setting-label">{t('overlay.timestamp')}</span>
            <input
              type="checkbox"
              class="toggle-input"
              checked={s().showTimestamp}
              onChange={(e) => props.onUpdate({ showTimestamp: e.currentTarget.checked })}
            />
          </label>

          <Show when={s().showTimestamp}>
            <div class="setting-row">
              <span class="setting-label">{t('overlay.format')}</span>
              <input
                type="text"
                class="text-input text-input-sm"
                value={s().timestampFormat}
                placeholder="yyyy-MM-dd HH:mm:ss"
                onChange={(e) => props.onUpdate({ timestampFormat: e.currentTarget.value })}
              />
            </div>
          </Show>
        </div>

        <div class="settings-group">
          <label class="setting-row">
            <span class="setting-label">{t('overlay.branding')}</span>
            <input
              type="checkbox"
              class="toggle-input"
              checked={s().showBranding}
              onChange={(e) => props.onUpdate({ showBranding: e.currentTarget.checked })}
            />
          </label>

          <Show when={s().showBranding}>
            <div class="setting-row">
              <span class="setting-label">{t('overlay.text')}</span>
              <input
                type="text"
                class="text-input text-input-sm"
                value={s().brandingText}
                placeholder="LensCast"
                onChange={(e) => props.onUpdate({ brandingText: e.currentTarget.value })}
              />
            </div>
          </Show>
        </div>

        <div class="settings-group">
          <label class="setting-row">
            <span class="setting-label">{t('overlay.status')}</span>
            <input
              type="checkbox"
              class="toggle-input"
              checked={s().showStatus}
              onChange={(e) => props.onUpdate({ showStatus: e.currentTarget.checked })}
            />
          </label>
        </div>

        <div class="settings-group">
          <label class="setting-row">
            <span class="setting-label">{t('overlay.custom')}</span>
            <input
              type="checkbox"
              class="toggle-input"
              checked={s().showCustomText}
              onChange={(e) => props.onUpdate({ showCustomText: e.currentTarget.checked })}
            />
          </label>

          <Show when={s().showCustomText}>
            <div class="setting-row">
              <span class="setting-label">{t('overlay.text')}</span>
              <input
                type="text"
                class="text-input text-input-sm"
                value={s().customText}
                placeholder={t('overlay.customPlaceholder')}
                onChange={(e) => props.onUpdate({ customText: e.currentTarget.value })}
              />
            </div>
          </Show>
        </div>

        <div class="settings-group">
          <div class="setting-row">
            <span class="setting-label">{t('overlay.position')}</span>
            <select
              class="select-input select-input-sm"
              value={s().overlayPosition}
              onChange={(e) => props.onUpdate({ overlayPosition: e.currentTarget.value as OverlayPosition })}
            >
              {Object.keys(OVERLAY_POSITION_LABELS).map((value) => (
                <option value={value}>{t(POS_KEYS[value as OverlayPosition])}</option>
              ))}
            </select>
          </div>

          <div class="setting-row">
            <span class="setting-label">{t('overlay.fontSize')}</span>
            <input
              type="range"
              class="range-input"
              min="8"
              max="72"
              value={s().overlayFontSize}
              onInput={(e) => props.onUpdate({ overlayFontSize: parseInt(e.currentTarget.value) })}
            />
            <span class="range-value">{s().overlayFontSize}px</span>
          </div>

          <div class="setting-row">
            <span class="setting-label">{t('overlay.textColor')}</span>
            <input
              type="color"
              class="color-input"
              value={s().overlayTextColor.slice(-7)}
              onInput={(e) => props.onUpdate({ overlayTextColor: e.currentTarget.value })}
            />
          </div>

          <div class="setting-row">
            <span class="setting-label">{t('overlay.background')}</span>
            <input
              type="color"
              class="color-input"
              value={`#${s().overlayBackgroundColor.slice(-6)}`}
              onInput={(e) => {
                const hex = e.currentTarget.value
                const alpha = s().overlayBackgroundColor.slice(1, 3) || '80'
                props.onUpdate({ overlayBackgroundColor: `#${alpha}${hex.slice(1)}` })
              }}
            />
          </div>

          <div class="setting-row">
            <span class="setting-label">{t('overlay.padding')}</span>
            <input
              type="range"
              class="range-input"
              min="0"
              max="32"
              value={s().overlayPadding}
              onInput={(e) => props.onUpdate({ overlayPadding: parseInt(e.currentTarget.value) })}
            />
            <span class="range-value">{s().overlayPadding}px</span>
          </div>

          <div class="setting-row">
            <span class="setting-label">{t('overlay.lineHeight')}</span>
            <input
              type="range"
              class="range-input"
              min="0"
              max="20"
              value={s().overlayLineHeight}
              onInput={(e) => props.onUpdate({ overlayLineHeight: parseInt(e.currentTarget.value) })}
            />
            <span class="range-value">{s().overlayLineHeight}px</span>
          </div>
        </div>
      </Show>
    </SettingsCard>
  )
}
