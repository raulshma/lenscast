interface Props {
  small?: boolean
}

export default function Spinner(props: Props) {
  return <span class="nx-spinner" classList={{ 'nx-spinner-sm': props.small === true }} role="status" />
}
