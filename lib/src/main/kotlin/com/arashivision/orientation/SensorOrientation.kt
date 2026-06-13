package com.arashivision.orientation

/**
 * The already-extracted device orientation for one sensor frame — the result of
 * RotationMatrixMath (native getRotationMatrixFromVector + remapCoordinateSystem +
 * getOrientation, with axis selection for the display rotation). Pure data for OrientationProcessor.
 *
 * @param quaternion the orientation as a quaternion (from the remapped matrix)
 * @param rawYawDeg yaw from getOrientation (degrees), azimuth
 * @param rawPitchDeg the pitch component, already selected by display orientation (degrees)
 * @param rawRollDeg roll (degrees), for debugging
 */
data class SensorOrientation(
    val quaternion: Quaternion,
    val rawYawDeg: Float,
    val rawPitchDeg: Float,
    val rawRollDeg: Float
)
