package com.panorama.core.fov

import com.panorama.core.math.GazeState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.normalize
import kotlin.math.atan2

/** Quaternion FOV test against the viewer's gaze. The viewer looks down local -Z (the canonical
 *  base shared with quatFromYawPitch / ViewCalibration), so a world direction is brought into the
 *  viewer frame with inverse(gaze.quaternion) and compared against the forward axis.
 *
 *  No axis signs live here — the only convention this file knows is "forward is -Z", which is the
 *  same forward the calibration maps detections onto, so the two stay in lock-step (spec sec 6). */
object PanoramaFov {

    /** Rotate [targetDir] into the viewer's local frame (forward = -Z). */
    private fun toLocal(gaze: GazeState, targetDir: Float3): Float3 =
        inverse(gaze.quaternion) * normalize(targetDir)

    /** True when [targetDir] falls within the horizontal and vertical half-FOV cones around the
     *  viewer's forward (-Z). Targets at or behind the lateral plane (forward component <= 0) are
     *  always outside. */
    fun isInsideFov(gaze: GazeState, targetDir: Float3, hFovRad: Float, vFovRad: Float): Boolean {
        val local = toLocal(gaze, targetDir)
        val forward = -local.z
        if (forward <= 0f) return false
        val hAngle = atan2(local.x, forward)
        val vAngle = atan2(local.y, forward)
        return kotlin.math.abs(hAngle) <= hFovRad / 2f &&
            kotlin.math.abs(vAngle) <= vFovRad / 2f
    }

    /** Screen-space angle (radians) of the direction toward an off-FOV [targetDir], measured in
     *  the viewer's local frame: atan2(localY, localX), so 0 points screen-right and +pi/2 up.
     *  Defined for targets outside the FOV; the value still points toward the target when behind. */
    fun arrowAngle(gaze: GazeState, targetDir: Float3): Float {
        val local = toLocal(gaze, targetDir)
        return atan2(local.y, local.x)
    }
}
