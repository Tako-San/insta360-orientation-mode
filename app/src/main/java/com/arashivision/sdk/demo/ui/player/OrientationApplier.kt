package com.arashivision.sdk.demo.ui.player

/**
 * Port for rotating the player sphere to the given yaw/pitch (degrees). Hides the concrete mechanism
 * (media3 reflection in [Media3SphericalOrientationSink]); in tests it is replaced by a fake
 * that records the angles passed in.
 */
interface OrientationApplier {
    fun apply(yawDeg: Float, pitchDeg: Float)
}
