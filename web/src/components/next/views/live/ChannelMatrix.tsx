import { For, Show } from 'solid-js'
import type { NextState } from '../../NextState'
import { t } from '../../../../lib/i18n'
import { Led, Panel, type LedTone } from '../../ui'
import { SettingItem } from '../../ui/SettingItem'
import { channelStates, type ChannelState } from '../../lib/telemetry'

/**
 * The channel rack: one row per output (WEB / RTSP / RTMP / WHIP) with
 * its LED, state code and start/stop verb. This is the first surface in
 * the next UI that exposes RTMP/WHIP control outside the System rack.
 * Status codes are console vocabulary (ON AIR / SYNC / FAULT / RDY) and
 * stay untranslated, like the channel codes themselves — the start/stop
 * buttons are ordinary verbs and ride the i18n catalogs.
 */
export default function ChannelMatrix(props: { state: NextState }) {
  const state = props.state

  const statusText = (c: ChannelState): string => {
    // Blocked outranks on air: the row is nominally live but the camera is
    // denied under lock — the console says so instead of a green lie.
    if (c.status === 'blocked') return 'LOCKED'
    if (c.active) return 'ON AIR'
    if (c.status === 'connecting') return 'SYNC'
    if (c.status === 'error') return 'FAULT'
    return c.enabled ? 'RDY' : 'OFF'
  }

  const statusTone = (c: ChannelState): string => {
    // On air is the tally state — amber per the color law (DESIGN.md).
    // SYNC and LOCKED read soft amber (a condition that heals); red is
    // faults only; RDY / OFF stay quiet (chrome, not news).
    if (c.status === 'blocked') return 'nx-channel-status-warn'
    if (c.active) return 'nx-channel-status-air'
    if (c.status === 'connecting') return 'nx-channel-status-warn'
    if (c.status === 'error') return 'nx-channel-status-fault'
    return ''
  }

  // The rack's own Led mapping: amber is on air (the tally), amber hollow
  // is the wait (SYNC, LOCKED), red is the fault, off is chrome. Local to
  // this surface — the statusline chips keep their own healthy-idle green.
  const ledTone = (c: ChannelState): LedTone => {
    if (c.status === 'blocked') return 'warn'
    if (c.active) return 'rec'
    if (c.status === 'connecting') return 'warn'
    if (c.status === 'error') return 'danger'
    return 'off'
  }

  const canToggle = (c: ChannelState): boolean => c.enabled && !state.isViewer()

  const toggle = (c: ChannelState) => {
    if (state.streamActionLoading() || !canToggle(c)) return
    if (c.code === 'WEB') {
      if (c.active) state.handleStopWebStream()
      else state.handleStartWebStream()
    } else if (c.code === 'RTSP') {
      if (c.active) state.handleStopRtspStream()
      else state.handleStartRtspStream()
    } else if (c.code === 'RTMP') {
      if (c.active) state.handleStopRtmp()
      else state.handleStartRtmp()
    } else if (c.code === 'WHIP') {
      if (c.active) state.handleStopWhip()
      else state.handleStartWhip()
    }
  }

  return (
    <SettingItem id="channels.status">
      <Panel title={t('next.channels')} badge={<Led tone={state.streamActionLoading() ? 'warn' : 'off'} />}>
        <For each={channelStates(state.status())}>
          {(channel) => (
            <div class="nx-channel">
              <div class="nx-channel-head">
                <Led tone={ledTone(channel)} />
                <span class="nx-channel-code">{channel.code}</span>
                <span class={`nx-channel-status ${statusTone(channel)}`}>{statusText(channel)}</span>
                <Show when={canToggle(channel)}>
                  <button
                    type="button"
                    class="nx-channel-btn"
                    classList={{ 'nx-channel-btn-stop': channel.active }}
                    disabled={state.streamActionLoading()}
                    onClick={() => toggle(channel)}
                  >
                    {channel.active ? t('common.stop') : t('common.start')}
                  </button>
                </Show>
              </div>
              <Show when={channel.error}>
                <div class="nx-banner nx-banner-danger nx-channel-error" role="alert">
                  {channel.error}
                </div>
              </Show>
            </div>
          )}
        </For>
      </Panel>
    </SettingItem>
  )
}
