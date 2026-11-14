import { createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import { kickStreamClient, listStreamClients } from '../../../../api/client'
import { t, tCount } from '../../../../lib/i18n'
import { Panel, Badge, Empty, Button } from '../../ui'

interface ClientSummary {
  httpClients: string[]
  httpCount: number
  rtspCount: number
  maxHttp: number
}

/**
 * Connected viewers with kick, ported from the classic ClientsCard.
 * Self-polls on a visibility-gated 3s cadence so the list stays honest
 * without touching the main status ladder. A viewer-role session sees
 * the list but the kick buttons are disabled — the list itself is a read.
 */
export default function ClientsPanel(props: { readOnly?: () => boolean } = {}) {
  const [clients, setClients] = createSignal<ClientSummary | null>(null)
  const [error, setError] = createSignal('')
  const [kicking, setKicking] = createSignal('')
  const [kicked, setKicked] = createSignal('')

  async function refresh() {
    if (typeof document !== 'undefined' && document.hidden) return
    try {
      setClients(await listStreamClients())
      setError('')
    } catch (e: any) {
      setError(e?.message || t('clients.listFailed'))
    }
  }

  async function handleKick(id: string) {
    if (kicking()) return
    setKicking(id)
    try {
      await kickStreamClient(id)
      setKicked(id)
      setTimeout(() => setKicked(''), 2500)
      await refresh()
    } catch (e: any) {
      setError(e?.message || t('clients.kickFailed'))
    } finally {
      setKicking('')
    }
  }

  onMount(() => {
    void refresh()
    const timer = setInterval(refresh, 3000)
    onCleanup(() => clearInterval(timer))
  })

  return (
    <Panel
      title={t('clients.title')}
      badge={
        <Show when={clients()}>
          <Badge tone={clients()!.httpCount > 0 ? 'accent' : 'neutral'} mono>
            {clients()!.httpCount}
          </Badge>
        </Show>
      }
    >
      <Show when={error()}>
        <div class="nx-banner nx-banner-danger" role="alert">{error()}</div>
      </Show>

      <Show when={clients()} fallback={<Empty title={t('clients.waiting')} />}>
        <div class="nx-channel-head">
          <Badge tone="neutral" mono>HTTP {clients()!.httpCount}</Badge>
          <Badge tone="neutral" mono>RTSP {clients()!.rtspCount}</Badge>
        </div>
        <ul class="nx-clients">
          <For each={clients()!.httpClients} fallback={
            <li class="nx-clients-empty">
              {t('clients.noHttp')}.
              <Show when={clients()!.rtspCount > 0}>
                {' '}{tCount('clients.rtspCount', clients()!.rtspCount)}.
              </Show>
            </li>
          }>
            {(id) => (
              <li class="nx-clients-row">
                <span class="nx-clients-id nx-mono" title={id}>{id}</span>
                <Show when={kicked() === id} fallback={
                  <Button
                    size="sm"
                    variant="danger-soft"
                    disabled={kicking() !== '' || props.readOnly?.() === true}
                    title={props.readOnly?.() === true ? t('clients.readOnlyTitle') : undefined}
                    onClick={() => void handleKick(id)}
                    ariaLabel={t('clients.kick')}
                  >
                    {kicking() === id ? t('clients.kicking') : t('clients.kick')}
                  </Button>
                }>
                  <span class="nx-clients-kicked">{t('clients.kicked')}</span>
                </Show>
              </li>
            )}
          </For>
        </ul>
        <div class="nx-field-hint">
          {t('clients.slotsInUse', { used: clients()!.httpCount, max: clients()!.maxHttp })}
        </div>
      </Show>
    </Panel>
  )
}
