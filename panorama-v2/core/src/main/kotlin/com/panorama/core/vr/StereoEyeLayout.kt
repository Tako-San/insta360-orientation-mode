package com.panorama.core.vr

import com.panorama.core.math.GazeState
import com.panorama.core.math.yawPitchOf
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion

/** Split-screen VR layout: per-eye render scale, eye spacing, and the seekbar mappings the
 *  settings dialog uses to drive them. The mappings are linear and invertible so a stored value
 *  reads straight back onto the slider (eyeScale/spacing <-> [0,1] seek progress).
 *
 *  Defaults bracket the on-phone tuning range; Phase 3 wires the dialog to these. */
data class StereoEyeLayout(
    val minScale: Float = 0.5f,
    val maxScale: Float = 1.5f,
    val minSpacing: Float = 0f,
    val maxSpacing: Float = 0.2f,
) {
    fun eyeScaleFromSeek(progress: Float): Float = minScale + progress * (maxScale - minScale)
    fun seekFromEyeScale(scale: Float): Float = (scale - minScale) / (maxScale - minScale)

    fun spacingFromSeek(progress: Float): Float = minSpacing + progress * (maxSpacing - minSpacing)
    fun seekFromSpacing(spacing: Float): Float = (spacing - minSpacing) / (maxSpacing - minSpacing)

    companion object {
        private val Y_AXIS = Float3(0f, 1f, 0f)

        /** Splits [base] into (left, right) eye gazes straddling it by [ipdYawDeg]: left eye yaw =
         *  base - ipd/2, right eye yaw = base + ipd/2, by pre-rotating the base quaternion about +Y
         *  (Ry(delta) * base adds delta to the gaze yaw). yaw/pitch fields are recomputed from the
         *  rotated quaternion so the GazeState stays self-consistent. */
        fun stereoGaze(base: GazeState, ipdYawDeg: Float): Pair<GazeState, GazeState> {
            val half = ipdYawDeg / 2f
            val left = rotatedGaze(base, -half)
            val right = rotatedGaze(base, half)
            return left to right
        }

        private fun rotatedGaze(base: GazeState, deltaYawDeg: Float): GazeState {
            val q = Quaternion.fromAxisAngle(Y_AXIS, deltaYawDeg) * base.quaternion
            val (yaw, pitch) = yawPitchOf(q)
            return base.copy(quaternion = q, yawDeg = yaw, pitchDeg = pitch)
        }
    }
}
