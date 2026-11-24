import { createEffect, createSignal, For, Show } from 'solid-js'
import type { JSX } from 'solid-js'
import type { ConnectionQualityStatus, DeviceStatus } from '../types'
import type { PlayerMode } from '../video/playerLadder'
import { useSignalHistory } from '../hooks/useSignalHistory'
import { formatBytes } from '../format'
import Sparkline from './Sparkline'
import { t } from '../lib/i18n'
import { encodedLane, finiteOrNull, pickPreviewLane, type LaneStats } from '../lib/streamStats'

interface Props {
  /** The full device status: the picked lane drives the readout, battery feeds the trend sparkline. */
  status: () => DeviceStatus | undefined
  /**
   * The preview's player rung — the panel follows its lane (MJPEG rung → the
   * MJPEG clients' measured lane; WHEP/H264/HLS → the encoded lane). Optional
   * only for non-preview hosts: no rung means the encoded lane.
   */
  previewMode?: () => PlayerMode
}

const QUALITY_COLORS: Record<string, { dot: string; bg: string }> = {
  EXCELLENT: { dot: '#4CAF50', bg: 'rgba(76, 175, 80, 0.15)' },
  GOOD: { dot: '#8BC34A', bg: 'rgba(139, 195, 74, 0.15)' },
  FAIR: { dot: '#FFC107', bg: 'rgba(255, 193, 7, 0.15)' },
  POOR: { dot: '#FF9800', bg: 'rgba(255, 152, 0, 0.15)' },
  CRITICAL: { dot: '#F44336', bg: 'rgba(244, 67, 54, 0.15)' },
}

const QUALITY_LABELS: Record<string, string> = {
  EXCELLENT: 'EXC',
  GOOD: 'GOOD',
  FAIR: 'FAIR',
  POOR: 'POOR',
  CRITICAL: 'CRIT',
}

/** Display view of the lib's one coerce shape (finiteOrNull): non-finite
    reads as absence, shown as the honest 0. */
const num = (value: unknown): number => finiteOrNull(value) ?? 0

export default function ConnectionQualityIndicator(props: Props) {
  const [expanded, setExpanded] = createSignal(false)

  const conn = (): ConnectionQualityStatus | undefined => props.status()?.connectionQuality
  // The preview's lane: measured numbers only, null when the lane has no
  // data (the gate in StreamPreview keeps this hidden then).
  const lane = (): LaneStats | null => pickPreviewLane(props.previewMode?.(), props.status())
  const isMjpegLane = () => lane()?.lane === 'mjpeg'
  const clientDetails = () => conn()?.clientDetails ?? {}
  const qualityLevel = () => conn()?.qualityLevel ?? 'GOOD'
  const colors = () => QUALITY_COLORS[qualityLevel()] ?? QUALITY_COLORS.GOOD
  const label = () => QUALITY_LABELS[qualityLevel()] ?? 'N/A'
  const encoded = () => props.status()?.encodedStream
  const hasClients = () => Object.keys(clientDetails()).length > 0

  // Sparkline history: both series ride the status payloads the parent
  // already pushes — throughput from the picked lane's measured bitrate
  // (the same number the badge shows, never the ladders' congestion
  // estimate), battery from the device status. No extra fetch lane.
  const throughputHistory = useSignalHistory()
  const batteryHistory = useSignalHistory()

  // The sparkline never mixes lanes: a rung switch (MJPEG ↔ encoded) resets
  // the series, so a sample from the previous lane can never sit next to one
  // from the new one (the reset fires before the push — one effect, in
  // order).
  let historyLane: LaneStats['lane'] | null = null
  createEffect(() => {
    const kind = lane()?.lane ?? null
    if (kind !== historyLane) {
      historyLane = kind
      throughputHistory.reset()
    }
    const kbps = lane()?.bitrateKbps
    if (kbps == null) return
    throughputHistory.push(kbps)
  })

  createEffect(() => {
    const level = props.status()?.battery?.level
    if (level == null) return
    batteryHistory.push(num(level))
  })

  const lastBatteryPercent = () => {
    const values = batteryHistory.values()
    return values.length > 0 ? values[values.length - 1] : 0
  }

  return (
    <div class="connection-quality-wrapper" style={{ position: 'relative' }}>
      <div
        class="cq-badge"
        onClick={() => setExpanded(!expanded())}
        style={{
          cursor: 'pointer',
          background: 'rgba(0, 0, 0, 0.7)',
          'border-radius': '12px',
          padding: '6px 10px',
          display: 'inline-flex',
          'flex-direction': 'column',
          'align-items': 'flex-end',
          gap: '2px',
          'min-width': '80px',
        }}
      >
        {/* The quality verdict belongs to the MJPEG lane's network monitor;
            an encoded-lane badge shows numbers only. */}
        <Show when={isMjpegLane()}>
          <div style={{ display: 'flex', 'align-items': 'center', gap: '4px' }}>
            <div
              style={{
                width: '8px',
                height: '8px',
                'border-radius': '50%',
                background: colors().dot,
              }}
            />
            <span
              style={{
                'font-size': '11px',
                'font-weight': '700',
                'font-family': 'ui-monospace, monospace',
                color: '#fff',
              }}
            >
              {label()}
            </span>
          </div>
        </Show>
        <Show when={lane()}>
          <span
            style={{
              'font-size': '10px',
              'font-family': 'ui-monospace, monospace',
              color: 'rgba(255, 255, 255, 0.7)',
            }}
          >
            {num(lane()?.fps).toFixed(1)} fps · {num(lane()?.bitrateKbps)} kbps
          </span>
        </Show>
      </div>

      <Show when={expanded() && (lane() || encoded() || hasClients())}>
        <div
          class="cq-detail-panel"
          style={{
            position: 'absolute',
            top: '100%',
            right: '0',
            'margin-top': '6px',
            width: '260px',
            background: 'rgba(28, 28, 30, 0.95)',
            'backdrop-filter': 'blur(12px)',
            'border-radius': '12px',
            padding: '12px',
            'z-index': '100',
            'box-shadow': '0 8px 32px rgba(0, 0, 0, 0.4)',
            border: '1px solid rgba(255, 255, 255, 0.08)',
          }}
        >
          <div
            style={{
              'font-size': '12px',
              'font-weight': '700',
              color: '#fff',
              'margin-bottom': '8px',
            }}
          >
            {t('cq.title')}
          </div>

          {/* Preview — the active lane's measured numbers. */}
          <Show when={lane()}>
            <PanelSection title={t('cq.section.preview')}>
              <Show when={isMjpegLane()} fallback={<EncodedStatRows lane={lane} />}>
                <div style={{ display: 'flex', 'flex-direction': 'column', gap: '4px' }}>
                  <CqStatRow label={t('cq.quality')} value={label()} valueColor={colors().dot} />
                  <CqStatRow label={t('cq.avgThroughput')} value={`${num(conn()?.avgThroughputKbps)} kbps`} />
                  <CqStatRow label={t('cq.minThroughput')} value={`${num(conn()?.minThroughputKbps)} kbps`} />
                  {/* worstLatencyMs is the worst client's last frame send
                      duration (the send loop's stopwatch), not network
                      latency — the label says so now. */}
                  <CqStatRow label={t('cq.sendTime')} value={`${num(conn()?.worstLatencyMs)} ms`} />
                  <CqStatRow label={t('cq.avgFrame')} value={`${(num(conn()?.avgFrameSizeBytes) / 1024).toFixed(1)} KB`} />
                  <CqStatRow label={t('cq.fps')} value={num(conn()?.framesPerSecond).toFixed(1)} />
                  <CqStatRow label={t('cq.clients')} value={`${num(conn()?.activeClients)}`} />
                  <CqStatRow label={t('cq.totalSent')} value={formatBytes(num(conn()?.totalBytesSent))} />
                </div>
              </Show>
            </PanelSection>
          </Show>

          {/* Encoded stream — the shared encoder's measured output whenever
              the block exists (presence IS the running fact). Shown even
              while the preview rides another lane: it is device truth, not
              the preview's lane. */}
          <Show when={encoded()}>
            <PanelSection title={t('cq.section.encoded')}>
              <EncodedStatRows lane={() => encodedLane(encoded())} />
            </PanelSection>
          </Show>

          <Show when={throughputHistory.values().length > 1 || batteryHistory.values().length > 1}>
            <PanelSection title={t('cq.trend')}>
              <div style={{ display: 'flex', 'flex-direction': 'column', gap: '6px' }}>
                <CqSparklineRow
                  label={t('cq.throughput')}
                  value={`${num(lane()?.bitrateKbps)} kbps`}
                  samples={throughputHistory.values()}
                  color={isMjpegLane() ? colors().dot : 'rgba(255, 255, 255, 0.6)'}
                  title={t('cq.throughputHistory')}
                />
                <CqSparklineRow
                  label={t('cq.battery')}
                  value={`${lastBatteryPercent()}%`}
                  samples={batteryHistory.values()}
                  color="rgba(255, 255, 255, 0.6)"
                  title={t('cq.batteryHistory')}
                />
              </div>
            </PanelSection>
          </Show>

          <Show when={hasClients()}>
            <PanelSection title={t('cq.mjpegViewers')}>
              <div style={{ display: 'flex', 'flex-direction': 'column', gap: '6px' }}>
                <For each={Object.entries(clientDetails())}>
                  {([clientId, detail]) => (
                    <div
                      style={{
                        background: 'rgba(255, 255, 255, 0.04)',
                        'border-radius': '6px',
                        padding: '6px 8px',
                      }}
                    >
                      <div
                        style={{
                          'font-size': '10px',
                          'font-weight': '600',
                          color: 'rgba(255, 255, 255, 0.7)',
                          'margin-bottom': '4px',
                        }}
                      >
                        {t('cq.client', { id: clientId.slice(0, 8) })}
                      </div>
                      <div style={{ display: 'flex', 'flex-direction': 'column', gap: '2px' }}>
                        <CqStatRow label={t('cq.throughput')} value={`${num(detail?.avgThroughputKbps)} kbps`} compact />
                        <CqStatRow label={t('cq.sendTime')} value={`${num(detail?.lastSendDurationMs)} ms`} compact />
                        <CqStatRow label={t('cq.frame')} value={`${(num(detail?.lastFrameSizeBytes) / 1024).toFixed(1)} KB`} compact />
                        <CqStatRow label={t('cq.frames')} value={`${num(detail?.framesSent)}`} compact />
                      </div>
                    </div>
                  )}
                </For>
              </div>
            </PanelSection>
          </Show>
        </div>
      </Show>
    </div>
  )
}

/**
 * The encoded lane's row set — measured bitrate vs target, fps, codec,
 * resolution. One helper for both places the encoded lane renders: the
 * Preview section's fallback and the "Encoded stream" section (which reads
 * the status block even while the preview rides another lane).
 */
function EncodedStatRows(props: { lane: () => LaneStats | null }) {
  return (
    <div style={{ display: 'flex', 'flex-direction': 'column', gap: '4px' }}>
      <CqStatRow label={t('cq.encoded.bitrate')} value={`${num(props.lane()?.bitrateKbps)} kbps`} />
      <CqStatRow label={t('cq.encoded.target')} value={`${num(props.lane()?.targetBitrateKbps)} kbps`} />
      <CqStatRow label={t('cq.fps')} value={num(props.lane()?.fps).toFixed(1)} />
      <CqStatRow label={t('cq.encoded.codec')} value={props.lane()?.codec ?? '—'} />
      <Show when={props.lane()?.width && props.lane()?.height}>
        <CqStatRow label={t('cq.encoded.resolution')} value={`${props.lane()!.width}×${props.lane()!.height}`} />
      </Show>
    </div>
  )
}

/** One titled panel section: the uppercase header + the rows below it. */
function PanelSection(props: { title: string; children: JSX.Element }) {
  return (
    <div
      style={{
        'margin-top': '8px',
        'padding-top': '8px',
        'border-top': '1px solid rgba(255, 255, 255, 0.08)',
      }}
    >
      <div
        style={{
          'font-size': '10px',
          'font-weight': '600',
          color: 'rgba(255, 255, 255, 0.5)',
          'text-transform': 'uppercase',
          'letter-spacing': '0.5px',
          'margin-bottom': '6px',
        }}
      >
        {props.title}
      </div>
      {props.children}
    </div>
  )
}

function CqStatRow(props: { label: string; value: string; valueColor?: string; compact?: boolean }) {
  return (
    <div style={{ display: 'flex', 'justify-content': 'space-between', 'align-items': 'center' }}>
      <span
        style={{
          'font-size': props.compact ? '9px' : '10px',
          'font-family': 'ui-monospace, monospace',
          color: 'rgba(255, 255, 255, 0.45)',
        }}
      >
        {props.label}
      </span>
      <span
        style={{
          'font-size': props.compact ? '9px' : '10px',
          'font-family': 'ui-monospace, monospace',
          'font-weight': '500',
          color: props.valueColor ?? 'rgba(255, 255, 255, 0.85)',
        }}
      >
        {props.value}
      </span>
    </div>
  )
}

function CqSparklineRow(props: { label: string; value: string; samples: number[]; color: string; title: string }) {
  return (
    <div style={{ display: 'flex', 'justify-content': 'space-between', 'align-items': 'center', gap: '8px' }}>
      <div style={{ display: 'flex', 'flex-direction': 'column', gap: '1px' }}>
        <span
          style={{
            'font-size': '10px',
            'font-family': 'ui-monospace, monospace',
            color: 'rgba(255, 255, 255, 0.45)',
          }}
        >
          {props.label}
        </span>
        <span
          style={{
            'font-size': '10px',
            'font-family': 'ui-monospace, monospace',
            'font-weight': '500',
            color: 'rgba(255, 255, 255, 0.85)',
          }}
        >
          {props.value}
        </span>
      </div>
      <span style={{ color: props.color, 'line-height': '0', 'flex-shrink': '0', display: 'inline-flex' }}>
        <Sparkline samples={props.samples} title={props.title} />
      </span>
    </div>
  )
}
