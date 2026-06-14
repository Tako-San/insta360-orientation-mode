package com.panorama.core.orientation

import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.inverse

/** Holds the calibration reference and yields the gaze quaternion relative to it.
 *  No sign/sensitivity (that is calibration, Task 1.5) and no smoothing (Task 1.4) here:
 *  this stage only re-bases the raw orientation against the captured zero point.
 *
 *  relativeTo(current) = current * reference^-1, so when current == reference the result is
 *  identity. The default reference is identity, so before any calibrate() the output equals
 *  the input. For unit quaternions inverse == conjugate; we use the library inverse. */
class OrientationProcessor {
    private var reference: Quaternion = Quaternion()

    /** Capture [q] as the new zero point; subsequent relativeTo(q) returns identity. */
    fun calibrate(q: Quaternion) {
        reference = q
    }

    /** Gaze quaternion of [current] relative to the calibration reference. */
    fun relativeTo(current: Quaternion): Quaternion = current * inverse(reference)
}
