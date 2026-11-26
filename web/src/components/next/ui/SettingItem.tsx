import { createContext, useContext, Show, type Accessor, type JSX } from 'solid-js'

export interface InspectorSectionContextValue {
  hiddenItems: Accessor<readonly string[]>
  readOnly: Accessor<boolean>
  title?: Accessor<string>
  headerActions?: JSX.Element
  allHidden?: Accessor<boolean>
  emptyState?: JSX.Element
}

export const InspectorSectionContext = createContext<InspectorSectionContextValue>()

export function useInspectorSection(): InspectorSectionContextValue | undefined {
  return useContext(InspectorSectionContext)
}

export function SettingItem(props: { id: string; children: JSX.Element }) {
  const context = useInspectorSection()
  if (!context) return props.children
  return (
    <Show when={!context.hiddenItems().includes(props.id)} fallback={null}>
      {props.children}
    </Show>
  )
}
