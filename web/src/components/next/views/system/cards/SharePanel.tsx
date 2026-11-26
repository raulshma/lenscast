import { createEffect, createSignal, onCleanup, Show } from 'solid-js'
import { toCanvas } from 'qrcode'
import { copyText, dashboardUrl } from '../../../../../lib/share'
import { t } from '../../../../../lib/i18n'
import { Panel, Button } from '../../../ui'
import { SettingItem } from '../../../ui/SettingItem'

function CopyButton(props: { text: () => string; label: string }) {
  const [copied, setCopied] = createSignal(false)
  let timer: ReturnType<typeof setTimeout> | null = null
  onCleanup(() => { if (timer) clearTimeout(timer) })

  async function handleCopy() {
    const ok = await copyText(props.text(), {
      writeText: (t) => navigator.clipboard.writeText(t),
      execCopy: (t) => {
        const el = document.createElement('textarea')
        el.value = t
        el.style.position = 'fixed'
        el.style.opacity = '0'
        document.body.appendChild(el)
        el.select()
        const okExec = document.execCommand('copy')
        document.body.removeChild(el)
        return okExec
      },
    })
    if (ok) {
      setCopied(true)
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => setCopied(false), 1500)
    }
  }

  return (
    <Button size="sm" variant="ghost" onClick={() => void handleCopy()} ariaLabel={copied() ? t('share.copied') : props.label}>
      <Show when={copied()} fallback={
        <svg class="w-4 h-4" aria-hidden="true" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">
          <path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71" />
          <path d="M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.71" />
        </svg>
      }>
        <svg class="w-4 h-4" aria-hidden="true" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">
          <path d="M20 6L9 17l-5-5" />
        </svg>
      </Show>
      <span>{copied() ? t('share.copied') : props.label}</span>
    </Button>
  )
}

/**
 * Share the dashboard, ported from the classic ShareCard onto the kit
 * Panel: a QR of the LAN URL (point another phone's camera at it), the URL
 * itself with a copy button, and the per-media deep-link hint. URL building
 * is pure lib/share logic; the QR renders onto a canvas via the small
 * `qrcode` dependency — that render chain (error fallback included) is kept
 * verbatim along with the share-* presentation classes.
 */
export default function SharePanel() {
  const url = () => dashboardUrl({
    origin: typeof location !== 'undefined' ? location.origin : '',
    pathname: typeof location !== 'undefined' ? location.pathname : '/',
  })
  const [qrError, setQrError] = createSignal(false)
  let canvas: HTMLCanvasElement | undefined

  createEffect(() => {
    const target = url()
    if (!target || !canvas) return
    let cancelled = false
    setQrError(false)
    // margin: 4 paints the spec quiet zone inside the canvas itself — the
    // console's QR wrap is a paper plate, not a white card (cards.css).
    toCanvas(canvas, target, { width: 148, margin: 4, errorCorrectionLevel: 'M' })
      .catch(() => { if (!cancelled) setQrError(true) })
    onCleanup(() => { cancelled = true })
  })

  return (
    <Panel title={t('share.title')}>
      <SettingItem id="share.links">
        <div class="share-card-body">
        <div class="share-qr-wrap" title={t('share.qrTitle')}>
          <Show when={!qrError()} fallback={
            <span class="share-qr-error">{t('share.qrError')}</span>
          }>
            <canvas ref={canvas} class="share-qr-canvas" aria-label={t('share.qrAria')} />
          </Show>
        </div>
        <div class="share-card-copy">
          <code class="share-url">{url()}</code>
          <div class="nx-deterrence">
            <CopyButton text={url} label={t('share.copyUrl')} />
          </div>
          <p class="share-hint">
            {t('share.hint1')} {t('share.hint2')}
            {' '}<code>#/gallery/&lt;file&gt;</code>
          </p>
          <p class="share-hint">
            {t('share.usbHint')}
            {' '}<code>adb forward tcp:8080 tcp:8080</code>
          </p>
        </div>
      </div>
      </SettingItem>
    </Panel>
  )
}
