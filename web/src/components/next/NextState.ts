import type { useAppState } from '../../hooks/useAppState'

/**
 * The dispatcher-owned state instance, shared by both shells. Views read the
 * same accessors the classic shell does — one poll ladder, one SSE stream,
 * no duplication.
 */
export type NextState = ReturnType<typeof useAppState>

/**
 * The state accessors StreamPreview always reads — spelled once so the two
 * mounting sites (the legacy shell's stage and the next shell's persistent
 * re-parenting host) cannot drift apart in the player plumbing. The shells
 * add only their own skin: `stripOverlay` and, in legacy, the fullscreen lift.
 */
export function streamPreviewProps(state: NextState) {
  return {
    status: state.status,
    previewVisible: state.previewVisible,
    streamNonce: state.streamNonce,
    streamActionLoading: state.streamActionLoading,
    isRecording: state.isRecording,
    captureMsg: state.captureMsg,
    liveAudioStatus: state.liveAudioStatus,
    recordingTimer: state.recordingTimer,
    playerMode: state.playerMode,
    setPlayerMode: state.setPlayerMode,
    handleCapture: state.handleCapture,
    handleStartWebStream: state.handleStartWebStream,
    handleStopWebStream: state.handleStopWebStream,
    handleStartRtspStream: state.handleStartRtspStream,
    handleStopRtspStream: state.handleStopRtspStream,
    setPreviewVisible: state.setPreviewVisible,
    connectionLost: state.connectionLost,
  }
}
