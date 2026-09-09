/**
 * One label + toggle-switch row (`field-row field-row-toggle`): the on/off
 * setting row every settings card repeats, extracted so the switch markup
 * lives in exactly one place. Props stay on `props.` (not destructured) so
 * Solid's reactivity survives the component boundary.
 */
export default function ToggleRow(props: {
  id: string
  label: string
  checked: boolean
  onToggle: () => void
}) {
  return (
    <div class="field-row field-row-toggle">
      <span class="field-label">{props.label}</span>
      <label class="toggle-switch" for={props.id}>
        <input
          id={props.id}
          type="checkbox"
          checked={props.checked}
          onChange={props.onToggle}
        />
        <span class="toggle-slider" />
      </label>
    </div>
  )
}
