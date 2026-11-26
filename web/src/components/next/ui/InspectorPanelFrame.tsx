import type { Accessor, JSX } from 'solid-js'
import { Show } from 'solid-js'
import { t } from '../../../lib/i18n'
import type { WebUiSectionPlacementDto } from '../../../types'
import { IconChevronDown, IconChevronRight, IconMinus } from '../../icons'
import IconButton from './IconButton'
import { InspectorSectionContext } from './SettingItem'
import { inspectorSection, inspectorSectionTitle } from '../views/live/inspectorRegistry'

interface Props {
  section: WebUiSectionPlacementDto
  readOnly: Accessor<boolean>
  onToggleCollapsed?: () => void
  onHide?: () => void
  children: JSX.Element
  emptyState?: JSX.Element
}

export default function InspectorPanelFrame(props: Props) {
  const definition = () => inspectorSection(props.section.sectionId)
  const title = () => props.section.kind === 'custom'
    ? props.section.customTitle || t('next.inspector.configurator.pinnedControls')
    : props.section.customTitle || t(inspectorSectionTitle(props.section.sectionId))
  const allHidden = () => props.section.kind === 'custom'
    ? props.section.items.length === 0
    : (definition()?.itemIds.length ?? 0) > 0
      && definition()!.itemIds.every((id) => props.section.hiddenItems.includes(id))
  const hiddenItems = (): readonly string[] => props.section.hiddenItems
  const readOnly = (): boolean => props.readOnly()

  return (
    <div
      class="nx-inspector-frame"
      classList={{
        'nx-inspector-frame-collapsed': props.section.collapsed,
        'nx-inspector-frame-hidden': allHidden(),
      }}
      data-section-id={props.section.sectionId}
      aria-label={title()}
    >
      <InspectorSectionContext.Provider
        value={{
          hiddenItems,
          readOnly,
          title,
          allHidden,
          emptyState: props.emptyState,
          headerActions: (
            <div class="nx-inspector-frame-actions">
              <IconButton
                label={props.section.collapsed
                  ? t('next.inspector.section.expand', { title: title() })
                  : t('next.inspector.section.collapse', { title: title() })}
                size="sm"
                variant="ghost"
                ariaExpanded={!props.section.collapsed}
                disabled={props.readOnly() || !props.onToggleCollapsed}
                onClick={props.onToggleCollapsed}
              >
                <ShowCollapsedIcon collapsed={props.section.collapsed} />
              </IconButton>
              <Show when={props.onHide}>
                <IconButton
                  label={t('next.inspector.section.hide', { title: title() })}
                  size="sm"
                  variant="ghost"
                  disabled={props.readOnly()}
                  onClick={props.onHide}
                >
                  <IconMinus size={14} />
                </IconButton>
              </Show>
            </div>
          ),
        }}
      >
        {props.children}
      </InspectorSectionContext.Provider>
    </div>
  )
}

function ShowCollapsedIcon(props: { collapsed: boolean }) {
  return props.collapsed ? <IconChevronRight size={14} /> : <IconChevronDown size={14} />
}
