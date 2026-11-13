interface Props {
  w?: string
  h?: string
  class?: string
}

/** Loading placeholder block; pulse animation from the kit CSS. */
export default function Skeleton(props: Props) {
  return (
    <div
      class={`nx-skeleton${props.class ? ` ${props.class}` : ''}`}
      style={{ width: props.w ?? '100%', height: props.h ?? '12px' }}
      aria-hidden="true"
    />
  )
}
