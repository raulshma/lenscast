import { For, Show } from 'solid-js'
import type { NextState } from '../../NextState'
import { t } from '../../../../lib/i18n'
import { Led, Panel } from '../../ui'
import { channelStates, channelLed, type ChannelState } from '../../lib/telemetry'

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
    if (c.active) return 'ON AIR'
    if (c.status === 'connecting') return 'SYNC'
    if (c.status === 'error') return 'FAULT'
    return c.enabled ? 'RDY' : 'OFF'
  }

  const statusTone = (c: ChannelState): string => {
    // On air is the signal state — magenta per the color law (DESIGN.md).
    // Green stays for healthy-idle (RDY); red is faults only.
    if (c.active) return 'nx-channel-status-air'
    if (c.status === 'connecting') return 'nx-channel-status-warn'
    if (c.status === 'error') return 'nx-channel-status-fault'
    return ''
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
    <Panel title={t('next.channels')} badge={<Led tone={state.streamActionLoading() ? 'warn' : 'off'} />}>
      <For each={channelStates(state.status())}>
        {(channel) => (
          <div class="nx-channel">
            <div class="nx-channel-head">
              <Led tone={channelLed(channel)} />
              <span class="nx-channel-code">{channel.code}</span>
              <span class={`nx-channel-status ${statusTone(channel)}`}>{statusText(channel)}</span>
              <Show when={canToggle(channel)}>
                <button
                  type="button"
                  class="nx-channel-btn"
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
  )
}
