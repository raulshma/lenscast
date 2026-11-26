export const INSPECTOR_CATEGORIES = [
  'live', 'camera', 'capture', 'streaming', 'storage', 'links', 'detection', 'admin',
] as const

export type InspectorCategory = typeof INSPECTOR_CATEGORIES[number]

export interface InspectorSectionDefinition {
  id: string
  category: InspectorCategory
  titleKey: string
  keywords: readonly string[]
  itemIds: readonly string[]
}

export const INSPECTOR_SECTIONS: readonly InspectorSectionDefinition[] = [
  { id: 'channels', category: 'live', titleKey: 'next.channels', keywords: ['channel', 'live', 'web', 'rtsp', 'rtmp', 'whip'], itemIds: ['channels.status'] },
  { id: 'clients', category: 'live', titleKey: 'clients.title', keywords: ['client', 'viewer', 'http', 'rtsp', 'kick'], itemIds: ['clients.list'] },
  { id: 'storage', category: 'live', titleKey: 'storage.title', keywords: ['storage', 'retention', 'quota', 'forecast'], itemIds: ['storage.summary', 'storage.retention', 'storage.quota'] },
  { id: 'masking-quick', category: 'live', titleKey: 'privacy.title', keywords: ['mask', 'privacy', 'zone', 'edit'], itemIds: ['masking.enabled', 'masking.edit'] },
  { id: 'system-info', category: 'live', titleKey: 'system.title', keywords: ['system', 'diagnostics', 'camera', 'restart', 'reset'], itemIds: ['system.diagnostics', 'system.reset-camera', 'system.restart'] },
  { id: 'lens', category: 'camera', titleKey: 'system.section.lens', keywords: ['lens', 'camera', 'front', 'back'], itemIds: ['lens.select'] },
  { id: 'exposure', category: 'camera', titleKey: 'exposure.title', keywords: ['exposure', 'ev', 'iso', 'shutter', 'time'], itemIds: ['exposure.compensation', 'exposure.iso', 'exposure.time'] },
  { id: 'focus', category: 'camera', titleKey: 'focus.title', keywords: ['focus', 'mode', 'distance'], itemIds: ['focus.mode', 'focus.distance'] },
  { id: 'white-balance', category: 'camera', titleKey: 'wb.title', keywords: ['white balance', 'wb', 'kelvin', 'temperature'], itemIds: ['whiteBalance.preset', 'whiteBalance.temperature'] },
  { id: 'zoom-frame', category: 'camera', titleKey: 'zoomframe.title', keywords: ['zoom', 'fps', 'frame rate', 'resolution', 'rotation', 'orientation'], itemIds: ['zoomFrame.zoom', 'zoomFrame.frameRate', 'zoomFrame.resolution', 'zoomFrame.rotation', 'zoomFrame.orientationLocked'] },
  { id: 'effects', category: 'camera', titleKey: 'effects.title', keywords: ['effects', 'stabilization', 'hdr', 'scene'], itemIds: ['effects.stabilization', 'effects.hdr', 'effects.sceneMode'] },
  { id: 'night-vision', category: 'camera', titleKey: 'nightvision.title', keywords: ['night', 'vision', 'ir'], itemIds: ['nightVision.mode'] },
  { id: 'overlay', category: 'camera', titleKey: 'system.section.overlays', keywords: ['overlay', 'timestamp', 'branding', 'status', 'text', 'style'], itemIds: ['overlay.enabled', 'overlay.timestamp', 'overlay.branding', 'overlay.status', 'overlay.customText', 'overlay.style'] },
  { id: 'privacy-masking', category: 'camera', titleKey: 'privacy.title', keywords: ['privacy', 'mask', 'zone', 'edit'], itemIds: ['masking.enabled', 'masking.zones', 'masking.edit'] },
  { id: 'interval-capture', category: 'capture', titleKey: 'interval.title', keywords: ['interval', 'timelapse', 'capture'], itemIds: ['interval.status', 'interval.config', 'interval.start-stop'] },
  { id: 'recording', category: 'capture', titleKey: 'recording.title', keywords: ['recording', 'record', 'audio', 'quality', 'duration'], itemIds: ['recording.status', 'recording.start-stop', 'recording.audio', 'recording.quality', 'recording.duration'] },
  { id: 'watchdog', category: 'capture', titleKey: 'watchdog.title', keywords: ['watchdog', 'fsi', 'crash', 'heartbeat'], itemIds: ['watchdog.enabled', 'watchdog.fsiResume', 'watchdog.crashGuard', 'watchdog.heartbeat'] },
  { id: 'web-stream', category: 'streaming', titleKey: 'webstream.title', keywords: ['web', 'mjpeg', 'jpeg', 'quality', 'bitrate'], itemIds: ['streaming.webEnabled', 'streaming.webQuality', 'streaming.resolution', 'streaming.adaptiveBitrate'] },
  { id: 'audio', category: 'streaming', titleKey: 'audio.title', keywords: ['audio', 'microphone', 'sound'], itemIds: ['streaming.audioEnabled'] },
  { id: 'rtsp', category: 'streaming', titleKey: 'rtsp.title', keywords: ['rtsp', 'rtsps', 'server'], itemIds: ['streaming.rtspEnabled'] },
  { id: 'whip', category: 'streaming', titleKey: 'whip.title', keywords: ['whip', 'webrtc'], itemIds: ['streaming.whipEnabled'] },
  { id: 'rtmp', category: 'streaming', titleKey: 'rtmp.title', keywords: ['rtmp', 'push'], itemIds: ['streaming.rtmpEnabled'] },
  { id: 'https', category: 'streaming', titleKey: 'https.title', keywords: ['https', 'tls', 'certificate'], itemIds: ['streaming.httpsEnabled'] },
  { id: 'keep-awake', category: 'streaming', titleKey: 'keepawake.title', keywords: ['keep awake', 'screen', 'wake'], itemIds: ['streaming.keepAwake'] },
  { id: 'recording-timeline', category: 'storage', titleKey: 'timeline.title', keywords: ['recording', 'timeline', 'nvr', '24h'], itemIds: ['timeline.recordings'] },
  { id: 'multicam', category: 'links', titleKey: 'multicam.title', keywords: ['multicam', 'multi', 'camera', 'tiles'], itemIds: ['multicam.list'] },
  { id: 'share', category: 'links', titleKey: 'share.title', keywords: ['share', 'qr', 'url', 'link'], itemIds: ['share.links'] },
  { id: 'push', category: 'links', titleKey: 'push.title', keywords: ['push', 'notification', 'web push'], itemIds: ['push.enabled', 'push.subscription'] },
  { id: 'unifiedpush', category: 'links', titleKey: 'unifiedPush.title', keywords: ['unifiedpush', 'push', 'notification'], itemIds: ['unifiedpush.enabled', 'unifiedpush.subscription'] },
  { id: 'mqtt', category: 'links', titleKey: 'mqtt.title', keywords: ['mqtt', 'broker', 'topic'], itemIds: ['mqtt.enabled', 'mqtt.connection'] },
  { id: 'event-log', category: 'detection', titleKey: 'events.title', keywords: ['event', 'log', 'motion', 'sound', 'sse'], itemIds: ['events.list'] },
  { id: 'detection-stats', category: 'detection', titleKey: 'stats.title', keywords: ['detection', 'stats', '24h', '7d', 'motion'], itemIds: ['detection.stats'] },
  { id: 'security', category: 'detection', titleKey: 'security.title', keywords: ['security', 'motion', 'sound', 'ml', 'alert', 'siren', 'webhook'], itemIds: ['security.motion', 'security.motionSensitivity', 'security.sound', 'security.ml', 'security.alerts', 'security.deterrence', 'security.webhook', 'security.siren', 'security.torch', 'security.testAlert'] },
  { id: 'automation', category: 'detection', titleKey: 'automation.title', keywords: ['automation', 'rule', 'schedule', 'test'], itemIds: ['automation.rules', 'automation.test'] },
  { id: 'auth', category: 'admin', titleKey: 'auth.title', keywords: ['auth', 'admin', 'viewer', 'password'], itemIds: ['auth.config'] },
  { id: 'backup', category: 'admin', titleKey: 'backup.title', keywords: ['backup', 'webdav', 'cloud'], itemIds: ['backup.config'] },
  { id: 'config-backup', category: 'admin', titleKey: 'config.title', keywords: ['config', 'backup', 'export', 'import'], itemIds: ['configBackup.actions'] },
  { id: 'audit', category: 'admin', titleKey: 'audit.title', keywords: ['audit', 'log', 'action'], itemIds: ['audit.list'] },
] as const

const SECTION_BY_ID = new Map(INSPECTOR_SECTIONS.map((section) => [section.id, section]))

export function inspectorSection(sectionId: string): InspectorSectionDefinition | undefined {
  return SECTION_BY_ID.get(sectionId)
}

export function inspectorSectionTitle(sectionId: string): string {
  return inspectorSection(sectionId)?.titleKey ?? sectionId
}
