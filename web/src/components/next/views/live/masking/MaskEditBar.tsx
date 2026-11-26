import { Show } from 'solid-js'
import { t } from '../../../../../lib/i18n'
import { IconPlus } from '../../../../icons'
import { Button } from '../../../ui'
import type { MaskEditSession } from './maskEditSession'

export interface MaskEditBarProps {
  /** The mounted editor's controller. */
  session: MaskEditSession
}

/**
 * The masking-zone editor's command bar, above the Live stage while an edit
 * is open. On the left, what to do next — one contextual line, never a
 * legend; on the right, the two verbs the picture cannot carry: arm a draw,
 * and finish.
 *
 * It owns nothing. Every state is read from the session and every change is
 * a session command, so the bar can never drift from the picture: it is the
 * same draft, the same selection, the same "armed" flag the layer draws.
 */
export default function MaskEditBar(props: MaskEditBarProps) {
  const session = props.session
  const drawing = () => session.drawing()

  /** The one hint the moment calls for. Drawing outranks a selection: an armed crosshair is the state to explain. */
  const hint = (): { text: string; keys: boolean } => {
    if (drawing()) return { text: t('privacy.editor.drawHint'), keys: false }
    if (session.selectedId() !== null) return { text: t('privacy.editor.hint'), keys: true }
    if (session.zones().length > 0) return { text: t('privacy.editor.selectHint'), keys: false }
    return { text: t('privacy.editor.drawHint'), keys: false }
  }

  return (
    <div class="nx-mask-bar" role="region" aria-label={t('privacy.editor.title')}>
      <div class="nx-mask-bar-text">
        <span class="nx-mask-bar-title">{t('privacy.editor.title')}</span>
        {/* The hint changes as the operator acts; announced politely, never asserted. */}
        <span class="nx-mask-bar-hint" aria-live="polite">{hint().text}</span>
        {/* The keyboard lives on the picture, where there is room for it. */}
        <Show when={hint().keys}>
          <span class="nx-mask-bar-keys">{t('privacy.editor.keyHint')}</span>
        </Show>
      </div>
      <div class="nx-mask-bar-actions">
        <Button
          size="sm"
          variant={drawing() ? 'toggled' : 'secondary'}
          ariaPressed={drawing()}
          onClick={() => session.toggleDrawing()}
        >
          <IconPlus size={14} />
          {t('privacy.addZone')}
        </Button>
        <Button size="sm" variant="primary" onClick={() => session.finish()}>
          {t('privacy.editor.done')}
        </Button>
      </div>
    </div>
  )
}
