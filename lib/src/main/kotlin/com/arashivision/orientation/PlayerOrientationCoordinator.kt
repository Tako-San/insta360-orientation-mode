package com.arashivision.orientation

/**
 * Pure (JVM) orientation orchestration for the offline player: takes the calibration-relative
 * gaze angles from the gyro, inverts the signs to match the on-screen rotation direction, and
 * smooths them. Returns the final yaw/pitch (degrees), which the Activity turns into a single
 * gaze quaternion fed BOTH into the panorama sphere (our own GL renderer) AND into the gaze
 * direction for the arrow — from one source, so the sphere and the arrow stay in sync.
 *
 * The signs are inverted to match the rotation direction the user expects (clockwise phone turn
 * → image follows the head; pitch flipped) — verified on device by probe A. The exact axis
 * mapping for the GL renderer is pinned in ViewMatrixMath; this coordinator only owns the
 * heading sign + smoothing.
 *
 * @param smoothing adaptive smoothing (suppresses jitter at rest, does not slow down turns)
 */
class PlayerOrientationCoordinator(
    private val smoothing: OrientationSmoothing = OrientationSmoothing()
) {
    /**
     * @param rawGazeYawDeg gaze yaw from the gyro (getGazeYawDeg)
     * @param rawGazePitchDeg gaze pitch from the gyro (getGazePitchDeg)
     * @return the smoothed, inverted angles for the sphere and the arrow
     */
    fun coordinate(rawGazeYawDeg: Float, rawGazePitchDeg: Float): TargetOrientation {
        val invertedYaw = -rawGazeYawDeg
        val invertedPitch = -rawGazePitchDeg
        return smoothing.update(invertedYaw, invertedPitch)
    }

    /** Reset the smoothing (for example, when the player is recreated). */
    fun reset() = smoothing.reset()
}
