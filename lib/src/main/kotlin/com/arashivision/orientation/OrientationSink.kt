package com.arashivision.orientation

/**
 * Panorama orientation sink. The implementation applies (yaw, pitch) to a concrete
 * player view; the calling code does not know the details (the SDK reflection is hidden in the implementation).
 */
interface OrientationSink {
    fun apply(yawDeg: Float, pitchDeg: Float)
}
