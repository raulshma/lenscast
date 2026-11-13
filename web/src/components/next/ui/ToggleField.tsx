import FieldRow from './FieldRow'
import Switch from './Switch'

interface Props {
  label: string
  hint?: string
  checked: boolean | undefined
  disabled?: boolean
  onChange: (v: boolean) => void
}

/** The settings row for booleans: label (+ hint) left, switch right. */
export default function ToggleField(props: Props) {
  return (
    <FieldRow label={props.label} hint={props.hint}>
      <Switch
        checked={props.checked}
        disabled={props.disabled}
        onChange={props.onChange}
        ariaLabel={props.label}
      />
    </FieldRow>
  )
}
