import { formatDate, formatTime } from './i18n'

/**
 * The byte/size and duration formatting shared by the two media browsers —
 * the classic Gallery overlay and the next UI's Media view used to carry
 * private copies that had already drifted once (the >0 guard). One home so
 * a display change lands in both skins at once.
 */

/** Human byte size ("1.5 MB"); empty for absent sizes (0/negative). */
export function formatFileSize(bytes: number): string {
  if (bytes <= 0) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(i > 0 ? 1 : 0)} ${units[i]}`
}

/** Recording length as m:ss (90_000 → "1:30"). */
export function formatDuration(ms: number): string {
  const totalSec = Math.floor(ms / 1000)
  const min = Math.floor(totalSec / 60)
  const sec = totalSec % 60
  return `${min}:${sec.toString().padStart(2, '0')}`
}

/** A capture timestamp as "Mon D HH:MM" in the viewer's locale. */
export function formatDateLabel(ts: number): string {
  const d = new Date(ts)
  return formatDate(d, { month: 'short', day: 'numeric' }) +
    ' ' + formatTime(d, { hour: '2-digit', minute: '2-digit' })
}
