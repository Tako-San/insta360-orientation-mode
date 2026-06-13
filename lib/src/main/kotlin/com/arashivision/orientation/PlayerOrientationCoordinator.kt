package com.arashivision.orientation

/**
 * Pure (JVM) orientation orchestration for the offline player: takes the calibration-relative
 * gaze angles from the gyro, inverts the signs to match the media3 direction, and smooths them.
 * Returns the final yaw/pitch (degrees), which the Activity feeds BOTH into the sphere (via
 * OrientationApplier) AND into the gaze direction for the arrow — from a single source, so that
 * the sphere and the arrow stay in sync.
 *
 * The signs are inverted: media3 onScrollChange rotates the sphere opposite to the phone's
 * rotation (clockwise → image counter-clockwise), and pitch is flipped (confirmed by probe A).
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
