import { createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import { kickStreamClient, listStreamClients } from '../api/client'
import SettingsCard from './SettingsCard'

interface ClientSummary {
  httpClients: string[]
  httpCount: number
  rtspCount: number
  maxHttp: number
}

/**
 * Live viewer list with kick. The backend already exposed
 * GET/DELETE /api/stream/clients; this is its missing surface.
 * Self-polls on a visibility-gated 3s cadence so the list stays
 * honest without touching the main status ladder.
 */
export default function ClientsCard() {
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
      setError(e?.message || 'Failed to list clients')
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
      setError(e?.message || 'Kick failed')
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
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <rect x="2" y="3" width="20" height="14" rx="2" />
          <line x1="8" y1="21" x2="16" y2="21" />
          <line x1="12" y1="17" x2="12" y2="21" />
        </svg>
      }
      title="Connected Clients"
    >
      <div class="clients-card-body">
        <Show when={error()}>
          <div class="status-banner status-banner-error" role="alert">
            <span class="status-banner-dot" aria-hidden="true" />
            <span>{error()}</span>
          </div>
        </Show>

        <Show when={clients()} fallback={
          <div class="clients-empty">Waiting for client data…</div>
        }>
          <div class="clients-count-row">
            <span class="clients-count-badge" title="HTTP / RTSP viewers">
              {clients()!.httpCount}<span class="clients-count-sep">HTTP</span>{clients()!.rtspCount}<span class="clients-count-sep">RTSP</span>
            </span>
          </div>
          <ul class="clients-list">
            <For each={clients()!.httpClients} fallback={
              <li class="clients-empty">
                No HTTP viewers connected
                <Show when={clients()!.rtspCount > 0}>
                  {' '}· {clients()!.rtspCount} RTSP client{clients()!.rtspCount === 1 ? '' : 's'}
                </Show>
              </li>
            }>
              {(id) => (
                <li class="client-row">
                  <span class="client-id" title={id}>{id}</span>
                  <Show when={kicked() === id} fallback={
                    <button
                      type="button"
                      class="client-kick-btn"
                      disabled={kicking() !== ''}
                      onClick={() => void handleKick(id)}
                    >
                      {kicking() === id ? 'Kicking…' : 'Kick'}
                    </button>
                  }>
                    <span class="client-kicked">Kicked</span>
                  </Show>
                </li>
              )}
            </For>
          </ul>
          <div class="clients-cap-row">
            <span>{clients()!.httpCount} / {clients()!.maxHttp} HTTP viewer slots in use</span>
          </div>
        </Show>
      </div>
    </SettingsCard>
  )
}
