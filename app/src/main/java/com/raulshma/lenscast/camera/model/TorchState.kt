package com.raulshma.lenscast.camera.model

/**
 * The LIVE torch fact as a three-state machine, distinct from the persisted
 * [CameraSettings.torchEnabled]/[CameraSettings.torchStrength] pair — those
 * are INTENT (what the settings layer wants the next apply to drive), while
 * this state is what the flash is actually doing right now. The two diverge
 * by design: remote writers (the Web API's torch toggle, the deterrence
 * coordinator) flip the live torch through
 * [com.raulshma.lenscast.camera.CameraService.setTorchEnabled] without ever
 * persisting, so a settings read must not be mistaken for the torch's state.
 *
 * - [Off] — torch dark. Also the state while NO camera is bound: the live
 *   torchState fact dies with the camera session, and a dark flash is the
 *   only honest publication for an unbound camera.
 * - `On(null)` — torch lit at the device default brightness: no strength
 *   level has been driven this torch-on epoch (plain enableTorch, or a
 *   remote flip).
 * - `On(level)` — torch lit at a strength level the service actually drove
 *   through the API 33+ ladder (see [TorchStrengthPolicy]); the level is the
 *   service's last driven verdict, not the persisted request.
 *
 * [com.raulshma.lenscast.camera.CameraService] is the only writer, fed from
 * the bound camera's cameraInfo torchState observation plus the strength it
 * last drove; `isTorchOn()` derives from the same flow.
 */
sealed class TorchState {

    /** Torch dark — including "no camera bound to observe". */
    data object Off : TorchState()

    /**
     * Torch lit. [strengthLevel] null = the device default brightness (no
     * strength level driven this epoch); non-null = the level the service
     * last drove onto the flash.
     */
    data class On(val strengthLevel: Int?) : TorchState()

    companion object {

        /**
         * The one mapping from the two inputs the service observes — the
         * live cameraInfo torch verdict and the strength it last drove — to
         * the published state. Pure, so the machine stays testable without a
         * device.
         */
        fun from(liveTorchOn: Boolean, drivenStrengthLevel: Int?): TorchState = when {
            !liveTorchOn -> Off
            drivenStrengthLevel == null -> On(null)
            else -> On(drivenStrengthLevel)
        }
    }
}
