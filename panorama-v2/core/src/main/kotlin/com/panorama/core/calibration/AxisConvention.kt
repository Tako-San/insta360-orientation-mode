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
 * Phase 4 tuning: the full set of axis-correction knobs lives here so on-device calibration is a
 * matter of editing this one data class (no logic edits, then rebuild). [swapYawPitch] exchanges
 * the two axes; [yawSign]/[pitchSign] flip each direction. Together they cover all 8 combinations
 * (swap x 2 signs) needed to map any device-frame convention onto the screen:
 *   - horizontal head-turn moves the wrong way  -> flip [yawSign]
 *   - vertical tilt moves the wrong way          -> flip [pitchSign]
 *   - turning horizontally pans vertically (axes transposed) -> set [swapYawPitch] = true
 *
 * @param yawSign sign applied to yaw before building the view rotation about +Y.
 * @param pitchSign sign applied to pitch before building the view rotation about +X.
 * @param swapYawPitch when true, the gaze yaw and pitch are exchanged before signs are applied
 *        (handles a device frame whose horizontal/vertical axes are transposed vs the screen).
 * @param glForwardIsMinusZ true when the eye looks down -Z (OpenGL convention); matches the
 *        forward vector (0,0,-1) used by [com.panorama.core.math.yawPitchOf].
 */
data class AxisConvention(
    val yawSign: Float = -1f,
    val pitchSign: Float = -1f,
    val swapYawPitch: Boolean = false,
    val glForwardIsMinusZ: Boolean = true,
)
