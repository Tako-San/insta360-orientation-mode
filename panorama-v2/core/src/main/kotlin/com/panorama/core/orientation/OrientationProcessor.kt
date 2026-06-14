package com.panorama.core.orientation

import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.inverse

/** Holds the calibration reference and yields the gaze quaternion relative to it.
 *  No sign/sensitivity (that is calibration, Task 1.5) and no smoothing (Task 1.4) here:
 *  this stage only re-bases the raw orientation against the captured zero point.
 *
 *  relativeTo(current) = reference^-1 * current, so when current == reference the result is
 *  identity. The default reference is identity, so before any calibrate() the output equals
 *  the input. For unit quaternions inverse == conjugate; we use the library inverse.
 *
 *  Frame note (Phase 4 root-cause fix): the rotation-vector orientation is device->world, so a
 *  LEFT-multiplied delta (current * reference^-1) expresses the motion in the fixed WORLD frame,
 *  where a horizontal head-turn is a rotation about world-up and yawPitchOf (which reads yaw from
 *  the forward vector's X/Z) cannot see it — that produced the "horizontal turn does nothing,
 *  vertical tilt works" symptom. RIGHT-multiplying (reference^-1 * current) instead re-bases the
 *  delta into the DEVICE/body frame that yawPitchOf assumes (forward = -Z, up = +Y), so a turn
 *  about the device's own up axis becomes pure yaw and a tilt about its right axis becomes pure
 *  pitch. This is the only place that frame choice lives. */
class OrientationProcessor {
    private var reference: Quaternion = Quaternion()

    /** Capture [q] as the new zero point; subsequent relativeTo(q) returns identity. */
    fun calibrate(q: Quaternion) {
        reference = q
    }

    /** Gaze quaternion of [current] relative to the calibration reference, in the device/body
     *  frame (see class note). */
    fun relativeTo(current: Quaternion): Quaternion = inverse(reference) * current
}
