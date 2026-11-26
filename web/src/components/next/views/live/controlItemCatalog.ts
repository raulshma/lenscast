import { INSPECTOR_SECTIONS, inspectorSection } from './inspectorRegistry'

export interface ControlItemDefinition {
  id: string
  sectionId: string
  labelKey: string
  keywords: readonly string[]
  custom: boolean
}

const ITEM_LABEL_KEYS: Readonly<Record<string, string>> = {
  'channels.status': 'next.channels',
  'clients.list': 'clients.slotsTitle',
  'storage.summary': 'storage.forecast',
  'storage.retention': 'storage.keepCaptures',
  'storage.quota': 'storage.quota',
  'masking.enabled': 'privacy.enable',
  'masking.zones': 'privacy.zones',
  'masking.edit': 'privacy.editZones',
  'system.diagnostics': 'system.title',
  'system.reset-camera': 'system.resetCamera',
  'system.restart': 'system.restart',
  'lens.select': 'system.section.lens',
  'exposure.compensation': 'exposure.compensation',
  'exposure.iso': 'exposure.iso',
  'exposure.time': 'exposure.time',
  'focus.mode': 'focus.mode',
  'focus.distance': 'focus.distance',
  'whiteBalance.preset': 'wb.title',
  'whiteBalance.temperature': 'wb.temp',
  'zoomFrame.zoom': 'zoomframe.zoom',
  'zoomFrame.frameRate': 'zoomframe.frameRate',
  'zoomFrame.resolution': 'common.resolution',
  'zoomFrame.rotation': 'zoomframe.rotation',
  'zoomFrame.orientationLocked': 'zoomframe.orientationLocked',
  'effects.stabilization': 'effects.stabilization',
  'effects.hdr': 'effects.hdr',
  'effects.sceneMode': 'effects.scene',
  'nightVision.mode': 'nightvision.title',
  'overlay.enabled': 'overlay.enable',
  'overlay.timestamp': 'overlay.timestamp',
  'overlay.branding': 'overlay.branding',
  'overlay.status': 'overlay.status',
  'overlay.customText': 'overlay.custom',
  'overlay.style': 'next.inspector.item.overlay.style',
  'interval.status': 'interval.running',
  'interval.config': 'interval.interval',
  'interval.start-stop': 'interval.start',
  'recording.status': 'common.status',
  'recording.start-stop': 'recording.start',
  'recording.audio': 'recording.audio',
  'recording.quality': 'recording.quality',
  'recording.duration': 'recording.duration',
  'watchdog.enabled': 'watchdog.enable',
  'watchdog.fsiResume': 'watchdog.fsiResume',
  'watchdog.crashGuard': 'watchdog.crashGuard',
  'watchdog.heartbeat': 'watchdog.heartbeat',
  'streaming.webEnabled': 'webstream.enable',
  'streaming.webQuality': 'webstream.jpegQuality',
  'streaming.resolution': 'webstream.resolution',
  'streaming.adaptiveBitrate': 'webstream.adaptive',
  'streaming.audioEnabled': 'audio.includeLive',
  'streaming.rtspEnabled': 'rtsp.enable',
  'streaming.whipEnabled': 'whip.enable',
  'streaming.rtmpEnabled': 'rtmp.enable',
  'streaming.httpsEnabled': 'https.serve',
  'streaming.keepAwake': 'keepawake.enable',
  'timeline.recordings': 'timeline.title',
  'multicam.list': 'multicam.title',
  'share.links': 'share.title',
  'push.enabled': 'push.title',
  'push.subscription': 'next.inspector.item.push.subscription',
  'unifiedpush.enabled': 'unifiedPush.title',
  'unifiedpush.subscription': 'next.inspector.item.unifiedpush.subscription',
  'mqtt.enabled': 'mqtt.title',
  'mqtt.connection': 'common.status',
  'events.list': 'events.title',
  'detection.stats': 'stats.title',
  'security.motion': 'security.motion',
  'security.motionSensitivity': 'security.sensitivity',
  'security.sound': 'security.sound',
  'security.ml': 'security.ml',
  'security.alerts': 'security.localAlerts',
  'security.deterrence': 'security.deterrence',
  'security.webhook': 'security.webhook',
  'security.siren': 'security.sirenOn',
  'security.torch': 'preview.torch',
  'security.testAlert': 'security.sendTestAlert',
  'automation.rules': 'automation.title',
  'automation.test': 'next.inspector.item.automation.test',
  'auth.config': 'auth.title',
  'backup.config': 'backup.title',
  'configBackup.actions': 'config.title',
  'audit.list': 'audit.title',
}

const CUSTOM_CONTROL_IDS = new Set([
  'exposure.compensation', 'exposure.iso', 'exposure.time',
  'focus.mode', 'focus.distance', 'whiteBalance.preset', 'whiteBalance.temperature',
  'zoomFrame.zoom', 'zoomFrame.frameRate', 'zoomFrame.resolution', 'zoomFrame.rotation', 'zoomFrame.orientationLocked',
  'effects.stabilization', 'effects.hdr', 'nightVision.mode',
  'channels.status', 'streaming.webEnabled', 'streaming.webQuality', 'streaming.resolution', 'streaming.adaptiveBitrate',
  'streaming.audioEnabled', 'streaming.rtspEnabled', 'streaming.whipEnabled', 'streaming.rtmpEnabled', 'streaming.keepAwake',
  'recording.start-stop', 'recording.audio', 'recording.quality', 'interval.start-stop',
  'security.siren', 'security.torch', 'security.testAlert', 'capture.snapshot',
  'security.motion', 'security.motionSensitivity', 'security.sound', 'security.ml',
  'overlay.enabled', 'masking.enabled', 'masking.edit',
])

const BUILTIN_ITEMS: ControlItemDefinition[] = INSPECTOR_SECTIONS.flatMap((section) =>
  section.itemIds.map((id) => ({
    id,
    sectionId: section.id,
    labelKey: ITEM_LABEL_KEYS[id] ?? `next.inspector.item.${id}`,
    keywords: [id, ...section.keywords],
    custom: CUSTOM_CONTROL_IDS.has(id),
  })),
)

export const CONTROL_ITEMS: readonly ControlItemDefinition[] = [
  ...BUILTIN_ITEMS,
  {
    id: 'capture.snapshot',
    sectionId: 'capture',
    labelKey: 'next.inspector.item.capture.snapshot',
    keywords: ['snapshot', 'photo', 'capture'],
    custom: true,
  },
]

const ITEM_BY_ID = new Map(CONTROL_ITEMS.map((item) => [item.id, item]))

export function controlItem(itemId: string): ControlItemDefinition | undefined {
  return ITEM_BY_ID.get(itemId)
}

export function customControlItems(): ControlItemDefinition[] {
  return CONTROL_ITEMS.filter((item) => item.custom)
}

export function searchControlItems(query: string, customOnly = false): ControlItemDefinition[] {
  const needle = query.trim().toLocaleLowerCase()
  return CONTROL_ITEMS.filter((item) => {
    if (customOnly && !item.custom) return false
    if (!needle) return true
    const section = inspectorSection(item.sectionId)
    return [item.id, item.labelKey, ...item.keywords, section?.id ?? '', section?.category ?? '']
      .join(' ')
      .toLocaleLowerCase()
      .includes(needle)
  })
}
