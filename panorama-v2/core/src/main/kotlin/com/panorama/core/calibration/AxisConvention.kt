package com.panorama.core.calibration

/** The ONE place every coordinate-axis sign in :core lives (spec section 6, "Site A").
 *
 * v1's pain was sign drift: yaw/pitch flips were scattered across the renderer, the gyro
 * controller and the detection mapping, so fixing one axis silently broke another. Here the
 * signs are data, not code. [ViewCalibration] is the only type allowed to apply them; every
 * geometric builder elsewhere (SphereMesh in Task 1.6) is written sign-free so there is exactly
 * one knob to turn when the device says "yaw goes the wrong way".
 *
 * The defaults below make the view matrix the exact inverse of the gaze rotation produced by
 * [com.panorama.core.math.quatFromYawPitch] (Ry(yaw)*Rx(pitch)), so view = Rx(-pitch)*Ry(-yaw).
 * They are the starting guess; Phase 4 tunes them on the phone by editing THIS file only.
 *
 * @param yawSign sign applied to yaw before building the view rotation about +Y.
 * @param pitchSign sign applied to pitch before building the view rotation about +X.
 * @param glForwardIsMinusZ true when the eye looks down -Z (OpenGL convention); matches the
 *        forward vector (0,0,-1) used by [com.panorama.core.math.yawPitchOf].
 */
data class AxisConvention(
    val yawSign: Float = -1f,
    val pitchSign: Float = -1f,
    val glForwardIsMinusZ: Boolean = true,
)
