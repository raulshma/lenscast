import type { useAppState } from '../../hooks/useAppState'

/**
 * The dispatcher-owned state instance, shared by both shells. Views read the
 * same accessors the classic shell does — one poll ladder, one SSE stream,
 * no duplication.
 */
export type NextState = ReturnType<typeof useAppState>
