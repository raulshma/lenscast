import { createEffect, createSignal, Show } from 'solid-js'
import type { ConnectionQualityStatus, DeviceStatus } from '../types'
import { useSignalHistory } from '../hooks/useSignalHistory'
import { formatBytes } from '../format'
import Sparkline from './Sparkline'
import { t } from '../lib/i18n'

interface Props {
  /** The full device status: connection quality drives the panel, battery feeds the trend sparkline. */
  status: () => DeviceStatus | undefined
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

export default function ConnectionQualityIndicator(props: Props) {
  const [expanded, setExpanded] = createSignal(false)

  const conn = (): ConnectionQualityStatus | undefined => props.status()?.connectionQuality
  const safeNumber = (value: unknown, fallback = 0): number => {
    const n = typeof value === 'number' ? value : Number(value)
    return Number.isFinite(n) ? n : fallback
  }
  const clientDetails = () => conn()?.clientDetails ?? {}
  const qualityLevel = () => conn()?.qualityLevel ?? 'GOOD'
  const colors = () => QUALITY_COLORS[qualityLevel()] ?? QUALITY_COLORS.GOOD
  const label = () => QUALITY_LABELS[qualityLevel()] ?? 'N/A'

  // Sparkline history: both series ride the status payloads the parent
  // already pushes — bandwidth from the connection-quality block, battery
  // from the device status. No extra fetch lane.
  const bandwidthHistory = useSignalHistory()
  const batteryHistory = useSignalHistory()

  createEffect(() => {
    const quality = props.status()?.connectionQuality
    if (!quality) return
    bandwidthHistory.push(safeNumber(quality.estimatedBandwidthKbps))
  })

  createEffect(() => {
    const level = props.status()?.battery?.level
    if (level == null) return
    batteryHistory.push(safeNumber(level))
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
        <Show when={conn()}>
          <span
            style={{
              'font-size': '10px',
              'font-family': 'ui-monospace, monospace',
              color: 'rgba(255, 255, 255, 0.7)',
            }}
          >
            {safeNumber(conn()?.framesPerSecond).toFixed(1)} fps · {safeNumber(conn()?.avgThroughputKbps)} kbps
          </span>
        </Show>
      </div>

      <Show when={expanded() && conn()}>
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

          <div style={{ display: 'flex', 'flex-direction': 'column', gap: '4px' }}>
            <CqStatRow label={t('cq.quality')} value={label()} valueColor={colors().dot} />
            <CqStatRow label={t('cq.bandwidth')} value={`${safeNumber(conn()?.estimatedBandwidthKbps)} kbps`} />
            <CqStatRow label={t('cq.avgThroughput')} value={`${safeNumber(conn()?.avgThroughputKbps)} kbps`} />
            <CqStatRow label={t('cq.minThroughput')} value={`${safeNumber(conn()?.minThroughputKbps)} kbps`} />
            <CqStatRow label={t('cq.latency')} value={`${safeNumber(conn()?.worstLatencyMs)} ms`} />
            <CqStatRow label={t('cq.avgFrame')} value={`${(safeNumber(conn()?.avgFrameSizeBytes) / 1024).toFixed(1)} KB`} />
            <CqStatRow label="FPS" value={safeNumber(conn()?.framesPerSecond).toFixed(1)} />
            <CqStatRow label={t('cq.clients')} value={`${safeNumber(conn()?.activeClients)}`} />
            <CqStatRow label={t('cq.totalSent')} value={formatBytes(safeNumber(conn()?.totalBytesSent))} />
          </div>

          <Show when={bandwidthHistory.values().length > 1 || batteryHistory.values().length > 1}>
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
                {t('cq.trend')}
              </div>
              <div style={{ display: 'flex', 'flex-direction': 'column', gap: '6px' }}>
                <CqSparklineRow
                  label={t('cq.bandwidth')}
                  value={`${safeNumber(conn()?.estimatedBandwidthKbps)} kbps`}
                  samples={bandwidthHistory.values()}
                  color={colors().dot}
                  title={t('cq.bandwidthHistory')}
                />
                <CqSparklineRow
                  label={t('cq.battery')}
                  value={`${lastBatteryPercent()}%`}
                  samples={batteryHistory.values()}
                  color="rgba(255, 255, 255, 0.6)"
                  title={t('cq.batteryHistory')}
                />
              </div>
            </div>
          </Show>

          <Show when={Object.keys(clientDetails()).length > 0}>
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
                {t('cq.perClient')}
              </div>
              <div style={{ display: 'flex', 'flex-direction': 'column', gap: '6px' }}>
                {Object.entries(clientDetails()).map(([clientId, detail]) => (
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
                      <CqStatRow label={t('cq.throughput')} value={`${safeNumber(detail?.avgThroughputKbps)} kbps`} compact />
                      <CqStatRow label={t('cq.latency')} value={`${safeNumber(detail?.lastSendDurationMs)} ms`} compact />
                      <CqStatRow label={t('cq.frame')} value={`${(safeNumber(detail?.lastFrameSizeBytes) / 1024).toFixed(1)} KB`} compact />
                      <CqStatRow label={t('cq.frames')} value={`${safeNumber(detail?.framesSent)}`} compact />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </Show>
        </div>
      </Show>
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
