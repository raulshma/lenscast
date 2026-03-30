import type { JSX } from 'solid-js'

interface Props {
  icon: JSX.Element
  title: string
  children: JSX.Element
}

export default function SettingsCard(props: Props) {
  return (
    <div class="settings-card">
      <div class="settings-card-header">
        <span class="settings-card-icon">{props.icon}</span>
        <h3 class="settings-card-title">{props.title}</h3>
      </div>
      <div class="settings-card-body">
        {props.children}
      </div>
    </div>
  )
}
