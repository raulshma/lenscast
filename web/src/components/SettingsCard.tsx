import { Show, type JSX } from 'solid-js'

interface Props {
  icon: JSX.Element
  title: string
  /** Optional live-state chip (StatusChip) pinned to the header's right edge. */
  badge?: JSX.Element
  children: JSX.Element
}

export default function SettingsCard(props: Props) {
  return (
    <div class="settings-card">
      <div class="settings-card-header">
        <span class="settings-card-icon">{props.icon}</span>
        <h3 class="settings-card-title">{props.title}</h3>
        <Show when={props.badge}>{props.badge}</Show>
      </div>
      <div class="settings-card-body">
        {props.children}
      </div>
    </div>
  )
}
