package com.panorama.core.orientation

import com.panorama.core.math.GazeState
import com.panorama.core.math.yawPitchOf
import dev.romainguy.kotlin.math.Quaternion
import kotlin.math.abs

/** Motion-to-photon lead: extrapolate the gaze forward by leadTimeMs along its current smoothed
 *  angular velocity (axis + speed produced by [OrientationSmoothing]). This hides sensor->display
 *  latency so the rendered view sits where the head will be when the frame lands, not where it was. */
object GazePredictor {

    fun predict(gaze: GazeState, leadTimeMs: Float): GazeState {
        val angle = gaze.angularVelocityDegPerSec * (leadTimeMs / 1000f)
        if (abs(angle) < EPS_DEG) return gaze

        // World-frame (left-multiply) delta, matching OrientationSmoothing's axis convention
        // (axis is recovered from smoothed * prev^-1), so a positive speed leads the gaze further
        // in the same direction it is already turning.
        val deltaQuat = Quaternion.fromAxisAngle(gaze.angularAxis, angle)
        val predicted = deltaQuat * gaze.quaternion

        val (yaw, pitch) = yawPitchOf(predicted)
        return gaze.copy(quaternion = predicted, yawDeg = yaw, pitchDeg = pitch)
    }

    private const val EPS_DEG = 1e-4f
}
